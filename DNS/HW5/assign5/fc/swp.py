import enum
import logging
import llp
import queue
import struct
import threading
import time

class SWPType(enum.IntEnum):
    DATA = ord('D')
    ACK = ord('A')


class SWPPacket:
    _PACK_FORMAT = '!BI'
    _HEADER_SIZE = struct.calcsize(_PACK_FORMAT)
    MAX_DATA_SIZE = 1400  # Leaves plenty of space for IP + UDP + SWP header

    def __init__(self, type, seq_num, data=b''):
        self._type = type
        self._seq_num = seq_num
        self._data = data

    @property
    def type(self):
        return self._type

    @property
    def seq_num(self):
        return self._seq_num

    @property
    def data(self):
        return self._data

    def to_bytes(self):
        header = struct.pack(SWPPacket._PACK_FORMAT, self._type.value,
                             self._seq_num)
        return header + self._data

    @classmethod
    def from_bytes(cls, raw):
        header = struct.unpack(SWPPacket._PACK_FORMAT,
                               raw[:SWPPacket._HEADER_SIZE])
        type = SWPType(header[0])
        seq_num = header[1]
        data = raw[SWPPacket._HEADER_SIZE:]
        return SWPPacket(type, seq_num, data)

    def __str__(self):
        return "%s %d %s" % (self._type.name, self._seq_num, repr(self._data))


class SWPSender:
    _SEND_WINDOW_SIZE = 5
    _TIMEOUT = 1

    def __init__(self, remote_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(remote_address=remote_address,
                                             loss_probability=loss_probability)

        # Start receive thread
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()

        # TODO: Add additional state variables
        self.pool_sema = threading.BoundedSemaphore(value=SWPSender._SEND_WINDOW_SIZE)
        self.seqnum = 0
        self.buff = {}
        self.Timer = {}
        self.acked_num = 0

    def send(self, data):
        for i in range(0, len(data), SWPPacket.MAX_DATA_SIZE):
            self._send(data[i:i + SWPPacket.MAX_DATA_SIZE])

    def _send(self, data):
        with self.pool_sema:
            self.pool_sema.acquire()

            this_seq = self.seqnum
            temp = SWPPacket(SWPType.DATA, this_seq, data).to_bytes()
            self.buff.update({this_seq: temp})
            self.seqnum += 1  # It should be synchronized

            self._llp_endpoint.send(temp)
            self.Timer.update({this_seq: time.time()})
            while True:
                time.sleep(0.3)
                if self.Timer.get(this_seq) is None:
                    break
                elif time.time() - self.Timer.get(this_seq) > self._TIMEOUT:
                    self._retransmit(this_seq)
                    # todo: should we reset the timer? Yes in 1.1._send.5?

            # self.pool_sema.release()

        return

    def _retransmit(self, seq_num):
        # TODO
        self._llp_endpoint.send(self.buff.get(seq_num))
        self.Timer.update({seq_num: time.time()})

        return

    def _recv(self):
        while True:
            # Receive SWP packet
            raw = self._llp_endpoint.recv()
            if raw is None:
                continue
            packet = SWPPacket.from_bytes(raw)
            logging.debug("Received: %s" % packet)

            # TODO
            if packet.type != SWPType.ACK:
                continue

            # if self.acked_num < packet.seq_num:
            #     self.acked_num = packet.seq_num

            # self.buff.pop(packet.seq_num)
            # self.Timer.pop(packet.seq_num)
            # self.acked_num = packet.seq_num
            for i in range(self.acked_num, packet.seq_num + 1): # remove + 1 for sending highest ack num instead of highest one + 1 ( line 189)
                self.buff.pop(i)
                self.Timer.pop(i)
                self.pool_sema.release()
            # todo: release the lock here in for loop or outside (no congestion control) ?
            self.acked_num = packet.seq_num

            # in the case that the first packet is lost, should not add
            # if self.acked_num < packet.seq_num + 1:  # remove + 1 for sending highest ack num instead of highest one + 1 ( line 189)
            #     self.acked_num = packet.seq_num
            # seq_returned = packet.seq_num - 1  # - packet.MAX_DATA_SIZE
            # self.buff.pop(seq_returned)
            # # todo: release the lock only if we receive ack of new chunk of data!!!
            # self.Timer.pop(seq_returned)
            # self.pool_sema.release()

        return


class SWPReceiver:
    _RECV_WINDOW_SIZE = 5

    def __init__(self, local_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(local_address=local_address,
                                             loss_probability=loss_probability)

        # Received data waiting for application to consume
        self._ready_data = queue.Queue()

        # Start receive thread
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()

        # TODO: Add additional state variables
        self.HAS = -1  # highest acknowledged seq_num
        # self.HASGOTTEN = 0
        self.buff = {}  # for the data, store the packet with SENDING seq_num
        # self.pool_sema = threading.BoundedSemaphore(value=SWPReceiver._RECV_WINDOW_SIZE)

    def recv(self):
        return self._ready_data.get()

    def _recv(self):
        while True:
            # Receive data packet
            raw = self._llp_endpoint.recv()
            packet = SWPPacket.from_bytes(raw)
            logging.debug("Received: %s" % packet)

            # TODO: can it recv a seq <= HAS, yes but we do not need to retransmit it...
            this_seq = packet.seq_num
            if this_seq <= self.HAS:
                # temp = SWPPacket(SWPType.ACK, self.HAS + 1, b'')
                # self._llp_endpoint.send(temp)
                continue

            self.buff.update({this_seq: packet})

            num = self.HAS + 1
            while True:
                temp = self.buff.get(num, None)
                if temp is None:
                    # self.HAS = num - 1
                    break
                else:
                    self._ready_data.put(temp.to_bytes())
                    self.buff.pop(num)
                    num += 1
            # have received sth new and consume
            if self.HAS < num - 1:
                self._llp_endpoint.send(SWPPacket(SWPType.ACK, num - 1, b'').to_bytes())

            self.HAS = num - 1

        return

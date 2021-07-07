package edu.wisc.cs.sdn.vnet.rt;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;
import java.nio.ByteBuffer;

import net.floodlightcontroller.packet.*;


/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;
	
	private Map<Integer, Deque<Ethernet>> arpQueueList;

	private static final int RIP_MULTICAST = IPv4.toIPv4Address("224.0.0.9");

	private Thread requestRipThread;

	private TimerTask reqRipTask;

	private final byte[] MAC_BROADCAST = "FF:FF:FF:FF:FF:FF".getBytes();

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
		this.arpQueueList = new ConcurrentHashMap<Integer, Deque<Ethernet>>(); // <gatewayAddr, Deque<Ethernet_Packets>>

		///////////////// Add timertask for RIP//////////////////////////////
		this.reqRipTask = new TimerTask(){
			@Override
			public void run(){
				for (Iface iface : interfaces.values()){
					sendPacket(createRipPacket(iface, RIP_MULTICAST, MAC_BROADCAST, 0), iface);
				}
			}
		};
		/////////////////////////////////////////////////////////////////////

	}
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
                etherPacket.toString().replace("\n", "\n\t"));
		
		/********************************************************************/
		/* TODO: Handle packets                                             */
		
		switch(etherPacket.getEtherType())
		{
			case Ethernet.TYPE_IPv4:
				this.handleIpPacket(etherPacket, inIface);
				break;
			case Ethernet.TYPE_ARP:
				this.handleArpPacket(etherPacket, inIface);
				break;
		}
		
		/********************************************************************/
	}

	/************************************ ARP START *******************************/

	private void handleArpPacket(Ethernet etherPacket, Iface inIface){
		ARP arpPacket = (ARP)etherPacket.getPayload();
		int targetIp = ByteBuffer.wrap(arpPacket.getTargetProtocolAddress()).getInt();

		for (Iface iface : this.interfaces.values()){
			if (targetIp == iface.getIpAddress()){
				if (arpPacket.getOpCode() == ARP.OP_REQUEST){
					//System.out.println("Arp Request");
					sendArpPacket(0, 1, etherPacket, inIface, inIface); 
					break;
				} else if (arpPacket.getOpCode() == ARP.OP_REPLY){
					//System.out.println("Arp Reply");

					int senderIp = ByteBuffer.wrap(arpPacket.getSenderProtocolAddress()).getInt();
					MACAddress senderMac = new MACAddress(arpPacket.getSenderHardwareAddress());
					arpCache.insert(senderMac,senderIp);

					synchronized(arpQueueList){

						Deque<Ethernet> ether_queue = arpQueueList.remove(senderIp);
						if (ether_queue != null){ //!ether_queue.isEmpty()
							for (Ethernet ether : ether_queue){
								ether.setDestinationMACAddress(senderMac.toBytes());
								sendPacket(ether,inIface);
							}
						}

					}
				}
			}
		}
		
	}

	private void sendArpPacket(int targetIp, int type, Ethernet etherPacket, Iface inface, Iface outface){
		ARP arpHeader = new ARP();
		Ethernet ether = new Ethernet();

		/** Setting Ether Header **/
		ether.setEtherType(Ethernet.TYPE_ARP);
		ether.setSourceMACAddress(inface.getMacAddress().toBytes());

		/** Setting Arp Header **/
		arpHeader.setHardwareType(ARP.HW_TYPE_ETHERNET);
		arpHeader.setProtocolType(ARP.PROTO_TYPE_IP);
		arpHeader.setHardwareAddressLength((byte)Ethernet.DATALAYER_ADDRESS_LENGTH);
		arpHeader.setProtocolAddressLength((byte)4);
		arpHeader.setSenderHardwareAddress(inface.getMacAddress().toBytes());
		arpHeader.setSenderProtocolAddress(inface.getIpAddress());

		if (type == 0){  // type == 0 => ARP_REQUEST
			arpHeader.setOpCode(ARP.OP_REQUEST);
			arpHeader.setTargetHardwareAddress(new byte[]{0,0,0,0,0,0});	 //new byte[]{0,0,0,0,0,0}
			arpHeader.setTargetProtocolAddress(targetIp);
			ether.setDestinationMACAddress("FF:FF:FF:FF:FF:FF"); // set to broadcast;
			//System.out.println("ARP_REQUEST ready to send");
		} else {   // type == 1 => ARP_REPLY
			ARP arpPac = (ARP) etherPacket.getPayload();
			arpHeader.setOpCode(ARP.OP_REPLY);
			arpHeader.setTargetHardwareAddress(arpPac.getSenderHardwareAddress());
			arpHeader.setTargetProtocolAddress(arpPac.getSenderProtocolAddress());
			ether.setDestinationMACAddress(etherPacket.getSourceMACAddress());
			//System.out.println("ARP_REPLY ready to send");
		}
		ether.setPayload(arpHeader);
		//System.out.println("sending ARP Packet");
		this.sendPacket(ether,outface);
	}

	private void handleArpMiss(final int targetIp, final Ethernet etherPacket, final Iface inface, final Iface outface){
		//System.out.println("handling ARP miss");
		IPv4 packet = (IPv4) etherPacket.getPayload();
		final Integer dstAddr = new Integer(packet.getDestinationAddress());  /////// edit 
		RouteEntry entryMatched = this.getRouteTable().lookup(dstAddr);
		if (entryMatched == null){ // drop
			//System.out.println("entryMatched didn't found");
			return; 
		}

		int nextH = entryMatched.getGatewayAddress();
        if (nextH == 0){ 
			nextH = dstAddr; 
		}
		final int nextHop = nextH;

		synchronized (arpQueueList){

			if (arpQueueList.containsKey(nextHop)){
				//System.out.println("has key");
				Deque<Ethernet> queue = arpQueueList.get(nextHop);
				queue.offerLast(etherPacket);
			} else {
				//System.out.println("has not key");
				Deque<Ethernet> queue = new ArrayDeque<Ethernet>();
				queue.offerLast(etherPacket);
				arpQueueList.put(nextHop, queue);

				TimerTask timertask = new TimerTask(){
					int request_sent = 0;
					@Override
					public void run(){
						if (arpCache.lookup(nextHop) == null){
							if (request_sent <= 2){
								sendArpPacket(targetIp, 0, etherPacket, inface, outface);
								//System.out.println("sent Arp");
								request_sent++;
							} else { // sent 3 copies but haven't recieve any response, send ICMP and drop
								arpQueueList.remove(nextHop);
								//System.out.println("sent 3 copies but haven't recieve any response DESTINATION_HOST_NOTREACHABLE");
								sendICMPPacket(3,1,etherPacket, inface);  /** ICMP message: DESTINATION_HOST_UNREACHABLE **/
								this.cancel();
							}
							
						} else {
							this.cancel();
						}
					}
				};
				Timer timer = new Timer();
				timer.scheduleAtFixedRate(timertask, 0, 1000);
			}

		}

	}
	/*************************************** ARP END ************************************/ 

	private void handleIpPacket(Ethernet etherPacket, Iface inIface)
	{
		// Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }
		
		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
        //System.out.println("Handle IP packet");
		if(ipPacket.getProtocol() == IPv4.PROTOCOL_UDP && ((UDP)ipPacket.getPayload()).getDestinationPort() == UDP.RIP_PORT)
		{handleRIPPacket(etherPacket, inIface); return;}

        short origCksum = ipPacket.getChecksum();
        ipPacket.resetChecksum();
        byte[] serialized = ipPacket.serialize();
        ipPacket.deserialize(serialized, 0, serialized.length);
        short calcCksum = ipPacket.getChecksum();
        if (origCksum != calcCksum)
        { return; }

		for (Iface iface : this.interfaces.values()) {
			arpCache.insert(iface.getMacAddress(), iface.getIpAddress());
		}
        
        // Check TTL
        ipPacket.setTtl((byte)(ipPacket.getTtl()-1));
        if (ipPacket.getTtl() == 0)
        { 
			sendICMPPacket(11,0,etherPacket,inIface); /** ICMP message: TIME_EXCEEDED **/
			return; 
		}
        
        // Reset checksum now that TTL is decremented
        ipPacket.resetChecksum();


        
        // Check if packet is destined for one of router's interfaces
        for (Iface iface : this.interfaces.values())
        {
        	if (ipPacket.getDestinationAddress() == iface.getIpAddress())
        	{ 	
				byte protocol = ipPacket.getProtocol();
				if ( protocol == IPv4.PROTOCOL_UDP ||  protocol == IPv4.PROTOCOL_TCP){
					sendICMPPacket(3, 3, etherPacket, inIface);   /** ICMP message: DESTINATION_PORT_UNREACHABLE **/
				} else if (protocol == IPv4.PROTOCOL_ICMP){
					ICMP icmp = (ICMP) ipPacket.getPayload();
					System.out.println("Echo message");
					if (icmp.getIcmpType() == ICMP.TYPE_ECHO_REQUEST){
						sendICMPPacket(0,0,etherPacket, inIface);    /** ICMP message: ECHO_REPLY_MESSAGE **/
					}

				}
				return; 
			}
        }
		
        // Do route lookup and forward
        this.forwardIpPacket(etherPacket, inIface);
	}

    private void forwardIpPacket(Ethernet etherPacket, Iface inIface)
    {
        // Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }
        //System.out.println("Forward IP packet");
		
		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
        int dstAddr = ipPacket.getDestinationAddress();

        // Find matching route table entry 
        RouteEntry bestMatch = this.getRouteTable().lookup(dstAddr);

        // If no entry matched, do nothing
        if (null == bestMatch)
        { 	//System.out.println("DESTINATION_NET_UNREACHABLE");
			sendICMPPacket(3, 0, etherPacket, inIface);  /** ICMP message: DESTINATION_NET_UNREACHABLE **/
			return; 
		}
        // Make sure we don't sent a packet back out the interface it came in
        Iface outIface = bestMatch.getInterface();
        if (outIface == inIface)
        { return; }

        // Set source MAC address in Ethernet header
        etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());

        // If no gateway, then nextHop is IP destination
        int nextHop = bestMatch.getGatewayAddress();
        if (0 == nextHop)
        { nextHop = dstAddr; }

        // Set destination MAC address in Ethernet header
        ArpEntry arpEntry = this.arpCache.lookup(nextHop);
        if (null == arpEntry)
        {   handleArpMiss(nextHop, etherPacket, inIface, outIface);
			return; 
		}
        etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());
        
        this.sendPacket(etherPacket, outIface);
    }

	/***************************************** RIP START ************************************/

	public void startRIP(){
		for (Iface iface : this.interfaces.values()){
			int mask = iface.getSubnetMask();
			int addr = iface.getIpAddress() & mask;
			routeTable.insert(addr, 0, mask, iface, 1, -1, true); // insert(int dstIp, int gwIp, int maskIp, Iface iface, int dist, long time)
		}
		routeTable.enableRIP();
		//System.out.println("startRIP entered");
		Timer timer = new Timer();
		timer.scheduleAtFixedRate(this.reqRipTask, 0, 10000);
	}

	private boolean handleRIPPacket(Ethernet ether, Iface inface){ //maybe send it in this method
		IPv4 ip4 = (IPv4)ether.getPayload();

		UDP udp = (UDP) ip4.getPayload();

		//System.out.println("This is a RIP packet");
		// todo: check the IP to decide the command
		RIPv2 rip = (RIPv2)udp.getPayload();
		if (rip.getCommand() == RIPv2.COMMAND_REQUEST){
			//ip4.getSourceAddress()
			Ethernet resPacket = createRipPacket( inface, ip4.getSourceAddress(), ether.getSourceMACAddress(), 1); // send back a response
			this.sendPacket(resPacket, inface);
		} else if (rip.getCommand() == RIPv2.COMMAND_RESPONSE){
			System.out.println("sending RIP response");
			boolean isupdate = false;
			List<RIPv2Entry> entries = rip.getEntries();
			for (RIPv2Entry entry : entries){
				int ipAddr = entry.getAddress();
				int subnetMask = entry.getSubnetMask();
				int nextHop = ip4.getSourceAddress();
				int distance = entry.getMetric() + 1;
				if(distance > 16) distance = 16;

				RouteEntry rte = routeTable.lookup(ipAddr);
				if (rte != null){
					if (distance >= rte.getDistance()){
//						System.out.println("N: "+nextHop);
//						System.out.println("G: "+rte.getGatewayAddress());
						routeTable.updatetime(ipAddr, subnetMask,System.currentTimeMillis());
					} else{
						isupdate = true;
						routeTable.update(ipAddr, subnetMask, nextHop, inface, distance, System.currentTimeMillis());
					}
					//isupdate = true;
					// need to update curr time !!!
					/////update(int dstIp, int maskIp, int gwIp, Iface iface, int dist, long time)

				} else {
					isupdate = true;
					routeTable.insert(ipAddr, nextHop, subnetMask, inface, distance, System.currentTimeMillis(), false); ///// insert(int dstIp, int gwIp, int maskIp, Iface iface, int dist, long time, boolean P)
				}
			}
			if(isupdate){
				for (Iface iface : interfaces.values()){
					if (iface == inface) continue;
					//System.out.println("sending RIP packets to ifaces");
					sendPacket(createRipPacket(iface, RIP_MULTICAST, MAC_BROADCAST, 2), iface); // todo: it is 0???)
				}
			}
		}
		//System.out.println("RIP packet finished");
		return true;
	}


	private Ethernet createRipPacket(Iface out, int dstIPAddr, byte[] dstMacAddr, int command){
		// Ethernet
		Ethernet ether = new Ethernet();
		ether.setSourceMACAddress(out.getMacAddress().toBytes());
		ether.setEtherType(Ethernet.TYPE_IPv4);
		//

		// ip
		IPv4 ip = new IPv4();
		ip.setTtl((byte)64);
		ip.setSourceAddress(out.getIpAddress());
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		//

		RIPv2 rip = new RIPv2();
		// boardcast unsolicit RIP???
		if (command == 0){ // REQUEST
			rip.setCommand(RIPv2.COMMAND_REQUEST);
			ip.setDestinationAddress(RIP_MULTICAST);
			ether.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");
		} else if (command == 1){ // RESPONSE
			rip.setCommand(RIPv2.COMMAND_RESPONSE);
			ip.setDestinationAddress(dstIPAddr);
			ether.setDestinationMACAddress(dstMacAddr);
		} else if (command == 2){ // UNSOLICITED
			rip.setCommand(RIPv2.COMMAND_RESPONSE);
			ip.setDestinationAddress(RIP_MULTICAST);
			ether.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");
		} 

		// udp
		UDP udp = new UDP();
		udp.setSourcePort(UDP.RIP_PORT);
		udp.setDestinationPort(UDP.RIP_PORT);
		//

		for (RouteEntry e : routeTable.getEntries()){
			rip.addEntry(new RIPv2Entry(e.getDestinationAddress(), e.getMaskAddress(), e.getDistance()));
		}
		//

		udp.setPayload(rip);
		ip.setPayload(udp);
		ether.setPayload(ip);

		return ether;
	}

	/*******************************************RIP END ******************************************/

	/*********************************************ICMP START *************************************/
	
	/**
	* this is the creating process, now in part 2 we assume that we have everything (ARP...)
	*/
	private void sendICMPPacket(int icmpType, int icmpCode, Ethernet etherPacket ,Iface Interface){
		Ethernet ether = new Ethernet();
		IPv4 ip4 = (IPv4)etherPacket.getPayload();
		// find the route table
		RouteEntry dest = this.getRouteTable().lookup(ip4.getSourceAddress());
		//MACAddress macfordst = dest.getInterface().getMacAddress();
		if (dest == null){ // drop
			//System.out.println("sendICMPPacekt: RouteEntry dest not found");
			return;
		}
		
		////////////////////////////Setting Ethernet Header/////////////////////

		// get the gateway
		int nextHop = dest.getGatewayAddress();
		if (nextHop == 0){
			nextHop = ip4.getSourceAddress();
		}

		ArpEntry arp = this.arpCache.lookup(nextHop);
		if (arp == null){
			// this is for the next arp protocol
			handleArpMiss(nextHop, etherPacket, Interface, Interface);
			//System.out.println("arp == null in sendingICMP");
			return;
		}
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setDestinationMACAddress(arp.getMac().toBytes());
		ether.setSourceMACAddress(Interface.getMacAddress().toBytes());

		//////////////////////////////Ethernet end//////////////////////////////

		/////////////////////////Setting ICMP//////////////////////////////////
		ICMP icmp = new ICMP();
		icmp.setIcmpType((byte)icmpType);
		icmp.setIcmpCode((byte)icmpCode);
		//////////////////////////ICMP end////////////////////////////////////

		///////////////////////////Setting IP header////////////////////////////
		IPv4 ip = new IPv4();
		ip.setTtl((byte)64);
		ip.setProtocol(IPv4.PROTOCOL_ICMP);
		ip.setDestinationAddress(ip4.getSourceAddress());
		////////////////////////////IP header end///////////////////////////////

		//////////////////////////Setting Data//////////////////////////////////
		byte[] tempData; 
		Data data = new Data();
		// check if the received data is reply!!!
		if (icmpType == 0 && icmpCode == 0){       /** ICMP message: ECHO_REPLY_MESSAGE **/
			ip.setSourceAddress(ip4.getDestinationAddress());
			ICMP ip4_ICMP_payload = (ICMP)ip4.getPayload();
			tempData = ip4_ICMP_payload.getPayload().serialize();
		} else {
			ip.setSourceAddress(Interface.getIpAddress());
			int iphLength = ip4.getHeaderLength()*4; // why 4? length is in int(4byte)
			byte[] tempIPH = ip4.serialize(); // for the whole ipv4 packet, including header and payload
			tempData = new byte[12 + iphLength]; // "4 + iphLength + 8"
			Arrays.fill(tempData, 0, 4, (byte)0);
			for (int i = 0; i < iphLength+8; i++){
				tempData[i+4] = tempIPH[i];
			}
			// fill the tempIPH;
		}
		////////////////////////Data end///////////////////////////////////////
		

		data.setData(tempData);
		icmp.setPayload(data);
		ether.setPayload(ip);
		ip.setPayload(icmp);
		//System.out.println("Sending ICMP Packet");
		this.sendPacket(ether,Interface);
	}
   /***********************************ICMP DONE *********************************************/
}

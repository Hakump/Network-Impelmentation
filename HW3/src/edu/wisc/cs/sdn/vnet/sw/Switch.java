package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.MACAddress;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device implements Runnable
{
	private ConcurrentHashMap<MACAddress, ITime> macLearning;
	// TODO: about the time
	// we can add a time to each entry and if we want to send sth to a specific addr
	// and check that if the time span is geater than 15 sec,
	// if is, delete the entry, set to 0, and boardcast
	private Thread checkTime;
	private final static long TIMEOUT = 15000;
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
		macLearning = new ConcurrentHashMap<MACAddress,ITime>();
		checkTime = new Thread(this);
		checkTime.start();
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
		MACAddress SRC = etherPacket.getSourceMAC();
		MACAddress DST = etherPacket.getDestinationMAC();
		ITime dst = macLearning.get(DST);
		if (dst == null) {
			for (Iface iface: interfaces.values()) {
				if(!iface.getName().equals(inIface.getName())) {
					this.sendPacket(etherPacket, iface);
				}
			}
		} else {
			this.sendPacket(etherPacket, dst.getFace());
		}

		if (macLearning.get(SRC) == null){
			macLearning.put(SRC, new ITime(inIface, System.currentTimeMillis()));
		} else {
			ITime tempEntry = macLearning.get(SRC);
			tempEntry.setTime(System.currentTimeMillis());
			tempEntry.setOutIface(inIface);
		}



		/********************************************************************/
		// decap the packet, figuring out the packet MAC addr,
		// and if sender's name is not registered, add it to the switch file
		// if the receiver's name is not on the file, boardcast to all interfaces


		/*
		 *  Note from Zidong:
		 *  [[[ Logic for handlePacket(Ethernet etherPacket, Iface inIface) ]]]
		 *  INITIALIZE:
		 *  	1.1: get etherPacket's src MACAddress
		 *  	1.2: get etherPacekt's dst MACAddress
		 *  	1.3: get "ITIme entry" by using dst MACAddress from 'macLearning'[our hashmap]
		 *  PROCESS:
		 * 		2.1: if 'macLearning' doesn't have 1.1
		 *      		-> put this thing as an entry into 'macLearning'
		 *  		 else
		 *  			-> update that entry in 'macLearning', reset the entry's updateTime(aka Timer)
		 *  	2.2: if 1.3 returns null
		 *  		 	-> check name
		 *  		 else if 1.3 returns something
		 *  		 	-> sendPacekt(etherPacket, entry.getFace)

		 */


	}

	@Override
	public void run(){
		try {
			while (true){
				if (macLearning != null) {
					for (Map.Entry<MACAddress, ITime> entry: macLearning.entrySet()) {
						// remove entries that have lived for more than 15 seconds
						for (Map.Entry<MACAddress,ITime> it: macLearning.entrySet()) {
							if (it.getValue().getUpdateTime() + TIMEOUT > System.currentTimeMillis()){
									macLearning.remove(it.getKey());
							}
						}
					}
				}
				Thread.sleep(300);
			}

		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	class ITime {
		private Iface face;
		private long updateTime;
		public ITime(Iface face, long updateTime){
			this.face = face;
			this.updateTime = updateTime;
		}
		public void setTime(long updateTime) {
			this.updateTime = updateTime;
		}
		public void setOutIface(Iface face) {
			this.face =  face;
		}
		public Iface getFace() {
			return this.face;
		}
		public long getUpdateTime() {
			return this.updateTime;
		}
	}
}

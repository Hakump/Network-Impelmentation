package edu.wisc.cs.sdn.apps.loadbalancer;

import java.nio.ByteBuffer;
import java.util.*;

import edu.wisc.cs.sdn.apps.util.ArpServer;
import edu.wisc.cs.sdn.apps.l3routing.L3Routing;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;
import org.openflow.protocol.*;

import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionSetField;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.openflow.protocol.instruction.OFInstructionGotoTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



import net.floodlightcontroller.packet.ARP;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.internal.DeviceManagerImpl;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.util.MACAddress;

public class LoadBalancer implements IFloodlightModule, IOFSwitchListener,
		IOFMessageListener
{
	public static final String MODULE_NAME = LoadBalancer.class.getSimpleName();
	
	private static final byte TCP_FLAG_SYN = 0x02;
	
	private static final short IDLE_TIMEOUT = 20;
	
	// Interface to the logging system
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);
    
    // Interface to Floodlight core for interacting with connected switches
    private IFloodlightProviderService floodlightProv;
    
    // Interface to device manager service
    private IDeviceService deviceProv;
    
    // Switch table in which rules should be installed
    private byte table;
    
    // Set of virtual IPs and the load balancer instances they correspond with
    private Map<Integer,LoadBalancerInstance> instances;

    /**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Initializing %s...", MODULE_NAME));
		
		// Obtain table number from config
		Map<String,String> config = context.getConfigParams(this);
        this.table = Byte.parseByte(config.get("table"));
        
        // Create instances from config
        this.instances = new HashMap<Integer,LoadBalancerInstance>();
        String[] instanceConfigs = config.get("instances").split(";");
        for (String instanceConfig : instanceConfigs)
        {
        	String[] configItems = instanceConfig.split(" ");
        	if (configItems.length != 3)
        	{ 
        		log.error("Ignoring bad instance config: " + instanceConfig);
        		continue;
        	}
        	LoadBalancerInstance instance = new LoadBalancerInstance(
        			configItems[0], configItems[1], configItems[2].split(","));
            this.instances.put(instance.getVirtualIP(), instance);
            log.info("Added load balancer instance: " + instance);
        }
        
		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);
        
        /*********************************************************************/
        /* TODO: Initialize other class variables, if necessary              */
        
        /*********************************************************************/
	}

	/**
     * Subscribes to events and performs other startup tasks.
     */
	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Starting %s...", MODULE_NAME));
		this.floodlightProv.addOFSwitchListener(this);
		this.floodlightProv.addOFMessageListener(OFType.PACKET_IN, this);
		
		/*********************************************************************/
		/* TODO: Perform other tasks, if necessary                           */
		
		/*********************************************************************/
	}
	
	/**
     * Event handler called when a switch joins the network.
     * @param DPID for the switch
     */
	@Override
	public void switchAdded(long switchId) 
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));
		
		/*********************************************************************/
		/* TODO: Install rules to send:                                      */
		/*       (1) packets from new connections to each virtual load       */
		/*       balancer IP to the controller                               */
		/*       (2) ARP packets to the controller, and                      */
		/*       (3) all other packets to the next rule table in the switch  */
		
		/*********************************************************************/

		for (int virtual_server: instances.keySet()) {
			OFMatch match_ip = new OFMatch();
			match_ip.setDataLayerType(OFMatch.ETH_TYPE_IPV4).setNetworkDestination(virtual_server).setNetworkProtocol(OFMatch.IP_PROTO_TCP);
			List<OFAction>  actions_ip = Arrays.asList((OFAction) new OFActionOutput(OFPort.OFPP_CONTROLLER));
			OFInstruction instruction_ip = new OFInstructionApplyActions(actions_ip);

			OFMatch match_arp = new OFMatch();
			match_arp.setDataLayerType(OFMatch.ETH_TYPE_ARP).setNetworkDestination(virtual_server);
			List<OFAction>  actions_arp = Arrays.asList((OFAction) new OFActionOutput(OFPort.OFPP_CONTROLLER));
			OFInstruction instruction_arp = new OFInstructionApplyActions(actions_arp);

			SwitchCommands.installRule(sw, table, (short) 1, match_ip, Arrays.asList(instruction_ip));
			SwitchCommands.installRule(sw, table, (short) 1, match_arp, Arrays.asList(instruction_arp));
		}

		OFMatch temp = new OFMatch();
		OFInstruction original = new OFInstructionGotoTable(L3Routing.table);
		SwitchCommands.installRule(sw, table, (short) 0, temp, Arrays.asList(original));

	}
	
	/**
	 * Handle incoming packets sent from switches.
	 * @param sw switch on which the packet was received
	 * @param msg message from the switch
	 * @param cntx the Floodlight context in which the message should be handled
	 * @return indication whether another module should also process the packet
	 */
	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) 
	{
		// We're only interested in packet-in messages
		if (msg.getType() != OFType.PACKET_IN)
		{ return Command.CONTINUE; }
		OFPacketIn pktIn = (OFPacketIn)msg;
		
		// Handle the packet
		Ethernet ethPkt = new Ethernet();
		ethPkt.deserialize(pktIn.getPacketData(), 0,
				pktIn.getPacketData().length);
		
		/*********************************************************************/
		/* TODO: Send an ARP reply for ARP requests for virtual IPs; for TCP */
		/*       SYNs sent to a virtual IP, select a host and install        */
		/*       connection-specific rules to rewrite IP and MAC addresses;  */
		/*       ignore all other packets                                    */
		
		/*********************************************************************/

		// todo: check the methods
		if (ethPkt.getEtherType() == Ethernet.TYPE_ARP){
			ARP arpPkt = (ARP) ethPkt.getPayload();
			int dstIp = ByteBuffer.wrap(arpPkt.getTargetProtocolAddress()).getInt(); // check if the method has...
			if (arpPkt.getOpCode() != ARP.OP_REQUEST){
				return Command.CONTINUE;
			}
			if (!instances.containsKey(dstIp)){
				return Command.CONTINUE;
			}

			/**
			 * if the arp is not request packet, nor to the virtual IPs, drop the packet
			 * else,
			 * construct a new arp reply
			 * 	- new etherpacket, set type,mac..., new arp, set type/code/virtual address, set arp as a payload
			 *  - send a packet
			 */

			LoadBalancerInstance instance = instances.get(dstIp);

			// https://floodlight.atlassian.net/wiki/spaces/floodlightcontroller/pages/9142281/How+to+Create+a+Packet+Out+Message
			Ethernet l2Pkt = new Ethernet();
			l2Pkt.setSourceMACAddress(instance.getVirtualMAC())
					.setDestinationMACAddress(ethPkt.getSourceMACAddress())
					.setEtherType(Ethernet.TYPE_ARP);
 
			//https://github.com/floodlight/floodlight/blob/master/src/main/java/net/floodlightcontroller/forwarding/Forwarding.java
			ARP l3_arp = new ARP();
			l3_arp.setHardwareType(ARP.HW_TYPE_ETHERNET)
					.setProtocolType(ARP.PROTO_TYPE_IP)
					.setHardwareAddressLength((byte) Ethernet.DATALAYER_ADDRESS_LENGTH)
					.setProtocolAddressLength((byte) 4)
					.setOpCode(ARP.OP_REPLY)
					.setSenderHardwareAddress(instance.getVirtualMAC())
					.setSenderProtocolAddress(dstIp)
					.setTargetHardwareAddress(arpPkt.getSenderHardwareAddress())
					.setTargetProtocolAddress(arpPkt.getSenderProtocolAddress());

			l2Pkt.setPayload(l3_arp);

			SwitchCommands.sendPacket(sw, (short)pktIn.getInPort(), l2Pkt);
			// https://floodlight.atlassian.net/wiki/spaces/floodlightcontroller/pages/1343634/How+to+Add+Services+to+a+Module?showComments=true&showCommentArea=true
			return Command.STOP; //todo: stop correct???
		} else if (ethPkt.getEtherType() == Ethernet.TYPE_IPv4){
			IPv4 ipPkt = (IPv4) ethPkt.getPayload();
			int dstIp = ipPkt .getDestinationAddress();
			if(!instances.containsKey(dstIp)){
				return Command.CONTINUE;
			}
			if (ipPkt.getFlags() != TCP_FLAG_SYN){ // todo: correct???
				return Command.CONTINUE;
			}
			TCP tcpPkt = (TCP) ipPkt.getPayload();
			LoadBalancerInstance instance = instances.get(dstIp);
			// build in function
			int host_ip = instance.getNextHostIP();
			System.out.println("Get next IP: " + host_ip);
			byte[] mac_addr = getHostMACAddress(host_ip);

			OFMatch match1 = new OFMatch();
			match1.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
			match1.setNetworkSource(ipPkt.getSourceAddress());
			match1.setNetworkDestination(ipPkt.getDestinationAddress());
			match1.setNetworkProtocol(OFMatch.IP_PROTO_TCP);
			match1.setTransportSource(tcpPkt.getSourcePort());
			match1.setTransportDestination(tcpPkt.getDestinationPort());

			OFInstructionApplyActions action1 = new OFInstructionApplyActions();
			List<OFAction> actions1 = Arrays.asList(
					(OFAction)new OFActionSetField(OFOXMFieldType.IPV4_DST, host_ip),
					(OFAction)new OFActionSetField(OFOXMFieldType.ETH_DST, mac_addr));
			action1.setActions(actions1);

			OFMatch match2 = new OFMatch();
			match2.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
			match2.setNetworkSource(host_ip); // todo: correct???
			match2.setNetworkDestination(ipPkt.getSourceAddress());
			match2.setNetworkProtocol(OFMatch.IP_PROTO_TCP);
			match2.setTransportSource(tcpPkt.getDestinationPort());
			match2.setTransportDestination(tcpPkt.getSourcePort());

			OFInstructionApplyActions action2 = new OFInstructionApplyActions();
			List<OFAction> actions2 = Arrays.asList(
				(OFAction) new OFActionSetField(OFOXMFieldType.ETH_SRC, ethPkt.getDestinationMACAddress()),
				(OFAction) new OFActionSetField(OFOXMFieldType.IPV4_SRC, ipPkt.getDestinationAddress()));
			action2.setActions(actions2);

			OFInstruction l3rules = new OFInstructionGotoTable(L3Routing.table);
			List<OFInstruction> redirectRules1 = Arrays.asList(action1, l3rules);
			List<OFInstruction> redirectRules2 = Arrays.asList(action2, l3rules);

			SwitchCommands.installRule(sw, table, (short) 3, match1, redirectRules1, SwitchCommands.NO_TIMEOUT, IDLE_TIMEOUT);
			SwitchCommands.installRule(sw, table, (short) 3, match2, redirectRules2, SwitchCommands.NO_TIMEOUT, IDLE_TIMEOUT);

			return Command.CONTINUE;
		}
		
		// We don't care about other packets
		return Command.CONTINUE;
	}
	
	/**
	 * Returns the MAC address for a host, given the host's IP address.
	 * @param hostIPAddress the host's IP address
	 * @return the hosts's MAC address, null if unknown
	 */
	private byte[] getHostMACAddress(int hostIPAddress)
	{
		Iterator<? extends IDevice> iterator = this.deviceProv.queryDevices(
				null, null, hostIPAddress, null, null);
		if (!iterator.hasNext())
		{ return null; }
		IDevice device = iterator.next();
		return MACAddress.valueOf(device.getMACAddress()).toBytes();
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId) 
	{ /* Nothing we need to do, since the switch is no longer active */ }

	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId)
	{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when a port on a switch goes up or down, or is
	 * added or removed.
	 * @param DPID for the switch
	 * @param port the port on the switch whose status changed
	 * @param type the type of status change (up, down, add, remove)
	 */
	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,
			PortChangeType type) 
	{ /* Nothing we need to do, since load balancer rules are port-agnostic */}

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId) 
	{ /* Nothing we need to do */ }
	
    /**
     * Tell the module system which services we provide.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() 
	{ return null; }

	/**
     * Tell the module system which services we implement.
     */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> 
			getServiceImpls() 
	{ return null; }

	/**
     * Tell the module system which modules we depend on.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> 
			getModuleDependencies() 
	{
		Collection<Class<? extends IFloodlightService >> floodlightService =
	            new ArrayList<Class<? extends IFloodlightService>>();
        floodlightService.add(IFloodlightProviderService.class);
        floodlightService.add(IDeviceService.class);
        return floodlightService;
	}

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName() 
	{ return MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) 
	{
		return (OFType.PACKET_IN == type 
				&& (name.equals(ArpServer.MODULE_NAME) 
					|| name.equals(DeviceManagerImpl.MODULE_NAME))); 
	}

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) 
	{ return false; }
}

package edu.wisc.cs.sdn.apps.l3routing;

import java.util.*;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import edu.wisc.cs.sdn.apps.util.SwitchCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;

import edu.wisc.cs.sdn.apps.util.Host;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceListener;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.routing.Link;


public class L3Routing implements IFloodlightModule, IOFSwitchListener,
		ILinkDiscoveryListener, IDeviceListener
{
	public static final String MODULE_NAME = L3Routing.class.getSimpleName();

	// Interface to the logging system
	private static Logger log = LoggerFactory.getLogger(MODULE_NAME);

	// Interface to Floodlight core for interacting with connected switches
	private IFloodlightProviderService floodlightProv;

	// Interface to link discovery service
	private ILinkDiscoveryService linkDiscProv;

	// Interface to device manager service
	private IDeviceService deviceProv;

	// Switch table in which rules should be installed
	public static byte table;

	// Map of hosts to devices
	private Map<IDevice,Host> knownHosts;

	/**
	 * Loads dependencies and initializes data structures.
	 */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException
	{
		log.info(String.format("Initializing %s...", MODULE_NAME));
		Map<String,String> config = context.getConfigParams(this);
		table = Byte.parseByte(config.get("table"));

		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
		this.linkDiscProv = context.getServiceImpl(ILinkDiscoveryService.class);
		this.deviceProv = context.getServiceImpl(IDeviceService.class);

		this.knownHosts = new ConcurrentHashMap<IDevice,Host>();
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
		this.linkDiscProv.addListener(this);
		this.deviceProv.addListener(this);

		/*********************************************************************/
		/* TODO: Initialize variables or perform startup tasks, if necessary */

		/*********************************************************************/
	}

	/**
	 * Get a list of all known hosts in the network.
	 */
	private Collection<Host> getHosts()
	{ return this.knownHosts.values(); }

	/**
	 * Get a map of all active switches in the network. Switch DPID is used as
	 * the key.
	 */
	private Map<Long, IOFSwitch> getSwitches()
	{ return floodlightProv.getAllSwitchMap(); }

	/**
	 * Get a list of all active links in the network.
	 */
	private Collection<Link> getLinks()
	{ return linkDiscProv.getLinks().keySet(); }

	/**
	 * Event handler called when a host joins the network.
	 * @param device information about the host
	 */
	@Override
	public void deviceAdded(IDevice device)
	{
		Host host = new Host(device, this.floodlightProv);
		// We only care about a new host if we know its IP
		if (host.getIPv4Address() != null)
		{
			log.info(String.format("Host %s added", host.getName()));
			this.knownHosts.put(device, host);

			/*****************************************************************/
			/* TODO: Update routing: add rules to route to new host          */

			/*****************************************************************/
			this.installHR(host);
		}
	}

	/**
	 * Event handler called when a host is no longer attached to a switch.
	 * @param device information about the host
	 */
	@Override
	public void deviceRemoved(IDevice device)
	{
		Host host = this.knownHosts.get(device);
		if (null == host)
		{ return; }
		this.knownHosts.remove(device);

		log.info(String.format("Host %s is no longer attached to a switch",
				host.getName()));

		/*********************************************************************/
		/* TODO: Update routing: remove rules to route to host               */

		/*********************************************************************/
		this.removeHR(host);
	}

	/**
	 * Event handler called when a host moves within the network.
	 * @param device information about the host
	 */
	@Override
	public void deviceMoved(IDevice device)
	{
		Host host = this.knownHosts.get(device);
		if (null == host)
		{
			host = new Host(device, this.floodlightProv);
			this.knownHosts.put(device, host);
		}

		if (!host.isAttachedToSwitch())
		{
			this.deviceRemoved(device);
			return;
		}
		log.info(String.format("Host %s moved to s%d:%d", host.getName(),
				host.getSwitch().getId(), host.getPort()));

		/*********************************************************************/
		/* TODO: Update routing: change rules to route to host               */

		/*********************************************************************/

		this.removeHR(host);
		this.installHR(host);

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
		/* TODO: Update routing: change routing rules for all hosts          */

		/*********************************************************************/
		for (Host host: this.getHosts()){
			this.removeHR(host);
			this.installHR(host);
		}
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId)
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d removed", switchId));

		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */

		/*********************************************************************/
		for (Host host: this.getHosts()){
			this.removeHR(host);
			this.installHR(host);
		}
	}

	/**
	 * Event handler called when multiple links go up or down.
	 * @param updateList information about the change in each link's state
	 */
	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList)
	{
		for (LDUpdate update : updateList)
		{
			// If we only know the switch & port for one end of the link, then
			// the link must be from a switch to a host
			if (0 == update.getDst())
			{
				log.info(String.format("Link s%s:%d -> host updated",
						update.getSrc(), update.getSrcPort()));
			}
			// Otherwise, the link is between two switches
			else
			{
				log.info(String.format("Link s%s:%d -> s%s:%d updated",
						update.getSrc(), update.getSrcPort(),
						update.getDst(), update.getDstPort()));
			}
		}

		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */

		/*********************************************************************/
		for (Host host: this.getHosts()){
			this.removeHR(host);
			this.installHR(host);
		}
	}

	/**
	 * Event handler called when link goes up or down.
	 * @param update information about the change in link state
	 */
	@Override
	public void linkDiscoveryUpdate(LDUpdate update)
	{ this.linkDiscoveryUpdate(Arrays.asList(update)); }

	/**
	 * Event handler called when the IP address of a host changes.
	 * @param device information about the host
	 */
	@Override
	public void deviceIPV4AddrChanged(IDevice device)
	{ this.deviceAdded(device); }

	/**
	 * Event handler called when the VLAN of a host changes.
	 * @param device information about the host
	 */
	@Override
	public void deviceVlanChanged(IDevice device)
	{ /* Nothing we need to do, since we're not using VLANs */ }

	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId)
	{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId)
	{ /* Nothing we need to do */ }

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
	{ /* Nothing we need to do, since we'll get a linkDiscoveryUpdate event */ }

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName()
	{ return this.MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(String type, String name)
	{ return false; }

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(String type, String name)
	{ return false; }

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
		floodlightService.add(ILinkDiscoveryService.class);
		floodlightService.add(IDeviceService.class);
		return floodlightService;
	}

	// Naive implementation with one to N dist

	/**
	 *
	 * @param currentSW
	 * @return
	 */
	private Map<Long, Integer> BFS(IOFSwitch currentSW){
		//Map<Long, Integer> distances = new ConcurrentHashMap<Long, Integer>();
		Map<Long, Integer> parentPort = new ConcurrentHashMap<Long, Integer>();
		Queue<Long> queue = new LinkedBlockingQueue<Long>();
		HashSet<Long> visited = new HashSet<Long>();

		//Map<Long, Integer> parentPort = new ConcurrentHashMap<>();
		long currentSW_ID = currentSW.getId();

		//parentPort.put(currentSW_ID, -1);

		// load all switches
		// init
		//distances.put(currentSW_ID, 0);
		queue.add(currentSW_ID);
		visited.add(currentSW_ID);

		// load all links
		List<Link> allEdges = this.getAllEdges();
		for (Link temp: allEdges
		) {
			System.out.println("------------------------------------");
			System.out.println(temp.getSrc() +" "+ temp.getDst() +"\n" + temp.getSrcPort() + " " + temp.getDstPort());
			System.out.println("------------------------------------");
		}
		// start
		while (!queue.isEmpty()){
			long curr_sw = queue.remove();
			System.out.println("SWID: "+curr_sw);
			List<Link> neighborhood = new LinkedList<Link>(); // links connect to curr_sw
			// add all links (one direction, but assuming bidirectional)
			for (Link temp: allEdges) { // todo: check if all link has added and are unique
				if(temp.getSrc() == curr_sw || temp.getDst() == curr_sw){
					neighborhood.add(temp);
				}
			}
			//int dist_to_curr = distances.get(curr_sw);
			for (Link neighbors: neighborhood) {
				long neighbor = neighbors.getSrc() == curr_sw ? neighbors.getDst() : neighbors.getSrc();
				if (visited.contains(neighbor)) {
					continue;
				}
				//System.out.println("Distinct Curr: " + curr_sw + " neighbor: " + neighbor);
//                if (curr_sw == currentSW_ID){ // parent is the source
//                    parentPort.put(neighbor, neighbors.getDst() == curr_sw ? neighbors.getDstPort() : neighbors.getSrcPort());
//                } else { // not the source, so we need to get curr_sw's parentPort number e.g. E(B) | B(A) C(A) => E(A)
//                    int parent = parentPort.get(curr_sw);
//                    parentPort.put(neighbor, parent);
//                }
				parentPort.put(neighbor, neighbors.getSrc() == curr_sw ? neighbors.getDstPort() : neighbors.getSrcPort());
				//distances.put(neighbor, dist_to_curr + 1);
				visited.add(neighbor); // stop other sw traverse after (lager or equal dist) from visiting it
				queue.add(neighbor); //todo: check if add() does add to last
			}
		}
		for (Map.Entry<Long, Integer> temp : parentPort.entrySet())
		{
			System.out.println("SW= " + temp.getKey() + " Port= " + temp.getValue());
		}

		return parentPort;
	}

	/**
	 * this method add all distinct links between switches, we must check if the scr and dst matches when using
	 *
	 * @return kist of edges in the topos
	 */
	private List<Link> getAllEdges(){
		List<Link> tempList = new ArrayList<Link>();
		//HashSet<Link> found = new HashSet<>();
		for (Link temp: this.getLinks()){
			boolean contain = false;
//            Iterator<Link> it = tempList.iterator();
//            while (it.hasNext()){
//                Link l = it.next();
//                if ( (l.getDst() == temp.getDst() && l.getSrc() == temp.getSrc())
//                        || (l.getDst() == temp.getSrc() && l.getDst() == temp.getSrc()) ){
//                    contain = true;
//                    break;
//                }
//            }
			for (Link l: tempList){
				if ( (l.getDst() == temp.getDst() && l.getSrc() == temp.getSrc())
						|| (l.getDst() == temp.getSrc() && l.getSrc() == temp.getDst()) ){
					contain = true;
					break;
				}
			}
			if(!contain){
				tempList.add(temp);
			}
		}
		System.out.println(tempList.size());
		return tempList;
	}

	/**
	 * this function removes rules in a host
	 * - case 1: device removed
	 * - case 2: device moved
	 * - case 3: switch(es) topo is changed
	 * @param removed the host that rules should be removed
	 */
	private void removeHR(Host removed){
		OFMatch match = new OFMatch();
		match.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
		match.setNetworkDestination(removed.getIPv4Address());

		for (IOFSwitch temp : this.getSwitches().values()) {
			SwitchCommands.removeRules(temp, table, match);
		}

	}

	private void installHR(Host host){
		if (host.isAttachedToSwitch()){
			Map<Long, Integer> parentRoutes = BFS(host.getSwitch());
			// check if target and assigned IP match
			OFMatch match = new OFMatch();
			match.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
			match.setNetworkDestination(host.getIPv4Address());

			for (Long targetId: parentRoutes.keySet()){
				OFAction action = new OFActionOutput(parentRoutes.get(targetId));
				//List<OFAction> actions = Arrays.asList(action);
				OFInstruction instruct = new OFInstructionApplyActions(Arrays.asList(action));
				//List<OFInstruction> instructions = Arrays.asList(instruct);
				SwitchCommands.installRule(this.getSwitches().get(targetId), table, SwitchCommands.DEFAULT_PRIORITY, match, Arrays.asList(instruct));
			}
			OFAction action_P = new OFActionOutput(host.getPort()); // get appropriate port number
			//List<OFAction> actions_P = Arrays.asList(action_P);
			OFInstruction instruct_P = new OFInstructionApplyActions(Arrays.asList(action_P));
			//List<OFInstruction> instructions_P = Arrays.asList(instruct_P);
			SwitchCommands.installRule(host.getSwitch(), table, SwitchCommands.DEFAULT_PRIORITY, match, Arrays.asList(instruct_P));
		}
	}   
	/*
		public static boolean installRule(IOFSwitch sw, byte table, short priority,
				OFMatch matchCriteria, List<OFInstruction> instructions)
		{
			return installRule(sw, table, priority, matchCriteria, instructions, 
					NO_TIMEOUT, NO_TIMEOUT);
		}
	*/

	// /**    
	// public static boolean removeRules(IOFSwitch sw, byte table,
	// OFMatch matchCriteria)

	// **/
	// private void removeHR(Host host){
	// 	for (IOFSwitch switch : this.getSwitches.values()){
	// 		OFMatch match = new OFMatch();
	// 		match.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
	// 		match.setNetworkDestination(host.getIPv4Address());			
	// 		SwitchCommands.removeRules(switch, this.table, match);
	// 	}
	// }

}



package edu.ucsf.rbvi.clusterMaker2.internal.algorithms.attributeClusterers.ChengChurch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cytoscape.group.CyGroup;
import org.cytoscape.model.CyNetwork;
import org.cytoscape.model.CyNode;
import org.cytoscape.model.CyRow;
import org.cytoscape.model.CyTable;
import org.cytoscape.model.CyTableFactory;
import org.cytoscape.model.CyTableManager;
import org.cytoscape.model.CyTableUtil;
import org.cytoscape.work.ContainsTunables;
import org.cytoscape.work.ProvidesTitle;
import org.cytoscape.work.TaskMonitor;
import org.cytoscape.work.Tunable;

import edu.ucsf.rbvi.clusterMaker2.internal.algorithms.FuzzyNodeCluster;
import edu.ucsf.rbvi.clusterMaker2.internal.algorithms.attributeClusterers.AbstractAttributeClusterer;
import edu.ucsf.rbvi.clusterMaker2.internal.api.ClusterManager;
import edu.ucsf.rbvi.clusterMaker2.internal.api.ClusterViz;
import edu.ucsf.rbvi.clusterMaker2.internal.api.CyMatrix;
import edu.ucsf.rbvi.clusterMaker2.internal.ui.BiclusterView;
import edu.ucsf.rbvi.clusterMaker2.internal.utils.ModelUtils;

public class ChengChurch extends AbstractAttributeClusterer {

	public static String SHORTNAME = "ccbicluster";
	public static String NAME = "Cheng & Church's  bi-cluster";
	public static String GROUP_ATTRIBUTE = SHORTNAME+"Group.SUID";
	
	CyTableManager tableManager = null;
	private CyTableFactory tableFactory = null;
	
	@Tunable(description="Network to cluster", context="nogui")
	public CyNetwork network = null;

	@ContainsTunables
	public ChengChurchContext context = null;
	
	public ChengChurch(ChengChurchContext context, ClusterManager clusterManager) {
		super(clusterManager);
		this.context = context;
		if (network == null)
			network = clusterManager.getNetwork();
		context.setNetwork(network);
		
		tableManager = clusterManager.getTableManager();
		tableFactory = clusterManager.getTableFactory();
	}

	public String getShortName() {return SHORTNAME;}

	@ProvidesTitle
	public String getName() {return NAME;}
	
	public ClusterViz getVisualizer() {
		return null;
	}
	
	public void run(TaskMonitor monitor){
		
		this.monitor = monitor;
		monitor.setTitle("Performing "+getName());
		List<String> nodeAttributeList = context.attributeList.getNodeAttributeList();
		String edgeAttribute = context.attributeList.getEdgeAttribute();
		
		clusterAttributeName = "CnC_Bicluster";
		
		if(network.getRow(network, CyNetwork.LOCAL_ATTRS).getTable().getColumn(ClusterManager.CLUSTER_ATTRIBUTE)==null){
			network.getRow(network, CyNetwork.LOCAL_ATTRS).getTable().createColumn(ClusterManager.CLUSTER_ATTRIBUTE, String.class, false);
		}
		network.getRow(network, CyNetwork.LOCAL_ATTRS).set(ClusterManager.CLUSTER_ATTRIBUTE, clusterAttributeName);
		
		if (nodeAttributeList == null && edgeAttribute == null) {
			monitor.showMessage(TaskMonitor.Level.ERROR, "Must select either one edge column or two or more node columns");
			return;
		}

		if (nodeAttributeList != null && nodeAttributeList.size() > 0 && edgeAttribute != null) {
			monitor.showMessage(TaskMonitor.Level.ERROR,"Can't have both node and edge columns selected");
			return;
		}

		if (context.selectedOnly && CyTableUtil.getNodesInState(network, CyNetwork.SELECTED, true).size() < 3) {
			monitor.showMessage(TaskMonitor.Level.ERROR,"Must have at least three nodes to cluster");
			return;
		}

		createGroups = context.createGroups;

		if (nodeAttributeList != null && nodeAttributeList.size() > 0) {
			// To make debugging easier, sort the attribute list
			Collections.sort(nodeAttributeList);
		}

		// Get our attributes we're going to use for the cluster
		String[] attributeArray;
		if (nodeAttributeList != null && nodeAttributeList.size() > 0) {
			attributeArray = new String[nodeAttributeList.size()];
			int i = 0;
			for (String attr: nodeAttributeList) { attributeArray[i++] = "node."+attr; }
		} else {
			attributeArray = new String[1];
			attributeArray[0] = "edge."+edgeAttribute;
		}

		monitor.setStatusMessage("Initializing");

		resetAttributes(network, SHORTNAME);
		
		
		// Create a new clusterer
		RunChengChurch algorithm = new RunChengChurch(network, attributeArray, monitor, context);

		String resultsString = "ChengChurch results:";

		// Cluster the nodes
		monitor.setStatusMessage("Clustering nodes");
		Integer[] rowOrder = algorithm.cluster(false);

		CyMatrix biclusterMatrix = algorithm.getBiclusterMatrix();

		createGroups(network, biclusterMatrix, context.nClusters, algorithm.getRowClustersArray(), SHORTNAME);
		updateAttributes(network, SHORTNAME, rowOrder, attributeArray, getAttributeList(), 
		                 algorithm.getBiclusterMatrix());

		// Build our attribute clustesrs
		List<String> arrayList = buildClusterHeaders(algorithm.getColClustersArray(), algorithm.getBiclusterMatrix(), true);
    ModelUtils.createAndSetLocal(network, network, ClusterManager.CLUSTER_ATTR_ATTRIBUTE,
                    arrayList, List.class, String.class);

		updateParams(network, context.getParams());
		
		// System.out.println(resultsString);
		if (context.showUI) {
			insertTasksAfterCurrentTask(new BiclusterView(clusterManager));
		}
	}
	
	List<String> buildClusterHeaders(int[] clusters, CyMatrix matrix, boolean transpose) {
		List<String> headers = new ArrayList<>();
		String[] labels;
		if (transpose) {
			labels = matrix.getColumnLabels();
		} else {
			labels = matrix.getRowLabels();
		}
		for (int i = 0; i < clusters.length; i++) {
			headers.add(labels[i]+"\t"+clusters[i]);
		}
		return headers;
	}
	
	protected void createBiclusterGroups(Map<Integer, List<Long>> clusterNodes){
		
		List<List<CyNode>> clusterList = new ArrayList<List<CyNode>>(); // List of node lists
		List<Long>groupList = new ArrayList<Long>(); // keep track of the groups we create
		createGroups = context.createGroups;
		attrList = new ArrayList<String>();
		
		for(Integer bicluster: clusterNodes.keySet()){
			String groupName = clusterAttributeName+"_"+bicluster;
			List<Long>suidList = clusterNodes.get(bicluster);
			List<CyNode>nodeList = new ArrayList<CyNode>();
			
			for(Long suid: suidList){
				CyNode node = network.getNode(suid);				
				attrList.add(network.getRow(node).get(CyNetwork.NAME, String.class)+"\t"+bicluster);
				nodeList.add(node);
			}
			
			if (createGroups) {
				CyGroup group = clusterManager.createGroup(network, groupName, nodeList, null, true);
				if (group != null)
					groupList.add(group.getGroupNode().getSUID());
			}			
		}
						
		// Adding a column per node by the clusterAttributeName, which will store a list of all the clusters to which the node belongs
				
		
		ModelUtils.createAndSetLocal(network, network, GROUP_ATTRIBUTE, groupList, List.class, Long.class);
		ModelUtils.createAndSetLocal(network, network, ClusterManager.CLUSTER_TYPE_ATTRIBUTE, 
		                             getShortName(), String.class, null);
		ModelUtils.createAndSetLocal(network, network, ClusterManager.CLUSTER_ATTRIBUTE, 
		                             clusterAttributeName, String.class, null);
		if (params != null)
			ModelUtils.createAndSetLocal(network, network, ClusterManager.CLUSTER_PARAMS_ATTRIBUTE, 
		                               params, List.class, String.class);
				
	}

	
	public void createBiclusterTable(Map<Integer, List<Long>> clusterNodes ,Map<Integer, List<String>> clusterAttrs){
		CyTable networkTable = network.getTable(CyNetwork.class, CyNetwork.LOCAL_ATTRS);
		CyTable BiClusterNodeTable = null;
		CyTable BiClusterAttrTable = null;
		final String nodeTableSUID = clusterAttributeName + "_NodeTable.SUID";
		final String attrTableSUID = clusterAttributeName + "_AttrTable.SUID";
				
		if(!CyTableUtil.getColumnNames(networkTable).contains(nodeTableSUID)){
			
			networkTable.createColumn(nodeTableSUID, Long.class, false);
		}

		// OK, the column exists -- now, does it have any data?
		Long BiClusterNodeTableSUID = network.getRow(network).get(nodeTableSUID, Long.class);
		if (BiClusterNodeTableSUID == null) {
			// No, set things up.
			BiClusterNodeTable = tableFactory.createTable(clusterAttributeName + "_NodeTable", 
			                                              "Node.SUID", Long.class, true, true);
			BiClusterNodeTable.createListColumn("Bicluster List", Integer.class, false);
			BiClusterNodeTableSUID = BiClusterNodeTable.getSUID();
		} else {
			BiClusterNodeTable = tableManager.getTable(BiClusterNodeTableSUID);
		}
		networkTable.getRow(network.getSUID()).set(nodeTableSUID, BiClusterNodeTableSUID);
		
		if(!CyTableUtil.getColumnNames(networkTable).contains(attrTableSUID)){
			
			network.getDefaultNetworkTable().createColumn(attrTableSUID, Long.class, false);
		}

		Long BiClusterAttrTableSUID = network.getRow(network).get(attrTableSUID, Long.class);
		if (BiClusterAttrTableSUID == null) {
			BiClusterAttrTable = tableFactory.createTable(clusterAttributeName + "_AttrTable", 
			                                              "BiCluster Number", Integer.class, true, true);
			BiClusterAttrTable.createListColumn("Bicluster Attribute List", String.class, false);
			BiClusterAttrTableSUID = BiClusterAttrTable.getSUID();
		} else {
			BiClusterAttrTable = tableManager.getTable(BiClusterAttrTableSUID);
		}
		networkTable.getRow(network.getSUID()).set(attrTableSUID, BiClusterAttrTableSUID);
				
		Map<Long,List<Integer>> biclusterList = new HashMap<Long,List<Integer>>();
		for(Integer clust : clusterNodes.keySet()){
			List<Long> temp = clusterNodes.get(clust);
			for(Long node : temp){
				if(biclusterList.containsKey(node)){
					biclusterList.get(node).add(clust);
				}
				else{
					List<Integer> newlist = new ArrayList<Integer>();
					newlist.add(clust);
					biclusterList.put(node, newlist);
				}
			}
		}
		
		CyRow TableRow;
		
		for(Long node:biclusterList.keySet()){
			TableRow = BiClusterNodeTable.getRow(node);
			TableRow.set("Bicluster List", biclusterList.get(node));			
		}
		
		for(Integer clust : clusterAttrs.keySet()){
			TableRow = BiClusterAttrTable.getRow(clust);
			TableRow.set("Bicluster Attribute List", clusterAttrs.get(clust));
		}
		
		tableManager.addTable(BiClusterNodeTable);
		tableManager.addTable(BiClusterAttrTable);
	}	
	
}

package ptatoolkit.zipper.flowgraph;

import ptatoolkit.Global;
import ptatoolkit.doop.basic.DoopInstanceMethod;
import ptatoolkit.pta.basic.InstanceMethod;
import ptatoolkit.pta.basic.Method;
import ptatoolkit.pta.basic.Obj;
import ptatoolkit.pta.basic.Type;
import ptatoolkit.pta.basic.Variable;
import ptatoolkit.util.Pair;
import ptatoolkit.util.graph.DirectedGraphImpl;
import ptatoolkit.util.graph.Reachability;
import ptatoolkit.zipper.analysis.ObjectAllocationGraph;
import ptatoolkit.zipper.analysis.PotentialContextElement;
import ptatoolkit.zipper.pta.PointsToAnalysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

import static ptatoolkit.util.ANSIColor.BLUE;
import static ptatoolkit.util.ANSIColor.GREEN;
import static ptatoolkit.util.ANSIColor.RED;
import static ptatoolkit.util.ANSIColor.color;
import static ptatoolkit.util.NumberableComparators.pairComparator;

public class FlowAnalysis {

    private final PointsToAnalysis pta;
    private final ObjectAllocationGraph oag;
    private final PotentialContextElement pce;
    private final ObjectFlowGraph objectFlowGraph;

    private Type currentType;
    private Set<Variable> inVars;
    private Set<Node> outNodes;
    private Set<Node> visitedNodes;
    private Map<Node, Set<Edge>> wuEdges;
    private DirectedGraphImpl<Node> pollutionFlowGraph;
    private Reachability<Node> reachability;

    public FlowAnalysis(PointsToAnalysis pta,
                        ObjectAllocationGraph oag,
                        PotentialContextElement pce,
                        ObjectFlowGraph ofg) {
        this.pta = pta;
        this.oag = oag;
        this.pce = pce;
        this.objectFlowGraph = ofg;
    }

    public void initialize(Type type, Set<Method> inms, Set<Method> outms) {
        currentType = type;
        inVars = inms.stream()
                .map(Method::getParameters)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
        outNodes = outms.stream()
                .map(Method::getRetVars)
                .flatMap(Collection::stream)
                .map(objectFlowGraph::nodeOf)
                .filter(n -> n != null)
                .collect(Collectors.toSet());
        visitedNodes = new HashSet<>();
        wuEdges = new HashMap<>();
        pollutionFlowGraph = new DirectedGraphImpl<>();
        reachability = new Reachability<>(pollutionFlowGraph);
    }

    public void analyze(Method startMethod) {
        if (startMethod instanceof InstanceMethod) {
            Node node = objectFlowGraph.nodeOf(((InstanceMethod) startMethod).getThis());
            assert node != null: ((DoopInstanceMethod) startMethod);
            queue.add(node);
        }
        for (Variable param : startMethod.getParameters()) {
            Node node = objectFlowGraph.nodeOf(param);
            if (node != null) {
                queue.add(node);
            } else {
                if (Global.isDebug()) {
                    System.out.println(param + " is absent in the flow graph.");
                }
            }
        }
        bfs();

        if (Global.isDebug()) {
            Set<Method> outMethods = new HashSet<>();
            for (Variable param: startMethod.getParameters()) {
                Node node = objectFlowGraph.nodeOf(param);
                if (node != null) {
                    for (Node outNode : outNodes) {
                        if (reachability.reachableNodesFrom(node).contains(outNode)) {
                            VarNode outVarNode = (VarNode) outNode;
                            outMethods.add(pta.declaringMethodOf(outVarNode.getVar()));
                        }
                    }
                }
            }
            System.out.println(color(GREEN, "In method: ") + startMethod);
            System.out.println(color(GREEN, "Out methods: ") + outMethods);
        }
    }

    public Set<Node> getFlowNodes() {
        Set<Node> results = new HashSet<>();
        for (Node outNode : outNodes) {
            if (pollutionFlowGraph.allNodes().contains(outNode)) {
                results.addAll(reachability.nodesReach(outNode));
            }
        }
        return results;
    }

    public int numberOfPFGNodes() {
        return pollutionFlowGraph.allNodes().size();
    }

    public int numberOfPFGEdges() {
        int nrEdges = 0;
        for (Node node : pollutionFlowGraph.allNodes()) {
            nrEdges += pollutionFlowGraph.succsOf(node).size();
        }
        return nrEdges;
    }

    public void clear() {
        currentType = null;
        inVars = null;
        outNodes = null;
        visitedNodes = null;
        wuEdges = null;
        pollutionFlowGraph = null;
        reachability = null;
    }

    private Queue<Node> queue = new LinkedList<>();

    private void bfs() {
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            visit(n);
        }
    }

    public static final Set<Pair<Variable, Variable>> senLocalAssigns = new ConcurrentSkipListSet<>(pairComparator); // (to, from)

    private void visit(Node node) {
        if (Global.isDebug()) {
            System.out.println(color(BLUE, "Node ") + node);
        }
        if (visitedNodes.contains(node)) { // node has been visited
            if (Global.isDebug()) {
                System.out.println(color(RED, "Visited node: ") + node);
            }
        } else {
            visitedNodes.add(node);
            pollutionFlowGraph.addNode(node);
            // add unwrapped flow edges
            if (Global.isEnableUnwrappedFlow()) {
                if (node instanceof VarNode) {
                    VarNode varNode = (VarNode) node;
                    Variable var = varNode.getVar();
                    // Optimization: approximate unwrapped flows to make
                    // Zipper and pointer analysis run faster
                    pta.returnToVariablesOf(var).forEach(toVar -> {
                        Node toNode = objectFlowGraph.nodeOf(toVar);
                        if (outNodes.contains(toNode)) {
                            for (Variable inVar : inVars) {
                                if (!Collections.disjoint(pta.pointsToSetOf(inVar),
                                        pta.pointsToSetOf(var))) {
                                    Edge unwrappedEdge =
                                            new Edge(Kind.UNWRAPPED_FLOW, node, toNode);
                                    addWUEdge(node, unwrappedEdge);
                                    break;
                                }
                            }
                        }
                    });
                    Set<Variable> s = pta.getInstanceLoadFromTo().get(var);
                    if (s != null) {
                        s.stream().forEach(v -> {
                            Node n = objectFlowGraph.nodeOf(v);
                            if (n != null) {
                                Edge unwrappedEdge =
                                        new Edge(Kind.UNWRAPPED_FLOW, node, n);
                                addWUEdge(node, unwrappedEdge);
                            }
                        });
                    }
                    s = pta.getArrayLoadFromTo().get(var);
                    if (s != null) {
                        s.stream().forEach(v -> {
                            Node n = objectFlowGraph.nodeOf(v);
                            if (n != null) {
                                Edge unwrappedEdge =
                                        new Edge(Kind.UNWRAPPED_FLOW, node, n);
                                addWUEdge(node, unwrappedEdge);
                            }
                        });
                    }
                    assert pta.getInstanceLoadFromTo().get(var) == null || pta.getArrayLoadFromTo().get(var) == null;
                }
            }
            List<Edge> nextEdges = new ArrayList<>();
            for (Edge edge : outEdgesOf(node)) {
                switch (edge.getKind()) {
                    case LOCAL_ASSIGN: {
                        Pair<Variable, Variable> p = new Pair<>(((VarNode)(edge.getTarget())).getVar(), ((VarNode)(edge.getSource())).getVar());
                        senLocalAssigns.add(p);
                        nextEdges.add(edge);
                    }
                    break;
                    case UNWRAPPED_FLOW: {
                        nextEdges.add(edge);
                    }
                    break;
                    case INTERPROCEDURAL_ASSIGN:
                    case INSTANCE_LOAD:
                    case WRAPPED_FLOW: {
                        // next must be a variable
                        VarNode next = (VarNode) edge.getTarget();
                        Variable var = next.getVar();
                        Method inMethod = pta.declaringMethodOf(var);
                        // Optimization: filter out some potential spurious flows due to
                        // the imprecision of context-insensitive pre-analysis, which
                        // helps improve the performance of Zipper and pointer analysis.
                        if (pce.PCEMethodsOf(currentType).contains(inMethod)) {
                            nextEdges.add(edge);
                        }
                    }
                    break;
                    case INSTANCE_STORE: {
                        InstanceFieldNode next = (InstanceFieldNode) edge.getTarget();
                        Obj base = next.getBase();
                        if (Global.isEnableWrappedFlow()) {
                            pta.methodsInvokedOn(currentType).stream()
                                    .map(m -> ((InstanceMethod) m).getThis())
                                    .map(objectFlowGraph::nodeOf)
                                    .filter(n -> n != null) // filter this variable of native methods
                                    .map(n -> new Edge(Kind.WRAPPED_FLOW, next, n))
                                    .forEach(e -> addWUEdge(next, e));
                            Node assigned = objectFlowGraph.nodeOf(pta.assignedVarOf(base));
                            if (assigned != null) {
                                Edge e = new Edge(Kind.WRAPPED_FLOW, next, assigned);
                                addWUEdge(next, e);
                            }
                        }
                            nextEdges.add(edge);
                    }
                    break;
                    default: {
                        throw new RuntimeException("Unknown edge: " + edge);
                    }
                }
            }
            for (Edge nextEdge : nextEdges) {
                Node nextNode = nextEdge.getTarget();
                pollutionFlowGraph.addEdge(node, nextNode);
                queue.add(nextNode);
            }
        }
    }

    private void addWUEdge(Node sourceNode, Edge edge) {
        if (!wuEdges.containsKey(sourceNode)) {
            wuEdges.put(sourceNode, new HashSet<>());
        }
        wuEdges.get(sourceNode).add(edge);
    }

    /**
     *
     * @param node
     * @return out edges of node from OFG, and wuEdges, if present
     */
    private Set<Edge> outEdgesOf(Node node) {
        Set<Edge> outEdges = objectFlowGraph.outEdgesOf(node);
        if (wuEdges.containsKey(node)) {
            outEdges = new HashSet<>(outEdges);
            outEdges.addAll(wuEdges.get(node));
        }
        return outEdges;
    }

    private void outputPollutionFlowGraphSize() {
        int nrNodes = pollutionFlowGraph.allNodes().size();
        int nrEdges = 0;
        for (Node node : pollutionFlowGraph.allNodes()) {
            nrEdges += pollutionFlowGraph.succsOf(node).size();
        }
        System.out.printf("#Size of PFG of %s: %d nodes, %d edges.\n",
                currentType, nrNodes, nrEdges);
    }

    public DirectedGraphImpl<Node> getPollutionFlowGraph() {
        return pollutionFlowGraph;
    }
}

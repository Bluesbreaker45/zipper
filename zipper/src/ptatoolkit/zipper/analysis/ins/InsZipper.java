package ptatoolkit.zipper.analysis.ins;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ptatoolkit.Global;
import ptatoolkit.Options;
import ptatoolkit.Global.NewStrategy;
import ptatoolkit.pta.basic.Field;
import ptatoolkit.pta.basic.Method;
import ptatoolkit.pta.basic.Obj;
import ptatoolkit.pta.basic.Variable;
import ptatoolkit.util.Pair;
import ptatoolkit.util.Triple;
import ptatoolkit.util.graph.DirectedGraphImpl;
import ptatoolkit.zipper.doop.Attribute;
import ptatoolkit.zipper.flowgraph.InstanceFieldNode;
import ptatoolkit.zipper.flowgraph.Node;
import ptatoolkit.zipper.flowgraph.VarNode;
import ptatoolkit.zipper.pta.PointsToAnalysis;

import static ptatoolkit.util.NumberableComparators.pairComparator;
import static ptatoolkit.util.NumberableComparators.tripleComparator;

public class InsZipper {

    private final NewStrategy newStrategy;
    private final Set<NewStrategy> implemented = Stream.of(NewStrategy.PFG).collect(Collectors.toSet());

    private final PointsToAnalysis pta;
    private final Set<Pair<Variable, Variable>> localAssigns; // (to, from)
    private final Set<Pair<Variable, Variable>> casts; // (to, from)
    private final Map<Triple<Variable, Obj, Field>, Set<Variable>> instanceLoadCause; // (to, baseValue, sig, base)
    private final Map<Pair<Variable, Obj>, Set<Variable>> arrayLoadCause; // (to, baseValue, base)
    
    private final Field ARR_FIELD;

    private final Set<Pair<Variable, Obj>> senHeapAllocation = new ConcurrentSkipListSet<>(pairComparator); // (to, obj)
    private final Set<Pair<Variable, Variable>> senLocalAssigns = new ConcurrentSkipListSet<>(pairComparator); // (to, from)
    private final Set<Pair<Variable, Variable>> senCasts = new ConcurrentSkipListSet<>(pairComparator); // (to, from)
    private final Set<Triple<Variable, Variable, Field>> senInstanceLoad = new ConcurrentSkipListSet<>(tripleComparator); // (to, base, field)
    private final Set<Pair<Variable, Variable>> senArrayLoad = new ConcurrentSkipListSet<>(pairComparator); // (to, base)

    public InsZipper(PointsToAnalysis pta) {
        this.pta = pta;
        this.newStrategy = Global.getHeapAllocationStrategy();
        if (!implemented.contains(this.newStrategy)) {
            throw new IllegalArgumentException("The input option for HeapAllocationStrategy is not supported.");
        }
        this.localAssigns = new HashSet<>();
        pta.localAssignIterator().forEachRemaining(localAssigns::add);
        this.casts = pta.getCasts();
        this.instanceLoadCause =  pta.getInstanceLoadCause();
        this.arrayLoadCause = pta.getArrayLoadCause();
        this.ARR_FIELD = pta.getArrayIndexRep();
    }
    
    public void analyze(Set<Node> flowNodes, DirectedGraphImpl<Node> pfg) {
        for (Node succ : flowNodes) {
            if (succ instanceof InstanceFieldNode) {
                /*
                 * If the successor is an instanceFieldNode, then its predecessors must be a varNode,
                 * it is caused by a store instruction, and we always analyze it sensitively.
                 * The wrapped flow would be processed by the rest code.
                 */
                assert pfg.predsOf(succ).stream().allMatch(n -> n instanceof VarNode);
                continue;
            }
            Variable to = ((VarNode) succ).getVar();
            for (Node pred : pfg.predsOf(succ)) {
                if (pred instanceof VarNode) {
                    Variable from = ((VarNode) pred).getVar();
                    Pair<Variable, Variable> p = new Pair<>(to, from);
                    if (casts.contains(p)) {
                        senCasts.add(p);
                    } else if (localAssigns.contains(p)) {
                        senLocalAssigns.add(p);
                    } else {
                        // some edges like method call
                    }
                } else {
                    Obj baseObj = ((InstanceFieldNode) pred).getBase();
                    Field field = ((InstanceFieldNode) pred).getField();
                    if (field.equals(ARR_FIELD)) {
                        Pair<Variable, Obj> p = new Pair<>(to, baseObj);
                        Set<Variable> s = arrayLoadCause.get(p);
                        if (s == null) {
                            // seems to be wrapped flow
                            if (!to.toString().contains("@this")) {
                                assert baseObj.getAttribute(Attribute.OBJECT_ASSIGNED) == to;
                                if (newStrategy == NewStrategy.PFG) {
                                    senHeapAllocation.add(p);
                                } else {
                                    throw new RuntimeException("Unimplemented heap allocation strategy.");
                                }
                            }
                        } else {
                            s.forEach(base -> senArrayLoad.add(new Pair<>(to, base)));
                        }
                    } else {
                        Triple<Variable, Obj, Field> t = new Triple<>(to, baseObj, field);
                        Set<Variable> s = instanceLoadCause.get(t);
                        if (s == null) {
                            if (!to.toString().contains("@this")) {
                                assert baseObj.getAttribute(Attribute.OBJECT_ASSIGNED) == to;
                                if (newStrategy == NewStrategy.PFG) {
                                    senHeapAllocation.add(new Pair<>(to, baseObj));
                                } else {
                                    throw new RuntimeException("Unimplemented heap allocation strategy.");
                                }
                            } else {
                                // System.out.println("INSTANCE CAUSE NOT FOUND: " + t);
                            }
                        } else {
                            s.forEach(base -> senInstanceLoad.add(new Triple<>(to, base, field)));
                        }
                    }
                }
            }
        }
    }

    public void printStatistics() {
        System.out.println("#LocalAssign(SEN/ALL): " + senLocalAssigns.size() + " / " + (localAssigns.size() - casts.size()));
        System.out.println("#Cast(SEN/ALL): " + senCasts.size() + " / " + casts.size());
        System.out.println("#HeapAllocation(SEN/ALL): " + senHeapAllocation.size() + " / " + pta.allObjects().size());
        Set<Triple<Variable, Variable, Field>> instanceLoadInsn = new HashSet<>();
        instanceLoadCause.forEach((k, v) -> v.forEach(from -> instanceLoadInsn.add(new Triple<>(k.getFirst(), from, k.getThird()))));
        System.out.println("#InstanceLoad(SEN/ALL): " + senInstanceLoad.size() + " / " + instanceLoadInsn.size());
        Set<Pair<Variable, Variable>> arrayLoadInsn = new HashSet<>();
        arrayLoadCause.forEach((k, v) -> v.forEach(from -> arrayLoadInsn.add(new Pair<>(k.getFirst(), from))));
        System.out.println("#ArrayLoad(SEN/ALL): " + senArrayLoad.size() + " / " + arrayLoadInsn.size());
    }

    public void outputAllResults(Options opt, Set<Method> pcm) throws FileNotFoundException {
        String SEP = "\t";

        // change (to, from) to (from, to) in order to adapt to the ins-level analysis
        List<String> senHeapAllocationList = senHeapAllocation.stream()
            .map(p -> new Pair<>(p.getSecond(), p.getFirst())) // (to, from) -> (from, to)
            .sorted(pairComparator)
            .map(p -> p.getFirst() + SEP + p.getSecond())
            .collect(Collectors.toList());
        List<String> senLocalAssignsList = senLocalAssigns.stream()
            .map(p -> new Pair<>(p.getSecond(), p.getFirst())) // (to, from) -> (from, to)
            .sorted(pairComparator)
            .map(p -> p.getFirst() + SEP + p.getSecond())
            .collect(Collectors.toList());
        List<String> senCastsList = senCasts.stream()
            .map(p -> new Pair<>(p.getSecond(), p.getFirst())) // (to, from) -> (from, to)
            .sorted(pairComparator)
            .map(p -> p.getFirst() + SEP + p.getSecond())
            .collect(Collectors.toList());
        List<String> senInstanceLoadList = senInstanceLoad.stream()
            .sorted(tripleComparator)
            .map(t -> t.getFirst() + SEP + t.getSecond() + SEP + t.getThird())
            .collect(Collectors.toList());
        List<String> senArrayLoadList = senArrayLoad.stream()
            .sorted(pairComparator)
            .map(p -> p.getFirst() + SEP + p.getSecond())
            .collect(Collectors.toList());
        
        printStatistics();
    
        outputResults(senHeapAllocationList, "InsAssignHeapAllocation", opt);
        outputResults(senLocalAssignsList, "InsAssignLocal", opt);
        outputResults(senCastsList, "InsAssignCast", opt);
        outputResults(senInstanceLoadList, "InsLoadInstanceField", opt);
        outputResults(senArrayLoadList, "InsLoadArrayIndex", opt);
    }

    private <T> void outputResults(List<String> results, String factName, Options opt)
            throws FileNotFoundException {
        File outputFile = new File(opt.getOutPath(),
                String.format("%s-%s.facts", opt.getApp(), factName));
        System.out.printf("Writing Zipper-ins %s (sensitive instructions) to %s ...\n",
                factName, outputFile.getPath());
        System.out.println();

        char EOL = '\n';
        PrintWriter writer = new PrintWriter(outputFile);
        results.stream()
                .forEach(line -> {
                    writer.write(line);
                    writer.write(EOL);
        });
        writer.close();
    }



    public void reset() {
        senHeapAllocation.clear();
        senLocalAssigns.clear();
        senCasts.clear();
        senInstanceLoad.clear();
        senArrayLoad.clear();
    }
}

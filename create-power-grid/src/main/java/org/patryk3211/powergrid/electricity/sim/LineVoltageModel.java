package org.patryk3211.powergrid.electricity.sim;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.ProvidedVoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.node.TransformerCoupling;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.SeriesWire;
import org.patryk3211.powergrid.electricity.sim.special.SplitTransformerControllerWire;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLine;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLinePart;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLinePort;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side line model. Conductors share a voltage. Sources on the same bus
 * add their voltages and their amp budgets. Series parts drop volts by the amps
 * actually leaving through them. A positive terminal tied to its negative
 * terminal through a conductor is a short. Nothing explodes.
 */
final class LineVoltageModel {
    static final double CONDUCTOR_OHMS = 0.5;
    private static final int RELAXATION_PASSES = 32;

    private LineVoltageModel() {
    }

    static void solve(ElectricalNetwork network) {
        for(var coupling : network.couplings) {
            if(coupling instanceof ProvidedVoltageSourceCoupling provided)
                provided.updateFixedVoltage();
        }

        var parent = new Reference2ObjectOpenHashMap<IElectricNode, IElectricNode>();
        for(var node : network.nodes) {
            if(node instanceof IElectricNode electric)
                parent.put(electric, electric);
        }
        for(var node : network.leafNodes.keySet())
            parent.putIfAbsent(node, node);
        for(var wire : network.wires)
            collectNodes(wire, parent);
        for(var coupling : network.couplings) {
            for(var node : coupling.coupledNodes()) {
                if(node != null)
                    parent.putIfAbsent(node, node);
            }
        }

        var bridges = new ArrayList<Bridge>();
        var edges = new ArrayList<Edge>();
        var loads = new ArrayList<FixedVoltageLoadWire>();
        for(var wire : network.wires)
            classify(wire, parent, bridges, edges, loads);

        var buses = new IdentityHashMap<IElectricNode, Bus>();
        for(var node : parent.keySet()) {
            var root = find(parent, node);
            buses.computeIfAbsent(root, key -> new Bus());
        }

        var anyShort = false;
        for(var coupling : network.couplings) {
            if(!(coupling instanceof VoltageSourceCoupling source) || source instanceof TransmissionLinePort)
                continue;
            source.setSuppliedAmps(0);
            var voltage = source.getVoltage();
            if(!Double.isFinite(voltage) || voltage == 0)
                continue;
            var hot = voltage > 0 ? source.getPositive() : source.getNegative();
            var cold = voltage > 0 ? source.getNegative() : source.getPositive();
            if(hot == null || !parent.containsKey(hot))
                continue;
            var hotBus = buses.get(find(parent, hot));
            var coldBus = cold != null && parent.containsKey(cold) ? buses.get(find(parent, cold)) : null;
            if(coldBus == hotBus) {
                hotBus.shorted = true;
                anyShort = true;
                continue;
            }
            hotBus.candidates.add(Math.abs(voltage));
            hotBus.feeds.add(source);
            hotBus.addAmps(source.getAmpCapacity());
            if(coldBus != null)
                coldBus.grounded = true;
        }

        for(var bus : buses.values()) {
            if(bus.shorted) {
                bus.voltage = 0;
                bus.fixed = true;
                bus.ampBudget = 0;
                bus.scale = 0;
                continue;
            }
            if(!bus.candidates.isEmpty()) {
                var stacked = 0.0;
                for(var candidate : bus.candidates)
                    stacked += candidate;
                bus.voltage = stacked;
                bus.fixed = true;
                bus.sourced = true;
            }
        }
        for(var bus : buses.values()) {
            if(!bus.fixed && bus.grounded) {
                bus.voltage = 0;
                bus.fixed = true;
            }
        }

        for(int pass = 0; pass < 8; ++pass) {
            var moved = false;
            for(var bridge : bridges) {
                if(!parent.containsKey(bridge.from()) || !parent.containsKey(bridge.to()))
                    continue;
                var from = buses.get(find(parent, bridge.from()));
                var to = buses.get(find(parent, bridge.to()));
                if(from.fixed && !to.fixed && !to.shorted) {
                    to.voltage = from.voltage;
                    to.fixed = true;
                    to.ampBudget = from.ampBudget;
                    moved = true;
                }
            }
            if(!moved)
                break;
        }

        for(var coupling : network.couplings) {
            if(coupling instanceof TransformerCoupling transformer)
                applyTransformer(transformer, parent, buses);
        }
        for(var wire : network.wires) {
            if(wire instanceof SplitTransformerControllerWire split)
                applySplit(split, parent, buses);
        }

        var adjacency = new IdentityHashMap<Bus, List<Link>>();
        for(var edge : edges) {
            if(!parent.containsKey(edge.a()) || !parent.containsKey(edge.b()))
                continue;
            var busA = buses.get(find(parent, edge.a()));
            var busB = buses.get(find(parent, edge.b()));
            if(busA == busB || edge.resistance() <= 0 || !Double.isFinite(edge.resistance()))
                continue;
            adjacency.computeIfAbsent(busA, key -> new ArrayList<>()).add(new Link(busB, edge.resistance()));
            adjacency.computeIfAbsent(busB, key -> new ArrayList<>()).add(new Link(busA, edge.resistance()));
        }

        var queue = new ArrayDeque<Bus>();
        for(var bus : buses.values()) {
            if(!bus.sourced || bus.shorted)
                continue;
            bus.feeder = bus;
            queue.add(bus);
        }
        while(!queue.isEmpty()) {
            var bus = queue.removeFirst();
            for(var link : adjacency.getOrDefault(bus, List.of())) {
                var next = link.other();
                if(next.shorted || next.sourced || next.feeder != null)
                    continue;
                next.feeder = bus.feeder;
                queue.add(next);
            }
        }

        for(int pass = 0; pass < RELAXATION_PASSES; ++pass) {
            for(var bus : buses.values())
                bus.demand = 0;
            var injection = new IdentityHashMap<Bus, Double>();
            for(var load : loads) {
                if(!load.isEnabled())
                    continue;
                var node1 = load.getNode1();
                var node2 = load.getNode2();
                if(node1 == null || node2 == null || !parent.containsKey(node1) || !parent.containsKey(node2))
                    continue;
                var bus1 = buses.get(find(parent, node1));
                var bus2 = buses.get(find(parent, node2));
                if(bus1.shorted || bus2.shorted)
                    continue;
                var volts = bus1.voltage - bus2.voltage;
                var required = load.requiredVoltage();
                var fraction = required <= 0 ? 0 : Math.min(1, Math.abs(volts) / required);
                var want = load.ratedAmps() * fraction;
                if(want <= 0 || volts == 0)
                    continue;
                var feeder = feederOf(bus1, bus2);
                if(feeder == null)
                    continue;
                feeder.demand += want;
                var sign = Math.signum(volts);
                injection.merge(bus1, sign * want, Double::sum);
                injection.merge(bus2, -sign * want, Double::sum);
            }
            for(var edge : edges) {
                if(!parent.containsKey(edge.a()) || !parent.containsKey(edge.b()))
                    continue;
                var busA = buses.get(find(parent, edge.a()));
                var busB = buses.get(find(parent, edge.b()));
                if(!busA.fixed || !busB.fixed || busA == busB)
                    continue;
                var amps = Math.abs(busA.voltage - busB.voltage) / edge.resistance();
                if(amps <= 0 || !Double.isFinite(amps))
                    continue;
                var feeder = Math.abs(busA.voltage) >= Math.abs(busB.voltage) ? feederOf(busA, busB) : feederOf(busB, busA);
                if(feeder != null)
                    feeder.demand += amps;
            }
            for(var bus : buses.values()) {
                if(!bus.sourced)
                    continue;
                if(bus.shorted || bus.ampBudget <= 0 && bus.demand > 0)
                    bus.scale = 0;
                else if(!Double.isFinite(bus.ampBudget) || bus.demand <= bus.ampBudget)
                    bus.scale = 1;
                else
                    bus.scale = bus.ampBudget / bus.demand;
            }

            var nextVoltage = new IdentityHashMap<Bus, Double>();
            for(var bus : buses.values()) {
                if(bus.fixed)
                    continue;
                var links = adjacency.getOrDefault(bus, List.of());
                var sumG = 0.0;
                var sumGV = 0.0;
                var low = Double.POSITIVE_INFINITY;
                var high = Double.NEGATIVE_INFINITY;
                for(var link : links) {
                    var conductance = 1.0 / link.resistance();
                    sumG += conductance;
                    sumGV += conductance * link.other().voltage;
                    low = Math.min(low, link.other().voltage);
                    high = Math.max(high, link.other().voltage);
                }
                for(var load : loads) {
                    if(!load.isEnabled() || load.getNode1() == null || load.getNode2() == null)
                        continue;
                    if(!parent.containsKey(load.getNode1()) || !parent.containsKey(load.getNode2()))
                        continue;
                    var loadA = buses.get(find(parent, load.getNode1()));
                    var loadB = buses.get(find(parent, load.getNode2()));
                    if(loadA == bus) {
                        low = Math.min(low, loadB.voltage);
                        high = Math.max(high, loadB.voltage);
                    } else if(loadB == bus) {
                        low = Math.min(low, loadA.voltage);
                        high = Math.max(high, loadA.voltage);
                    }
                }
                if(sumG <= 0)
                    continue;
                var feeder = bus.feeder;
                var scale = feeder == null ? 1 : feeder.scale;
                var leaving = injection.getOrDefault(bus, 0.0) * scale;
                var updated = (sumGV - leaving) / sumG;
                if(low <= high)
                    updated = Math.max(low, Math.min(high, updated));
                nextVoltage.put(bus, bus.voltage + 0.5 * (updated - bus.voltage));
            }
            for(var entry : nextVoltage.entrySet())
                entry.getKey().voltage = entry.getValue();
        }

        var voltages = new HashMap<IElectricNode, Double>();
        var scales = new HashMap<IElectricNode, Double>();
        var line = 0.0;
        for(var node : parent.keySet()) {
            var bus = buses.get(find(parent, node));
            voltages.put(node, bus.voltage);
            if(bus.shorted)
                scales.put(node, 0.0);
            else if(bus.sourced)
                scales.put(node, bus.scale);
            else if(bus.feeder != null)
                scales.put(node, bus.feeder.scale);
            if(bus.sourced && !bus.shorted)
                line = Math.max(line, bus.voltage);
        }

        for(var load : loads) {
            var node1 = load.getNode1();
            var node2 = load.getNode2();
            if(!load.isEnabled() || node1 == null || node2 == null || !parent.containsKey(node1) || !parent.containsKey(node2)) {
                load.setOperatingPoint(0, 0);
                continue;
            }
            var bus1 = buses.get(find(parent, node1));
            var bus2 = buses.get(find(parent, node2));
            if(bus1.shorted || bus2.shorted) {
                load.setOperatingPoint(0, 0);
                continue;
            }
            var volts = bus1.voltage - bus2.voltage;
            var feeder = feederOf(bus1, bus2);
            var brownout = 1.0;
            if(load.ratedAmps() > 0)
                brownout = feeder == null ? 0 : feeder.scale;
            load.setOperatingPoint(volts, brownout);
        }

        for(var bus : buses.values()) {
            if(!bus.sourced || bus.feeds.isEmpty())
                continue;
            var used = bus.demand * bus.scale;
            var infinite = 0;
            var finite = 0.0;
            for(var feed : bus.feeds) {
                if(Double.isFinite(feed.getAmpCapacity()))
                    finite += Math.max(0, feed.getAmpCapacity());
                else
                    ++infinite;
            }
            if(infinite > 0) {
                var each = used / infinite;
                for(var feed : bus.feeds) {
                    if(!Double.isFinite(feed.getAmpCapacity()))
                        feed.setSuppliedAmps(each);
                }
            } else if(finite > 0) {
                var share = used / finite;
                for(var feed : bus.feeds)
                    feed.setSuppliedAmps(feed.getAmpCapacity() * share);
            }
        }

        network.applyLineResult(line, anyShort, voltages, scales);
    }

    private static void applyTransformer(TransformerCoupling coupling, Map<IElectricNode, IElectricNode> parent, Map<IElectricNode, Bus> buses) {
        var ratio = coupling.getRatio();
        if(!Float.isFinite(ratio) || ratio == 0)
            return;
        var primary = coupling.primaryNodes();
        var secondary = coupling.secondaryNodes();
        if(primary.length == 0 || secondary.length == 0)
            return;
        for(var node : primary) {
            if(node == null || !parent.containsKey(node) || !buses.get(find(parent, node)).fixed)
                return;
        }
        var primaryHot = buses.get(find(parent, primary[0]));
        var primaryCold = primary.length > 1 ? buses.get(find(parent, primary[1])) : null;
        var primaryVolts = primaryCold == null ? primaryHot.voltage : primaryHot.voltage - primaryCold.voltage;
        var budget = primaryHot.ampBudget != 0 || primaryHot.sourced ? primaryHot.ampBudget
                : primaryCold != null ? primaryCold.ampBudget : 0;
        if(secondary.length == 1) {
            holdSecondary(secondary[0], ratio * primaryVolts, budget / Math.abs(ratio), parent, buses);
            return;
        }
        var coldNode = secondary[1];
        var coldBus = buses.get(find(parent, coldNode));
        var coldVolts = coldBus.fixed ? coldBus.voltage : primaryCold != null ? primaryCold.voltage : 0;
        if(!coldBus.fixed && !coldBus.shorted) {
            coldBus.voltage = coldVolts;
            coldBus.fixed = true;
        }
        holdSecondary(secondary[0], coldVolts + ratio * primaryVolts, budget / Math.abs(ratio), parent, buses);
    }

    private static void holdSecondary(IElectricNode node, double voltage, double amps, Map<IElectricNode, IElectricNode> parent, Map<IElectricNode, Bus> buses) {
        if(node == null || !parent.containsKey(node))
            return;
        var bus = buses.get(find(parent, node));
        if(bus.fixed || bus.shorted)
            return;
        bus.voltage = voltage;
        bus.fixed = true;
        bus.sourced = true;
        bus.ampBudget = amps;
        bus.feeder = bus;
    }

    private static void applySplit(SplitTransformerControllerWire split, Map<IElectricNode, IElectricNode> parent, Map<IElectricNode, Bus> buses) {
        var secondary = split.secondary;
        if(secondary == null || secondary.secondary != split)
            return;
        if(System.identityHashCode(split) > System.identityHashCode(secondary))
            return;
        var primary1 = split.getNode1();
        var primary2 = split.getNode2();
        var secondary1 = secondary.getNode1();
        var secondary2 = secondary.getNode2();
        if(primary1 == null || primary2 == null || secondary1 == null || secondary2 == null)
            return;
        if(!parent.containsKey(primary1) || !parent.containsKey(primary2))
            return;
        var hot = buses.get(find(parent, primary1));
        var cold = buses.get(find(parent, primary2));
        if(!hot.fixed || !cold.fixed)
            return;
        var budget = hot.sourced || hot.ampBudget != 0 ? hot.ampBudget : cold.ampBudget;
        var coldSide = buses.get(find(parent, secondary2));
        if(!coldSide.fixed && !coldSide.shorted) {
            coldSide.voltage = cold.voltage;
            coldSide.fixed = true;
        }
        var reference = coldSide.fixed ? coldSide.voltage : cold.voltage;
        holdSecondary(secondary1, reference + (hot.voltage - cold.voltage), budget, parent, buses);
    }

    private static Bus feederOf(Bus first, Bus second) {
        if(first.shorted || second.shorted)
            return null;
        if(first.sourced)
            return first;
        if(second.sourced)
            return second;
        if(first.feeder != null)
            return first.feeder;
        return second.feeder;
    }

    private static void collectNodes(AbstractElectricWire wire, Map<IElectricNode, IElectricNode> parent) {
        if(wire instanceof SeriesWire series) {
            for(var part : series.wires)
                collectNodes(part, parent);
            return;
        }
        if(wire.getNode1() != null)
            parent.putIfAbsent(wire.getNode1(), wire.getNode1());
        if(wire.getNode2() != null)
            parent.putIfAbsent(wire.getNode2(), wire.getNode2());
    }

    private static void classify(AbstractElectricWire wire, Map<IElectricNode, IElectricNode> parent, List<Bridge> bridges, List<Edge> edges, List<FixedVoltageLoadWire> loads) {
        if(wire instanceof SeriesWire series) {
            for(var part : series.wires)
                classify(part, parent, bridges, edges, loads);
            return;
        }
        if(wire instanceof FixedVoltageLoadWire load) {
            loads.add(load);
            return;
        }
        if(!(wire instanceof ElectricWire electric))
            return;
        if(electric.isIgnored())
            return;
        if(wire instanceof SwitchedWire switched && !switched.getState())
            return;
        var node1 = electric.getNode1();
        var node2 = electric.getNode2();
        if(node1 == null || node2 == null)
            return;
        if(electric.isVoltageBridge()) {
            if(electric.bridgeTowardNode2())
                bridges.add(new Bridge(node1, node2));
            else
                bridges.add(new Bridge(node2, node1));
            return;
        }
        var conductor = wire instanceof TransmissionLine
                || wire instanceof TransmissionLinePart
                || (wire instanceof SwitchedWire && electric.getResistance() <= CONDUCTOR_OHMS);
        if(conductor)
            union(parent, node1, node2);
        else
            edges.add(new Edge(node1, node2, electric.getResistance()));
    }

    private static void union(Map<IElectricNode, IElectricNode> parent, IElectricNode a, IElectricNode b) {
        var ra = find(parent, a);
        var rb = find(parent, b);
        if(ra != rb)
            parent.put(ra, rb);
    }

    private static IElectricNode find(Map<IElectricNode, IElectricNode> parent, IElectricNode node) {
        var root = parent.get(node);
        if(root == null)
            return node;
        if(root != node) {
            root = find(parent, root);
            parent.put(node, root);
        }
        return root;
    }

    private record Bridge(IElectricNode from, IElectricNode to) {
    }

    private record Edge(IElectricNode a, IElectricNode b, double resistance) {
    }

    private record Link(Bus other, double resistance) {
    }

    private static final class Bus {
        final List<Double> candidates = new ArrayList<>();
        final List<VoltageSourceCoupling> feeds = new ArrayList<>();
        double voltage;
        double ampBudget;
        double demand;
        double scale = 1;
        boolean fixed;
        boolean sourced;
        boolean grounded;
        boolean shorted;
        Bus feeder;

        void addAmps(double amps) {
            if(!Double.isFinite(amps)) {
                ampBudget = Double.POSITIVE_INFINITY;
                return;
            }
            if(Double.isInfinite(ampBudget))
                return;
            ampBudget += Math.max(0, amps);
        }
    }
}

package org.patryk3211.powergrid.electricity.sim;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.TransformerCoupling;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;

class FixedVoltageNetworkTest {
    @Test
    void parallelSourcesStackVoltageAndAmps() {
        var network = new ElectricalNetwork(false, null);
        var positive = new FloatingNode();
        var negative = new FloatingNode();
        var first = new VoltageSourceCoupling(positive, negative, 1, 450);
        var second = new VoltageSourceCoupling(positive, negative, 1, 450);
        first.setAmpCapacity(4);
        second.setAmpCapacity(4);
        network.addNodes(positive, negative, first, second);
        var load = new FixedVoltageLoadWire(12, positive, negative);
        load.setRatedAmps(3);
        network.addWire(load);

        network.singleTick();

        Assertions.assertEquals(900, network.getValue(positive), 1e-6);
        Assertions.assertEquals(900, network.getRemainingVoltage(), 1e-6);
        Assertions.assertEquals(900, load.receivedVoltage(), 1e-6);
        Assertions.assertEquals(1, load.outputScale(), 1e-6);
        Assertions.assertFalse(network.isFixedVoltageShorted());
    }

    @Test
    void mismatchedVoltagesStackAndDoNotShort() {
        var network = new ElectricalNetwork(false, null);
        var positive = new FloatingNode();
        var negative = new FloatingNode();
        network.addNodes(positive, negative,
                new VoltageSourceCoupling(positive, negative, 1, 40),
                new VoltageSourceCoupling(positive, negative, 1, 100));
        var load = new FixedVoltageLoadWire(12, positive, negative);
        load.setRatedAmps(1);
        network.addWire(load);

        network.singleTick();

        Assertions.assertFalse(network.isFixedVoltageShorted());
        Assertions.assertEquals(140, network.getValue(positive), 1e-6);
        Assertions.assertEquals(1, load.outputScale(), 1e-6);
    }

    @Test
    void closedSwitchShortsOnlyTheTiedBus() {
        var network = new ElectricalNetwork(false, null);
        var shortedPositive = new FloatingNode();
        var shortedNegative = new FloatingNode();
        var livePositive = new FloatingNode();
        var liveNegative = new FloatingNode();
        network.addNodes(shortedPositive, shortedNegative, livePositive, liveNegative,
                new VoltageSourceCoupling(shortedPositive, shortedNegative, 1, 100),
                new VoltageSourceCoupling(livePositive, liveNegative, 1, 50));
        network.addWire(new SwitchedWire(0.1f, shortedPositive, shortedNegative, true));
        var dead = new FixedVoltageLoadWire(12, shortedPositive, shortedNegative);
        var live = new FixedVoltageLoadWire(12, livePositive, liveNegative);
        dead.setRatedAmps(1);
        live.setRatedAmps(1);
        network.addWire(dead);
        network.addWire(live);

        network.singleTick();

        Assertions.assertTrue(network.isFixedVoltageShorted());
        Assertions.assertEquals(0, dead.outputScale(), 1e-6);
        Assertions.assertEquals(0, network.getValue(shortedPositive), 1e-6);
        Assertions.assertEquals(50, network.getValue(livePositive), 1e-6);
        Assertions.assertEquals(1, live.outputScale(), 1e-6);
    }

    @Test
    void resistorWireIsADropNotAShort() {
        var network = new ElectricalNetwork(false, null);
        var positive = new FloatingNode();
        var negative = new FloatingNode();
        network.addNodes(positive, negative, new VoltageSourceCoupling(positive, negative, 1, 100));
        var load = new FixedVoltageLoadWire(12, positive, negative);
        load.setRatedAmps(1);
        network.addWire(load);
        network.addWire(new ElectricWire(1, positive, negative));

        network.singleTick();

        Assertions.assertFalse(network.isFixedVoltageShorted());
        Assertions.assertEquals(100, network.getValue(positive), 1e-6);
        Assertions.assertEquals(1, load.outputScale(), 1e-6);
    }

    @Test
    void openLoadDoesNotConsumeTheBudget() {
        var network = new ElectricalNetwork(false, null);
        var positive = new FloatingNode();
        var negative = new FloatingNode();
        var source = new VoltageSourceCoupling(positive, negative, 1, 450);
        source.setAmpCapacity(1);
        network.addNodes(positive, negative, source);
        var load = new FixedVoltageLoadWire(12, positive, negative, false);
        load.setRatedAmps(5);
        network.addWire(load);

        network.singleTick();

        Assertions.assertEquals(450, network.getRemainingVoltage(), 1e-6);
        Assertions.assertEquals(0, load.receivedVoltage(), 1e-6);
    }

    @Test
    void voltageProbeMeasuresTheLine() {
        var network = new ElectricalNetwork(false, null);
        var positive = new FloatingNode();
        var negative = new FloatingNode();
        network.addNodes(positive, negative, new VoltageSourceCoupling(positive, negative, 1, 450));
        var probe = new FixedVoltageLoadWire(0, positive, negative);
        network.addWire(probe);

        network.singleTick();

        Assertions.assertFalse(network.isFixedVoltageShorted());
        Assertions.assertEquals(450, probe.potentialDifference(), 1e-6);
        Assertions.assertEquals(1, probe.outputScale(), 1e-6);
    }

    @Test
    void lowVoltageScalesTheMachine() {
        var network = new ElectricalNetwork(false, null);
        var positive = new FloatingNode();
        var negative = new FloatingNode();
        network.addNodes(positive, negative, new VoltageSourceCoupling(positive, negative, 1, 6));
        var load = new FixedVoltageLoadWire(12, positive, negative);
        load.setRatedAmps(2);
        network.addWire(load);

        network.singleTick();

        Assertions.assertEquals(6, load.receivedVoltage(), 1e-6);
        Assertions.assertEquals(0.5, load.outputScale(), 1e-6);
    }

    @Test
    void ampShortageScalesEveryLoad() {
        var network = new ElectricalNetwork(false, null);
        var positive = new FloatingNode();
        var negative = new FloatingNode();
        var source = new VoltageSourceCoupling(positive, negative, 1, 12);
        source.setAmpCapacity(4);
        network.addNodes(positive, negative, source);
        var first = new FixedVoltageLoadWire(12, positive, negative);
        var second = new FixedVoltageLoadWire(12, positive, negative);
        first.setRatedAmps(3);
        second.setRatedAmps(3);
        network.addWire(first);
        network.addWire(second);

        network.singleTick();

        Assertions.assertEquals(12, network.getValue(positive), 1e-6);
        Assertions.assertEquals(4.0 / 6.0, first.outputScale(), 1e-6);
        Assertions.assertEquals(4.0 / 6.0, second.outputScale(), 1e-6);
    }

    @Test
    void seriesResistorDropsVoltsByLoadAmps() {
        var network = new ElectricalNetwork(false, null);
        var sourcePositive = new FloatingNode();
        var far = new FloatingNode();
        var negative = new FloatingNode();
        network.addNodes(sourcePositive, far, negative, new VoltageSourceCoupling(sourcePositive, negative, 1, 100));
        network.addWire(new ElectricWire(10, sourcePositive, far));
        var load = new FixedVoltageLoadWire(12, far, negative);
        load.setRatedAmps(1);
        network.addWire(load);

        network.singleTick();

        Assertions.assertEquals(100, network.getValue(sourcePositive), 1e-4);
        Assertions.assertEquals(90, network.getValue(far), 1e-2);
        Assertions.assertEquals(1, load.outputScale(), 1e-3);
    }

    @Test
    void rheostatDividesVoltage() {
        var network = new ElectricalNetwork(false, null);
        var top = new FloatingNode();
        var wiper = new FloatingNode();
        var bottom = new FloatingNode();
        network.addNodes(top, wiper, bottom, new VoltageSourceCoupling(top, bottom, 1, 100));
        network.addWire(new ElectricWire(30, top, wiper));
        network.addWire(new ElectricWire(10, wiper, bottom));

        network.singleTick();

        Assertions.assertEquals(25, network.getValue(wiper), 1e-2);
    }

    @Test
    void transformerScalesVoltageAndInvertsAmps() {
        var network = new ElectricalNetwork(false, null);
        var primaryPositive = new FloatingNode();
        var primaryNegative = new FloatingNode();
        var secondaryPositive = new FloatingNode();
        var secondaryNegative = new FloatingNode();
        var source = new VoltageSourceCoupling(primaryPositive, primaryNegative, 1, 100);
        source.setAmpCapacity(10);
        network.addNodes(primaryPositive, primaryNegative, secondaryPositive, secondaryNegative, source,
                TransformerCoupling.create(2f, 1f, primaryPositive, primaryNegative, secondaryPositive, secondaryNegative));
        var load = new FixedVoltageLoadWire(12, secondaryPositive, secondaryNegative);
        load.setRatedAmps(10);
        network.addWire(load);

        network.singleTick();

        Assertions.assertEquals(100, network.getValue(primaryPositive), 1e-6);
        Assertions.assertEquals(200, network.getValue(secondaryPositive), 1e-4);
        Assertions.assertEquals(0, network.getValue(secondaryNegative), 1e-4);
        Assertions.assertEquals(0.5, load.outputScale(), 1e-3);
    }
}

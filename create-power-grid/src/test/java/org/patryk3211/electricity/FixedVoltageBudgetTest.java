package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.FixedVoltageLoadWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;

class FixedVoltageBudgetTest {
    @Test
    void twoGeneratorsStackTheirVoltages() {
        var network = new ElectricalNetwork(false, null);
        var positive = new FloatingNode();
        var negative = new FloatingNode();
        network.addNodes(positive, negative,
                new VoltageSourceCoupling(positive, negative, 1, 450),
                new VoltageSourceCoupling(positive, negative, 1, 450));
        var load = new FixedVoltageLoadWire(12, positive, negative);
        load.setRatedAmps(1);
        network.addWire(load);

        network.singleTick();

        Assertions.assertEquals(900, network.getValue(positive), 1e-6);
        Assertions.assertEquals(1, load.outputScale(), 1e-6);
    }

    @Test
    void heaterOhmsDrawFromTheAmpBudget() {
        var network = new ElectricalNetwork(false, null);
        var positive = new FloatingNode();
        var negative = new FloatingNode();
        var source = new VoltageSourceCoupling(positive, negative, 1, 100);
        source.setAmpCapacity(5);
        network.addNodes(positive, negative, source);
        network.addWire(new ElectricWire(10, positive, negative));

        network.singleTick();

        Assertions.assertEquals(100, network.getValue(positive), 1e-6);
        Assertions.assertEquals(0.5, network.getDeliveryScale(positive), 1e-6);
        Assertions.assertEquals(5, source.getSuppliedAmps(), 1e-4);
    }

    @Test
    void zeroRatedLoadsDoNotBrownOutTheLine() {
        var network = new ElectricalNetwork(false, null);
        var positive = new FloatingNode();
        var negative = new FloatingNode();
        var source = new VoltageSourceCoupling(positive, negative, 1, 24);
        source.setAmpCapacity(1);
        network.addNodes(positive, negative, source);
        var free = new FixedVoltageLoadWire(12, positive, negative);
        var paid = new FixedVoltageLoadWire(12, positive, negative);
        paid.setRatedAmps(1);
        network.addWire(free);
        network.addWire(paid);

        network.singleTick();

        Assertions.assertEquals(1, free.outputScale(), 1e-6);
        Assertions.assertEquals(1, paid.outputScale(), 1e-6);
    }

    @Test
    void stepDownKeepsPower() {
        var network = new ElectricalNetwork(false, null);
        var primaryPositive = new FloatingNode();
        var primaryNegative = new FloatingNode();
        var secondaryPositive = new FloatingNode();
        var secondaryNegative = new FloatingNode();
        var source = new VoltageSourceCoupling(primaryPositive, primaryNegative, 1, 100);
        source.setAmpCapacity(2);
        network.addNodes(primaryPositive, primaryNegative, secondaryPositive, secondaryNegative, source,
                org.patryk3211.powergrid.electricity.sim.node.TransformerCoupling.create(
                        0.5f, 1f, primaryPositive, primaryNegative, secondaryPositive, secondaryNegative));

        network.singleTick();

        Assertions.assertEquals(50, network.getValue(secondaryPositive), 1e-4);
    }
}

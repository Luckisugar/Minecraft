package org.patryk3211.powergrid.electricity.light.factorylight;

import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.FixedVoltageLoadWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

public class TopLevelSharedFilamentWire extends FixedVoltageLoadWire {
    private double conductance;

    public TopLevelSharedFilamentWire(IElectricNode node1, IElectricNode node2) {
        super(12, node1, node2);
    }

    @Override
    public double conductance() {
        return Math.max(conductance, ElectricalNetwork.G_MIN);
    }

    @Override
    public boolean isEnabled() {
        return super.isEnabled() && conductance > ElectricalNetwork.G_MIN;
    }

    public void updateConductance(double change) {
        conductance += change;
    }
}

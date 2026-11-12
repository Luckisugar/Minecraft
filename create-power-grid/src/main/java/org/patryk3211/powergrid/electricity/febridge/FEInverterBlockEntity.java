package org.patryk3211.powergrid.electricity.febridge;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.collections.ModdedConfigs;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.sim.FixedVoltageLoadWire;
import org.patryk3211.powergrid.electricity.sim.node.ProvidedVoltageSourceCoupling;

public class FEInverterBlockEntity extends ElectricBlockEntity {
    private ProvidedVoltageSourceCoupling outputSource;
    private FixedVoltageLoadWire control;

    public FEInverterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    private float rawThrottling() {
        return Mth.clamp((float) (control.potentialDifference()
                / ModdedConfigs.server().electricity.feInverterControlVoltage.get()), 0, 1);
    }

    public static int energyBufferSize() {
        return ModdedConfigs.server().electricity.feInverterBufferSize.get();
    }

    @Override
    public void electricalTick() {
        super.electricalTick();
        if(outputSource != null && storedEnergy() > 0) {
            var watts = Math.abs(outputSource.getVoltage()) * Math.max(0, outputSource.getSuppliedAmps());
            var cost = (int) Math.ceil(watts * ModdedConfigs.server().electricity.forgeEnergyPerWatt.getF());
            if(cost > 0)
                useEnergy(Math.min(storedEnergy(), cost));
            setUnsaved();
        }
    }

    protected float outputVoltage() {
        if(storedEnergy() <= 0)
            return 0;
        return ModdedConfigs.server().electricity.feInverterVoltage.getF() * (1 - rawThrottling());
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(3);
        outputSource = new ProvidedVoltageSourceCoupling(builder.terminalNode(0), builder.terminalNode(1), 1);
        outputSource.setVoltageProvider(this::outputVoltage);
        builder.add(outputSource);
        control = builder.connectLoad(ModdedConfigs.server().electricity.feInverterControlVoltage.get(), 0,
                builder.terminalNode(2), builder.terminalNode(1));
    }

    protected void useEnergy(int amount) {
        throw new AssertionError("Unimplemented");
    }

    protected int storedEnergy() {
        throw new AssertionError("Unimplemented");
    }
}

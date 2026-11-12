package org.patryk3211.powergrid.electricity.redstoneconverter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;

public class RedstoneConverterBlockEntity extends ElectricBlockEntity {
    private VoltageSourceCoupling outputSource;

    public Float signalOverride = null;

    public RedstoneConverterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void electricalTick() {
        var state = getBlockState();
        var facing = state.getValue(RedstoneConverterBlock.FACING);
        var pos = worldPosition.relative(facing);
        float signal;
        if(signalOverride != null) {
            signal = signalOverride;
        } else {
            signal = RedstoneConverterRegistry.get(level, level.getBlockState(pos), pos, facing.getOpposite());
        }
        if(!Float.isFinite(signal))
            signal = 0;
        signal = Mth.clamp(signal, 0, 1);
        if(signalOverride == null) {
            float signal2 = level.getDirectSignalTo(worldPosition) / 15.0f;
            signal = Math.max(signal, signal2);
        }
        boolean powered = signal > 1 / 30f;
        if(powered != state.getValue(RedstoneConverterBlock.POWERED)) {
            level.setBlockAndUpdate(worldPosition, state.setValue(RedstoneConverterBlock.POWERED, powered));
        }
        outputSource.setVoltage(powered ? 12 : 0);
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(3);
        outputSource = new VoltageSourceCoupling(builder.terminalNode(2), builder.terminalNode(1), 0.5f);
        builder.add(outputSource);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if(tag.contains("SignalOverride")) {
            signalOverride = tag.getFloat("SignalOverride");
        } else {
            signalOverride = null;
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if(signalOverride != null) {
            tag.putFloat("SignalOverride", signalOverride);
        }
    }
}

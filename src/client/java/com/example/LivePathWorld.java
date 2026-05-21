package com.example;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;

final class LivePathWorld implements PathWorldView {
    private final ClientWorld world;

    LivePathWorld(ClientWorld world) {
        this.world = world;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return world.getBlockEntity(pos);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return world.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return world.getFluidState(pos);
    }

    @Override
    public int getBottomY() {
        return world.getBottomY();
    }

    @Override
    public int getHeight() {
        return world.getHeight();
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return world.getChunkManager().getChunk(
                pos.getX() >> 4,
                pos.getZ() >> 4,
                ChunkStatus.FULL,
                false
        ) != null;
    }

    @Override
    public boolean isSupplyChestAt(BlockPos pos) {
        return HeadHighlighterController.isSupplyChestAtLive(world, pos);
    }

    @Override
    public boolean isZombieDangerAt(BlockPos pos) {
        return false;
    }
}

package com.example;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

interface PathWorldView extends BlockView {
    boolean isLoaded(BlockPos pos);

    boolean isSupplyChestAt(BlockPos pos);

    boolean isZombieDangerAt(BlockPos pos);
}

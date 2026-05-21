package com.example;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.List;

final class HeadTargetScanner {
    private HeadTargetScanner() {
    }

    static HeadTargetScan scan(MinecraftClient client, ClientWorld world) {
        LongOpenHashSet found = new LongOpenHashSet();
        LongOpenHashSet droppedItems = new LongOpenHashSet();

        double sumX = 0.0;
        double sumY = 0.0;
        double sumZ = 0.0;
        int count = 0;

        ClientChunkManager chunkManager = world.getChunkManager();
        int radius = client.options.getViewDistance().getValue();
        ChunkPos center = new ChunkPos(client.player.getBlockPos());

        for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
            for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
                WorldChunk chunk = chunkManager.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }

                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockState state = blockEntity.getCachedState();
                    if (!state.isOf(Blocks.CHEST)) {
                        continue;
                    }

                    BlockPos chestPos = blockEntity.getPos();
                    if (HeadHighlighterController.isPoliceStationChestTarget(chestPos.asLong())) {
                        continue;
                    }

                    if (found.add(chestPos.asLong())) {
                        sumX += chestPos.getX() + 0.5;
                        sumY += chestPos.getY() + 0.5;
                        sumZ += chestPos.getZ() + 0.5;
                        count++;
                    }
                }
            }
        }

        scanDroppedItems(client, world, droppedItems);

        ItemCenterMass centerMass = count > 0
                ? new ItemCenterMass(true, sumX / count, sumY / count, sumZ / count, count)
                : ItemCenterMass.EMPTY;

        return new HeadTargetScan(found.toLongArray(), centerMass, droppedItems.toLongArray());
    }

    private static void scanDroppedItems(MinecraftClient client, ClientWorld world, LongOpenHashSet droppedItems) {
        Vec3d playerPos = client.player.getPos();
        double radius = client.options.getViewDistance().getValue() * 16.0 + 16.0;

        Box searchBox = new Box(
                playerPos.x - radius,
                playerPos.y - 64.0,
                playerPos.z - radius,
                playerPos.x + radius,
                playerPos.y + 64.0,
                playerPos.z + radius
        );

        List<ItemEntity> items = world.getEntitiesByType(
                TypeFilter.instanceOf(ItemEntity.class),
                searchBox,
                item -> item != null
                        && item.isAlive()
                        && !item.getStack().isEmpty()
                        && HeadHighlighterController.isWantedLootStack(item.getStack())
        );

        for (ItemEntity item : items) {
            droppedItems.add(BlockPos.ofFloored(item.getX(), item.getY(), item.getZ()).asLong());
        }
    }
}

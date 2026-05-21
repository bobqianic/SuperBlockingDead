package com.example;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

final class PathSearchCache {
    final Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
    final Long2DoubleOpenHashMap feetY = new Long2DoubleOpenHashMap();
    final LongOpenHashSet loadedChunks = new LongOpenHashSet();
    final LongOpenHashSet unloadedChunks = new LongOpenHashSet();

    PathSearchCache() {
        parent.defaultReturnValue(HeadHighlighterController.NO_POS);
        feetY.defaultReturnValue(HeadHighlighterController.UNKNOWN_FEET_Y);
    }
}

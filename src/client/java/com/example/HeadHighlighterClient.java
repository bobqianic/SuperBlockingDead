package com.example;

import net.fabricmc.api.ClientModInitializer;

public final class HeadHighlighterClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HeadHighlighterController.initialize();
    }
}

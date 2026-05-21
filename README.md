# SuperBlockingDead

SuperBlockingDead is a client-side Minecraft Fabric mod for the Blocking Dead minigame. It scans the loaded world, finds supply chests and useful dropped items, computes live navigation paths, renders the current route, and drives the player through the loot, combat, inventory, and rescue loop.

**Status: full automation achieved.** The mod can automate the supported gameplay cycle end to end: locate loot, path to targets, move through the map, open and loot chests, collect dropped supplies, manage hotbar and inventory, equip armor, use health and food, shoot visible zombies, avoid blocked routes, and react to rescue callouts.

![Demo](demo.gif)

## Features

- Full search + fight automation with one toggle.
- Fight-only mode for automated zombie targeting without route driving.
- Supply chest scanning across loaded chunks.
- Dropped item detection for ammo, health packs, food, and armor.
- Asynchronous pathfinding using world snapshots so path work stays off the client tick.
- Path rendering, chest glow/outline overlays, and a small HUD for mode, target, path length, and worker state.
- Live repathing when a target is looted, unreachable, blocked by zombies, or the player gets off route.
- Door handling, final chest approach logic, water/jump handling, and wall/corner correction.
- Automatic chest looting with server-sync delays.
- Automatic inventory cleanup, hotbar preparation, armor equip, health-pack use, and food use.
- Automatic gun choice based on visible zombie pressure, including pistol, M16, and shotgun ammo checks.
- Rescue route detection from server messages for known rescue locations.

## Controls

Default keybinds are registered under the `SuperBlockingDead` controls category:

- `G` - Toggle Search + Fight
- `H` - Toggle Fight Only

The HUD shows whether automation is off, in loot+fight mode, or in fight-only mode.

## Requirements

- Minecraft `1.21.8`
- Java `21`
- Fabric Loader `0.18.4` or newer
- Fabric API `0.136.1+1.21.8`

## Build

On Windows:

```powershell
.\gradlew.bat build
```

On macOS/Linux:

```bash
./gradlew build
```

The built mod jar is written to `build/libs/`.

## Project Layout

- `src/client/java/com/example/HeadHighlighterController.java` - main automation controller, HUD, rendering, path requests, movement, combat, looting, and inventory behavior.
- `src/client/java/com/example/HeadTargetScanner.java` - scans loaded chunks for chests and dropped useful items.
- `src/client/java/com/example/SnapshotPathWorld.java` - immutable world snapshot used by async pathfinding.
- `src/main/resources/fabric.mod.json` - Fabric mod metadata and client entrypoint.

## Note

This project is intended for development and testing in environments where automation is allowed. Do not use it where it violates server rules or disrupts other players.

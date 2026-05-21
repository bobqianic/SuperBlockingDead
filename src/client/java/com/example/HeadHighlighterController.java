package com.example;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.ChunkStatus;

import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

final class HeadHighlighterController {

    public static final String MODID = "superblockingdead";

    private static volatile long[] SUPPLY_CHEST_POSITIONS_SNAPSHOT = new long[0];
    private static volatile long[] DROPPED_ITEM_POSITIONS_SNAPSHOT = new long[0];
    private static volatile ItemCenterMass ITEM_CENTER_MASS_SNAPSHOT = ItemCenterMass.EMPTY;
    private static volatile RescueTarget RESCUE_TARGET = null;

    private static final int REFRESH_EVERY_TICKS = 20;
    private static int refreshCountdown = 0;

    private static final int OUTLINE_COLOR = 0xFFFF00FF;

    private static final double GLOW_DISTANCE = 8.0;
    private static final double GLOW_DISTANCE_SQ = GLOW_DISTANCE * GLOW_DISTANCE;

    private static volatile long[] PATH_POSITIONS_SNAPSHOT = new long[0];
    private static volatile long PATH_TARGET_SNAPSHOT = Long.MIN_VALUE;
    private static volatile PathMode PATH_MODE_SNAPSHOT = PathMode.CHEST;

    static final long NO_POS = Long.MIN_VALUE;

    private static final int PATH_REFRESH_EVERY_TICKS = 5;
    private static int pathRefreshCountdown = 0;

    private static final int MAX_BFS_NODES = 80_000;
    private static final int MAX_BFS_XZ = 96;
    private static final int MAX_BFS_Y = 48;
    private static final int MAX_DROP = 12;

    private static final SearchBounds DEFAULT_SEARCH_BOUNDS = new SearchBounds(MAX_BFS_XZ, MAX_BFS_Y);
    private static final SearchBounds RESCUE_SEARCH_BOUNDS = new SearchBounds(160, 64);

    private static final long PATHFIND_HARD_LIMIT_NANOS = 2_000_000_000L;

    private static final int FAILED_TARGET_IGNORE_TICKS = 100;
    private static final Long2IntOpenHashMap FAILED_PATH_TARGETS = new Long2IntOpenHashMap();

    private static final int MAX_TARGET_CANDIDATES = 8;

    private static final int CLICKED_TARGET_IGNORE_TICKS = 30 * 20;
    private static final Long2IntOpenHashMap RECENTLY_CLICKED_TARGETS = new Long2IntOpenHashMap();
    private static final int SCOUTED_RESCUE_IGNORE_TICKS = 45 * 20;
    private static final Long2IntOpenHashMap SCOUTED_RESCUE_TARGETS = new Long2IntOpenHashMap();
    private static boolean FORCE_PATH_REBUILD = false;

    private static final double REACH_PADDING = 0.25;
    private static final double CHEST_FRONT_PLAYER_PADDING = 0.15;
    private static final double CHEST_FRONT_PLAYER_SCAN_HEIGHT = 2.2;

    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_HALF_WIDTH = PLAYER_WIDTH * 0.5;
    private static final double PLAYER_HEIGHT = 1.8;

    private static final double STEP_HEIGHT = 0.6;
    private static final double JUMP_HEIGHT = 1.25;

    private static final double PATH_REUSE_VERTICAL_TOLERANCE = JUMP_HEIGHT + 0.15;
    private static final double PATH_REUSE_HORIZONTAL_DISTANCE = 2.0;
    private static final double AUTO_PATH_VERTICAL_TOLERANCE = JUMP_HEIGHT + 0.15;
    private static final double AUTO_VERTICAL_SEGMENT_SCORE_WEIGHT = 0.25;
    private static final double AUTO_LOOKAHEAD_DISTANCE = 1.15;
    private static int AUTO_SIDE_CORRECTION_DIR = 0; // -1 = left, 1 = right
    private static int AUTO_SIDE_CORRECTION_SEGMENT = -1;
    private static final double AUTO_DROP_LOOKAHEAD_DISTANCE = 0.80;
    private static final double AUTO_DROP_REUSE_HORIZONTAL_DISTANCE = 1.35;
    private static final double AUTO_DROP_REUSE_HORIZONTAL_DISTANCE_SQ =
            AUTO_DROP_REUSE_HORIZONTAL_DISTANCE * AUTO_DROP_REUSE_HORIZONTAL_DISTANCE;
    private static final double AUTO_DROP_VERTICAL_PADDING = 0.75;
    private static final double PATH_SIDE_CORRECTION_ENGAGE = 0.28;
    private static final double PATH_SIDE_CORRECTION_RELEASE = 0.10;
    private static final double PATH_SIDE_CORRECTION_SWITCH = 0.42;
    private static final double PATH_SMOOTH_MAX_DOWN_STEP = 0.05;

    private static final double HEIGHT_CHANGE_EPSILON = 0.05;
    private static final double JUMP_HEADROOM_RISE = 0.42;
    private static final double JUMP_RISE_EPSILON = 0.08;
    private static final double JUMP_TRIGGER_DISTANCE_SQ = 0.85 * 0.85;
    private static final double COLLISION_EPSILON = 1.0E-5;

    private static final int MOVE_COST_FLAT = 10;
    private static final int MOVE_COST_STEP_UP = 12;
    private static final int MOVE_COST_DROP = 12;
    private static final int MOVE_COST_JUMP_UP = 96;
    private static final int WATER_WALK_PENALTY = 96;
    private static final int WATER_EXIT_LOOKAHEAD_NODES = 4;
    private static final double WATER_EXIT_MAX_DROP = 0.75;

    private static final int TRAPDOOR_MOVE_SWEEP_SAMPLES = 6;
    private static final int MOVE_SWEEP_SAMPLES = 6;
    private static final int OPEN_DOOR_CLOSE_SWEEP_SAMPLES = 6;

    private static final int PATH_SMOOTH_MAX_LOOKAHEAD = 28;
    private static final int PATH_SMOOTH_MAX_SAMPLES = 96;
    private static final double PATH_SMOOTH_SAMPLE_SPACING = 0.35;
    private static final double PATH_SMOOTH_FEET_Y_TOLERANCE = 0.18;
    private static final double PATH_SMOOTH_MAX_HEIGHT_DELTA = STEP_HEIGHT + 0.05;
    private static final double PATH_SMOOTH_DOOR_SCAN_PADDING = 0.08;
    private static final double PATH_SMOOTH_Y_FLOOR_EPSILON = 1.0E-4;

    // Wall-safety tuning. These keep smoothed/auto paths a little farther from walls,
    // especially around corners, without banning narrow corridors completely.
    private static final double PATH_SMOOTH_EXTRA_WALL_CLEARANCE = 0.10;
    private static final double PATH_NODE_WALL_PROBE_CLEARANCE = 0.22;
    private static final int PATH_NODE_WALL_PENALTY = 8;
    private static final int PATH_DIAGONAL_WALL_PENALTY = 8;
    private static final int PATH_TURN_WALL_PENALTY = 28;
    private static final double AUTO_WALL_AVOIDANCE_OFFSET = 0.11;
    private static final double AUTO_WALL_AVOIDANCE_PROBE = 0.22;
    private static final double AUTO_CORNER_LOOKAHEAD_DISTANCE = 0.55;
    private static final double AUTO_CORNER_LOOKAHEAD_DISTANCE_SQ =
            AUTO_CORNER_LOOKAHEAD_DISTANCE * AUTO_CORNER_LOOKAHEAD_DISTANCE;

    private static final int[][] WALK_OFFSETS = {
            { 1,  0},
            {-1,  0},
            { 0,  1},
            { 0, -1},
            { 1,  1},
            { 1, -1},
            {-1,  1},
            {-1, -1}
    };

    static final double UNKNOWN_FEET_Y = -1.0E30;

    private static final int PATH_COLOR = 0xFF00FFFF;
    private static final int PATH_TARGET_COLOR = 0xFFFFFF00;

    private static KeyBinding AUTO_KEY;
    private static KeyBinding FIGHT_ONLY_KEY;

    private static boolean AUTO_ENABLED = false;
    private static boolean FIGHT_ONLY_ENABLED = false;
    private static boolean AUTO_WAS_DRIVING = false;

    private static final int AUTO_CLICK_COOLDOWN_TICKS = 8;
    private static int autoClickCooldown = 0;

    private static final int CHEST_QUICK_MOVE_PER_TICK = 1;
    private static final int CHEST_LOOT_SETTLE_TICKS = 5;
    private static final int CHEST_LOOT_EMPTY_CLOSE_TICKS = 6;
    private static final int CHEST_LOOT_BLANK_CLOSE_TICKS = 20;
    private static final int PLAYER_MAIN_INVENTORY_SIZE = 36;
    private static final int INVENTORY_ROW_WIDTH = 9;
    private static final int HEALTH_PACK_KEEP_SLOTS = INVENTORY_ROW_WIDTH;
    private static final int EXTRA_HEALTH_PACK_THROW_COOLDOWN_TICKS = 1;
    private static final int AUTO_ARMOR_EQUIP_COOLDOWN_TICKS = 4;
    private static final int INVENTORY_MOVE_COOLDOWN_TICKS = 2;
    private static final int AUTO_INVENTORY_SETTLE_TICKS = 2;
    private static final double DROPPED_ITEM_PICKUP_DISTANCE = 1.45;
    private static final double DROPPED_ITEM_PICKUP_DISTANCE_SQ =
            DROPPED_ITEM_PICKUP_DISTANCE * DROPPED_ITEM_PICKUP_DISTANCE;
    private static final double DROPPED_ITEM_PICKUP_VERTICAL_TOLERANCE = 1.75;
    private static final int DROPPED_ITEM_GOAL_SCAN_XZ = 2;
    private static final int DROPPED_ITEM_GOAL_SCAN_Y = 2;
    private static int CHEST_LOOT_SYNC_ID = -1;
    private static long CHEST_LOOT_TARGET = NO_POS;
    private static int CHEST_LOOT_TICKS = 0;
    private static int CHEST_LOOT_EMPTY_TICKS = 0;
    private static boolean CHEST_LOOT_SAW_CONTENTS = false;
    private static final int CHEST_LOOT_MOVE_INTERVAL_TICKS = 3; // start with 3, tune 2-4
    private static int CHEST_LOOT_MOVE_COOLDOWN = 0;
    private static int extraHealthPackThrowCooldown = 0;
    private static int autoArmorEquipCooldown = 0;
    private static int inventoryMoveCooldown = 0;
    private static boolean AUTO_INVENTORY_OPENED = false;
    private static int AUTO_INVENTORY_TICKS = 0;

    private static final double SAFE_SHOT_LATERAL_MARGIN = 0.14;
    private static final double SAFE_SHOT_VERTICAL_MARGIN = 0.08;

    private static final double ZOMBIE_DIRECTION_CONE_DEGREES = 18.0;
    private static final double ZOMBIE_DIRECTION_CONE_COS =
            Math.cos(Math.toRadians(ZOMBIE_DIRECTION_CONE_DEGREES));
    private static final double ZOMBIE_LEAD_TICKS = 1.0;
    private static final double ZOMBIE_VELOCITY_BLEND = 0.55;
    private static final double ZOMBIE_MAX_HORIZONTAL_LEAD = 0.55;
    private static final HashMap<Integer, Vec3d> LAST_ZOMBIE_POSITIONS = new HashMap<>();
    private static final HashMap<Integer, Vec3d> ZOMBIE_ESTIMATED_VELOCITIES = new HashMap<>();
    private static final double ZOMBIE_SHOOT_RANGE = 20.0;
    private static final double ZOMBIE_SHOOT_RANGE_SQ = ZOMBIE_SHOOT_RANGE * ZOMBIE_SHOOT_RANGE;
    private static final double ZOMBIE_PATH_AVOIDANCE_PADDING = PLAYER_HALF_WIDTH + 0.20;
    private static final double ZOMBIE_PATH_VERTICAL_PADDING = 0.25;
    private static final double ZOMBIE_PATH_SEARCH_PADDING = 2.0;
    private static final int AUTO_SHOOT_COOLDOWN_TICKS = 4;
    private static int autoShootCooldown = 0;
    private static final String PISTOL_NAME = "Pistol";
    private static final String M16_NAME = "M16";
    private static final String SHOTGUN_NAME = "Shotgun";
    private static final String PISTOL_AMMO_NAME = "Pistol Ammo";
    private static final String M16_AMMO_NAME = "M16 Ammo";
    private static final String SHOTGUN_AMMO_NAME = "Shotgun Ammo";
    private static final String FRIED_CHICKEN_NAME = "Hypixel Fried Chicken";
    private static final String HEALTH_PACK_NAME = "Health Pack";
    private static final String[] LOW_ZOMBIE_GUN_PRIORITY = { PISTOL_NAME, M16_NAME, SHOTGUN_NAME };
    private static final String[] MEDIUM_ZOMBIE_GUN_PRIORITY = { M16_NAME, PISTOL_NAME, SHOTGUN_NAME };
    private static final String[] HIGH_ZOMBIE_GUN_PRIORITY = { SHOTGUN_NAME, M16_NAME, PISTOL_NAME };
    private static final int LOW_ZOMBIE_COUNT_MAX = 2;
    private static final int MEDIUM_ZOMBIE_COUNT_MAX = 5;
    private static final float HEALTH_PACK_TRIGGER_HEALTH = 12.0f;
    private static final float HEALTH_PACK_STOP_HEALTH = 18.0f;
    private static final int FOOD_TRIGGER_LEVEL = 18;
    private static final int FOOD_STOP_LEVEL = 20;
    private static final int SURVIVAL_USE_RETRY_TICKS = 6;
    private static final int FOOD_USE_RETRY_TICKS = 40;
    private static int survivalUseCooldown = 0;

    private static final double FOOD_CANCEL_NEAR_ZOMBIE_RANGE = 3.25;
    private static final double FOOD_CANCEL_NEAR_ZOMBIE_RANGE_SQ =
            FOOD_CANCEL_NEAR_ZOMBIE_RANGE * FOOD_CANCEL_NEAR_ZOMBIE_RANGE;

    private static int AUTO_WAYPOINT_INDEX = 1;
    private static long AUTO_LAST_TARGET = NO_POS;
    private static long AUTO_LAST_PATH_END = NO_POS;
    private static long[] AUTO_LAST_PATH_REF = null;
    private static int AUTO_OFF_PATH_TICKS = 0;
    private static long AUTO_FINAL_APPROACH_TARGET = NO_POS;
    private static double AUTO_FINAL_APPROACH_BEST_EYE_DISTANCE = Double.POSITIVE_INFINITY;
    private static int AUTO_FINAL_APPROACH_STUCK_TICKS = 0;

    private static final double WAYPOINT_ADVANCE_DISTANCE_SQ = 0.55 * 0.55;
    private static final double WAYPOINT_PASS_DISTANCE_SQ = 0.95 * 0.95;

    private static final float AUTO_MOVE_YAW_TOLERANCE = 18.0f;
    private static final float AUTO_MOVE_TARGET_PITCH = 0.0f;
    private static final float AUTO_MAX_YAW_CHANGE_PER_TICK = 60.0f;
    private static final float AUTO_GROUND_MAX_YAW_CHANGE_PER_TICK = 36.0f;
    private static final float AUTO_AIR_MAX_YAW_CHANGE_PER_TICK = 12.0f;
    private static final float AUTO_MAX_PITCH_CHANGE_PER_TICK = 8.0f;
    private static final float AUTO_SPRINT_JUMP_YAW_TOLERANCE = 16.0f;
    private static final double AUTO_SPRINT_JUMP_MIN_SPEED_SQ = 0.03 * 0.03;
    private static final double AUTO_SPRINT_JUMP_MAX_RISE = 0.20;
    private static final double AUTO_SPRINT_JUMP_MAX_DROP = 0.12;
    private static final double AUTO_SPRINT_JUMP_MIN_WAYPOINT_DIST_SQ = 0.70 * 0.70;
    private static final double AUTO_LOW_CLEARANCE_JUMP_SUPPRESS_LOOKAHEAD = 2.50;
    private static final double AUTO_LOW_CLEARANCE_SAMPLE_SPACING = 0.35;
    private static final double AUTO_SPRINT_JUMP_TURN_SUPPRESS_DISTANCE = 1.15;
    private static final double AUTO_SPRINT_JUMP_TURN_SUPPRESS_DISTANCE_SQ =
            AUTO_SPRINT_JUMP_TURN_SUPPRESS_DISTANCE * AUTO_SPRINT_JUMP_TURN_SUPPRESS_DISTANCE;

    // more conservative than 0.55; disables jump on ~45°+ bends
    private static final double AUTO_SPRINT_JUMP_SHARP_TURN_DOT = 0.70;

    private static final double AUTO_REALIGN_DISTANCE = 1.35;
    private static final double AUTO_REALIGN_DISTANCE_SQ = AUTO_REALIGN_DISTANCE * AUTO_REALIGN_DISTANCE;
    private static final double AUTO_REALIGN_IMPROVEMENT_SQ = 0.35 * 0.35;
    private static final double AUTO_OFF_PATH_REPATH_DISTANCE = 2.0;
    private static final double AUTO_COLLIDING_OFF_PATH_REPATH_DISTANCE = 1.35;
    private static final int AUTO_OFF_PATH_REPATH_TICKS = 4;
    private static final double AUTO_SEGMENT_ADVANCE_T = 0.72;
    private static final double AUTO_FINAL_CHEST_APPROACH_WAYPOINT_DISTANCE = 0.90;
    private static final double AUTO_FINAL_CHEST_APPROACH_WAYPOINT_DISTANCE_SQ =
            AUTO_FINAL_CHEST_APPROACH_WAYPOINT_DISTANCE * AUTO_FINAL_CHEST_APPROACH_WAYPOINT_DISTANCE;
    private static final double AUTO_FINAL_CHEST_APPROACH_EXTRA_REACH = 1.25;
    private static final double AUTO_FINAL_CHEST_APPROACH_MIN_HORIZONTAL_DISTANCE = 0.80;
    private static final double AUTO_FINAL_CHEST_APPROACH_MIN_HORIZONTAL_DISTANCE_SQ =
            AUTO_FINAL_CHEST_APPROACH_MIN_HORIZONTAL_DISTANCE * AUTO_FINAL_CHEST_APPROACH_MIN_HORIZONTAL_DISTANCE;
    private static final double AUTO_FINAL_CHEST_APPROACH_PROGRESS_EPSILON = 0.05;
    private static final int AUTO_FINAL_CHEST_APPROACH_STUCK_TICKS = 30;

    private static final double RESCUE_ARRIVAL_TOLERANCE = 3.0;
    private static final double RESCUE_ARRIVAL_TOLERANCE_SQ = RESCUE_ARRIVAL_TOLERANCE * RESCUE_ARRIVAL_TOLERANCE;
    private static final double RESCUE_VERTICAL_TOLERANCE = 3.0;
    private static final int RESCUE_GOAL_SCAN_XZ = 3;
    private static final int RESCUE_GOAL_SCAN_Y = 4;
    private static final int RESCUE_APPROACH_GOAL_CANDIDATES = 24;
    private static final int RESCUE_APPROACH_SCAN_XZ = 96;
    private static final int RESCUE_APPROACH_SCAN_Y = 10;
    private static final int RESCUE_APPROACH_SCAN_STEP = 2;
    private static final double RESCUE_APPROACH_REPATH_DISTANCE = 4.0;
    private static final double RESCUE_APPROACH_REPATH_DISTANCE_SQ =
            RESCUE_APPROACH_REPATH_DISTANCE * RESCUE_APPROACH_REPATH_DISTANCE;
    private static final double RESCUE_GOAL_SCORE_EPSILON = 1.0E-6;
    private static final int POLICE_STATION_MIN_X = -855;
    private static final int POLICE_STATION_MAX_X = -824;
    private static final int POLICE_STATION_MIN_Z = -283;
    private static final int POLICE_STATION_MAX_Z = -265;

    private static final ExecutorService PATH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "superblockingdead-pathfinder");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicLong PATH_REQUEST_COUNTER = new AtomicLong();

    private static volatile boolean PATH_SEARCH_RUNNING = false;
    private static volatile long LATEST_PATH_REQUEST_ID = 0L;
    private static volatile AsyncPathResult PENDING_PATH_RESULT = null;

    private static final int PATH_SNAPSHOT_RADIUS_CHUNKS = 8;
    private static final ThreadLocal<LongOpenHashSet> PATH_THREAD_IGNORES = new ThreadLocal<>();

    private static final RescueLocation[] RESCUE_LOCATIONS = {
            new RescueLocation("Farm House", new String[]{"farm house", "farmhouse"}, new RescuePoint[]{
                    new RescuePoint(-897.5, 19.0, -149.5)
            }),
            new RescueLocation("Grave Yard", new String[]{"grave yard", "graveyard"}, new RescuePoint[]{
                    new RescuePoint(-883.5, 21.0, -267.5)
            }),
            new RescueLocation("Scrap Yard", new String[]{"scrap yard", "scrapyard"}, new RescuePoint[]{
                    new RescuePoint(-716.5, 18.0, -120.5)
            }),
            new RescueLocation("Gas Station", new String[]{"gas station"}, new RescuePoint[]{
                    new RescuePoint(-797.5, 19.0, -102.5)
            }),
            new RescueLocation("Barn", new String[]{"barn"}, new RescuePoint[]{
                    new RescuePoint(-891.5, 19.0, -108.5)
            }),
            new RescueLocation("Church", new String[]{"church"}, new RescuePoint[]{
                    new RescuePoint(-880.5, 19.0, -235.5)
            }),
            new RescueLocation("Cornfield", new String[]{"cornfield", "corn field"}, new RescuePoint[]{
                    new RescuePoint(-842.5, 21.0, -97.5)
            }),
            new RescueLocation("Police", new String[]{"police"}, new RescuePoint[]{
                    new RescuePoint(-834.5, 19.0, -272.5)
            }),
            new RescueLocation("School", new String[]{"school"}, new RescuePoint[]{
                    new RescuePoint(-756.5, 19.0, -276.5),
                    new RescuePoint(-743.5, 19.0, -254.5)
            }),
            new RescueLocation("Gym", new String[]{"gym"}, new RescuePoint[]{
                    new RescuePoint(-740.5, 19.0, -247.5),
                    new RescuePoint(-717.5, 22.0, -227.5)
            }),
            new RescueLocation("Bank", new String[]{"bank"}, new RescuePoint[]{
                    new RescuePoint(-730.5, 19.0, -166.5),
                    new RescuePoint(-737.5, 19.0, -145.0)
            }),
            new RescueLocation("Lake", new String[]{"lake"}, new RescuePoint[]{
                    new RescuePoint(-788.5, 12.0, -207.5)
            })
    };

    private record PathResult(long[] path, long target) {}

    private record ScoredPath(long[] path, long target, int cost) {}

    private record AStarNode(long packed, int g, int h, int f) {}

    private record SearchBounds(int maxXz, int maxY) {}

    private enum PathMode {
        CHEST,
        ITEM,
        RESCUE,
        SCOUT
    }

    private record AsyncPathResult(long requestId, PathMode mode, long[] path, long target) {}

    private record ZombieTargetChoice(ZombieEntity zombie, Vec3d aimPoint, int directionalZombieCount) {}
    private record ZombieAimCandidate(ZombieEntity zombie, Vec3d aimPoint, double distSq, Vec3d direction) {}

    private record RescuePoint(double x, double y, double z) {
        BlockPos blockPos() {
            return BlockPos.ofFloored(x, y, z);
        }

        double squaredDistanceTo(Vec3d pos) {
            double dx = pos.x - x;
            double dy = pos.y - y;
            double dz = pos.z - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private record RescueLocation(String name, String[] keywords, RescuePoint[] points) {}

    private record RescueTarget(String name, RescuePoint point, long targetPacked) {}

    private record ScoredRescueGoal(long packed, double score) {}

    private enum SurvivalUseType {
        HEALTH_PACK,
        FOOD
    }

    private record PathJob(
            long requestId,
            PathWorldView world,
            BlockPos start,
            long[] targetsSnapshot,
            ItemCenterMass centerMass,
            long[] droppedItemTargetsSnapshot,
            boolean inventoryFull,
            double reach,
            double eyeHeight,
            long stickyTarget,
            LongOpenHashSet ignoredTargets,
            LongOpenHashSet ignoredScoutTargets,
            PathMode mode,
            RescueTarget rescueTarget
    ) {}

    static void initialize() {
        registerKeyBindings();
        registerHud();
        registerChatListener();
        registerClientTick();
        registerWorldRenderer();
    }

    private static void registerKeyBindings() {
        AUTO_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.superblockingdead.auto",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.superblockingdead"
        ));

        FIGHT_ONLY_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.superblockingdead.fight_only",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.superblockingdead"
        ));
    }

    private static void registerChatListener() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                handleIncomingServerMessage(message)
        );
    }

    private static void registerHud() {
        HudElementRegistry.addLast(Identifier.of(MODID, "auto_hud"), (context, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();

            if (client == null || client.options == null || client.options.hudHidden) {
                return;
            }

            String modeText;
            int modeColor;

            if (AUTO_ENABLED) {
                modeText = "AUTO: LOOT+FIGHT";
                modeColor = 0xFF55FF55;
            } else if (FIGHT_ONLY_ENABLED) {
                modeText = "AUTO: FIGHT ONLY";
                modeColor = 0xFFFFFF55;
            } else {
                modeText = "AUTO: OFF";
                modeColor = 0xFFFF5555;
            }

            RescueTarget rescueTarget = RESCUE_TARGET;
            String targetText = rescueTarget != null
                    ? "Rescue: " + rescueTarget.name()
                    : PATH_MODE_SNAPSHOT == PathMode.ITEM ? "Item: found"
                    : PATH_MODE_SNAPSHOT == PathMode.SCOUT ? "Search: loot area"
                    : PATH_TARGET_SNAPSHOT != NO_POS ? "Chest: found" : "Chest: none";

            context.drawTextWithShadow(client.textRenderer, modeText, 10, 10, modeColor);
            context.drawTextWithShadow(client.textRenderer, targetText, 10, 22, 0xFFFFFFFF);
            context.drawTextWithShadow(client.textRenderer, "Path: " + PATH_POSITIONS_SNAPSHOT.length + " nodes", 10, 34, 0xFFFFFFFF);
            context.drawTextWithShadow(client.textRenderer, PATH_SEARCH_RUNNING ? "Worker: pathing" : "Worker: idle", 10, 46, 0xFFFFFFFF);
        });
    }

    private static void registerClientTick() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null) {
                LAST_ZOMBIE_POSITIONS.clear();
                ZOMBIE_ESTIMATED_VELOCITIES.clear();
                return;
            }

            publishPendingPathResult(client);
            tickHeadCache(client);
            tickTargetIgnoreTimers();
            tickInventoryCleanupTimers();
            tickObservedPlayerLootedTargets(client, client.world);
            tickZombieMotionEstimator(client, client.world);
            tickPathRebuild(client);
            tickAutoToggle(client);

            if (AUTO_ENABLED || FIGHT_ONLY_ENABLED) {
                tickAutoMode(client, client.world);
            }
        });
    }

    private static void tickHeadCache(MinecraftClient client) {
        if (refreshCountdown-- > 0) {
            return;
        }

        refreshCountdown = REFRESH_EVERY_TICKS;
        rebuildCache(client, client.world);
    }

    private static void tickTargetIgnoreTimers() {
        tickRecentlyClickedTargets();
        tickFailedPathTargets();
        tickScoutedRescueTargets();
    }

    private static void tickInventoryCleanupTimers() {
        if (extraHealthPackThrowCooldown > 0) {
            extraHealthPackThrowCooldown--;
        }

        if (inventoryMoveCooldown > 0) {
            inventoryMoveCooldown--;
        }
    }

    private static void handleIncomingServerMessage(Text message) {
        if (isPlayerAuthoredGameMessage(message)) {
            return;
        }

        String cleanMessage = stripLegacyFormatting(message.getString());
        RescueLocation location = findRescueLocation(cleanMessage);
        if (location == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        RescuePoint point = chooseNearestRescuePoint(client, location);
        RescueTarget target = new RescueTarget(location.name(), point, point.blockPos().asLong());

        RescueTarget current = RESCUE_TARGET;
        if (current != null && current.targetPacked() == target.targetPacked()) {
            return;
        }

        RESCUE_TARGET = target;
        clearPathSnapshot();
        resetAutoPathState();
        invalidatePendingPathWork();
        FORCE_PATH_REBUILD = true;
        pathRefreshCountdown = 0;
    }

    private static String stripLegacyFormatting(String message) {
        if (message == null) {
            return "";
        }

        return message.replaceAll("(?i)\u00A7[0-9A-FK-ORX]", "");
    }

    private static boolean isPlayerAuthoredGameMessage(Text message) {
        if (message == null) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null) {
            return false;
        }

        String cleanMessage = stripLegacyFormatting(message.getString()).trim();
        if (cleanMessage.isEmpty()) {
            return false;
        }

        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            if (entry == null || entry.getProfile() == null) {
                continue;
            }

            if (hasPlayerChatPrefix(cleanMessage, entry.getProfile().getName())) {
                return true;
            }

            Text displayName = entry.getDisplayName();
            if (displayName != null
                    && hasPlayerChatPrefix(cleanMessage, stripLegacyFormatting(displayName.getString()).trim())) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasPlayerChatPrefix(String message, String playerName) {
        if (message == null || playerName == null || playerName.isBlank()) {
            return false;
        }

        String cleanName = playerName.trim();
        return message.startsWith("<" + cleanName + ">")
                || message.startsWith(cleanName + ":")
                || message.contains(" " + cleanName + ":")
                || message.contains("] " + cleanName + ":")
                || message.contains("> " + cleanName + ":");
    }

    private static RescueLocation findRescueLocation(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }

        String lower = message.toLowerCase(Locale.ROOT);
        for (RescueLocation location : RESCUE_LOCATIONS) {
            for (String keyword : location.keywords()) {
                if (lower.contains(keyword)) {
                    return location;
                }
            }
        }

        return null;
    }

    private static RescuePoint chooseNearestRescuePoint(MinecraftClient client, RescueLocation location) {
        RescuePoint[] points = location.points();
        if (points.length == 1 || client == null || client.player == null) {
            return points[0];
        }

        Vec3d playerPos = client.player.getPos();
        RescuePoint best = points[0];
        double bestDistSq = best.squaredDistanceTo(playerPos);

        for (int i = 1; i < points.length; i++) {
            double distSq = points[i].squaredDistanceTo(playerPos);
            if (distSq < bestDistSq) {
                best = points[i];
                bestDistSq = distSq;
            }
        }

        return best;
    }

    private static void tickObservedPlayerLootedTargets(MinecraftClient client, ClientWorld world) {
        if (!AUTO_ENABLED || RESCUE_TARGET != null || client.player == null || world == null) {
            return;
        }

        long[] targets = SUPPLY_CHEST_POSITIONS_SNAPSHOT;
        if (targets.length == 0 || world.getPlayers().size() <= 1) {
            return;
        }

        for (long targetPacked : targets) {
            if (targetPacked == NO_POS || isTemporarilyIgnoredTarget(targetPacked)) {
                continue;
            }

            BlockPos targetPos = BlockPos.fromLong(targetPacked);

            if (!isSupplyChestAtLive(world, targetPos)) {
                continue;
            }

            if (isOtherPlayerInFrontOfChest(client, world, targetPos)) {
                markTargetObservedLooted(targetPacked);
            }
        }
    }

    private static boolean isOtherPlayerInFrontOfChest(MinecraftClient client, ClientWorld world, BlockPos chestPos) {
        Direction front = getChestFrontDirection(world, chestPos);
        if (front == null) {
            return false;
        }

        BlockPos frontPos = chestPos.offset(front);
        Box frontBox = new Box(
                frontPos.getX(),
                chestPos.getY(),
                frontPos.getZ(),
                frontPos.getX() + 1.0,
                chestPos.getY() + CHEST_FRONT_PLAYER_SCAN_HEIGHT,
                frontPos.getZ() + 1.0
        ).expand(CHEST_FRONT_PLAYER_PADDING, 0.0, CHEST_FRONT_PLAYER_PADDING);

        for (AbstractClientPlayerEntity otherPlayer : world.getPlayers()) {
            if (otherPlayer == null || otherPlayer == client.player
                    || otherPlayer.getUuid().equals(client.player.getUuid())
                    || !otherPlayer.isAlive()
                    || otherPlayer.isRemoved() || otherPlayer.isSpectator()) {
                continue;
            }

            if (otherPlayer.getBoundingBox().intersects(frontBox)) {
                return true;
            }
        }

        return false;
    }

    private static Direction getChestFrontDirection(ClientWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!state.isOf(Blocks.CHEST) || !state.contains(ChestBlock.FACING)) {
            return null;
        }

        return state.get(ChestBlock.FACING);
    }

    private static void tickPathRebuild(MinecraftClient client) {
        if (!AUTO_ENABLED) {
            clearPathSnapshot();
            resetAutoPathState();
            return;
        }

        RescueTarget rescueTarget = RESCUE_TARGET;
        boolean rescuePathActive = rescueTarget != null
                && !shouldStopAtRescueTarget(client, rescueTarget, PATH_POSITIONS_SNAPSHOT);

        if (rescueTarget != null && !rescuePathActive) {
            completeRescueAndSwitchToFightOnly(client);
            return;
        }

        if (!rescuePathActive && !canContinuePathfindingWithInventory(client)) {
            return;
        }

        boolean forcePathRebuild = FORCE_PATH_REBUILD;

        if (!forcePathRebuild && pathRefreshCountdown-- > 0) {
            return;
        }

        pathRefreshCountdown = PATH_REFRESH_EVERY_TICKS;
        FORCE_PATH_REBUILD = false;

        if (!forcePathRebuild
                && PATH_TARGET_SNAPSHOT != NO_POS
                && isPathTargetStillValid(client.world, PATH_TARGET_SNAPSHOT)
                && !rescuePathActive
                && isAutoInDropTransition(client, client.world, PATH_POSITIONS_SNAPSHOT)) {
            return;
        }

        if (forcePathRebuild || !shouldReuseCurrentPath(client, client.world)) {
            requestAsyncPathRebuild(client, client.world, SUPPLY_CHEST_POSITIONS_SNAPSHOT);
        }
    }

    private static void tickAutoToggle(MinecraftClient client) {
        while (AUTO_KEY.wasPressed()) {
            AUTO_ENABLED = !AUTO_ENABLED;

            if (AUTO_ENABLED) {
                FIGHT_ONLY_ENABLED = false;
                FORCE_PATH_REBUILD = true;
                pathRefreshCountdown = 0;
            } else {
                clearPathSnapshot();
                resetAutoPathState();
                invalidatePendingPathWork();
                clearRescueTarget();
            }

            releaseAutoMovement(client);
            releaseAutoUse(client);
            resetChestLootState();
            resetAutoInventoryState();
        }

        while (FIGHT_ONLY_KEY.wasPressed()) {
            FIGHT_ONLY_ENABLED = !FIGHT_ONLY_ENABLED;

            if (FIGHT_ONLY_ENABLED) {
                AUTO_ENABLED = false;
            }

            clearPathSnapshot();
            resetAutoPathState();
            invalidatePendingPathWork();
            clearRescueTarget();
            FORCE_PATH_REBUILD = false;
            pathRefreshCountdown = 0;

            releaseAutoMovement(client);
            releaseAutoUse(client);
            resetChestLootState();
            resetAutoInventoryState();
        }
    }

    private static void registerWorldRenderer() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            ClientWorld world = (ClientWorld) context.world();
            if (world == null) {
                return;
            }

            long[] heads = SUPPLY_CHEST_POSITIONS_SNAPSHOT;
            long[] path = PATH_POSITIONS_SNAPSHOT;

            if (heads.length == 0 && path.length == 0) {
                return;
            }

            Vec3d cameraPos = context.camera().getPos();
            MatrixStack matrices = context.matrixStack();

            renderGlowingHeads(world, matrices, context.consumers().getBuffer(XrayRenderLayers.glow()), cameraPos, heads);

            VertexConsumer lines = context.consumers().getBuffer(XrayRenderLayers.lines());

            if (path.length > 0) {
                drawPath(world, matrices, lines, cameraPos, path, PATH_TARGET_SNAPSHOT);
            }

            renderHeadOutlines(world, matrices, lines, cameraPos, heads);
        });
    }

    private static void renderGlowingHeads(
            ClientWorld world,
            MatrixStack matrices,
            VertexConsumer glow,
            Vec3d cameraPos,
            long[] heads
    ) {
        for (long packedPos : heads) {
            BlockPos pos = BlockPos.fromLong(packedPos);
            BlockState state = world.getBlockState(pos);

            if (!isSupplyChestBlock(state)) {
                continue;
            }

            if (cameraPos.squaredDistanceTo(Vec3d.ofCenter(pos)) > GLOW_DISTANCE_SQ) {
                continue;
            }

            VoxelShape shape = state.getOutlineShape(world, pos);
            if (shape == null || shape.isEmpty()) {
                continue;
            }

            Box box = shape.getBoundingBox();
            drawGlowBox(matrices, glow, pos, cameraPos, box, 0.06, 0.06f);
            drawGlowBox(matrices, glow, pos, cameraPos, box, 0.02, 0.14f);
        }
    }

    private static void renderHeadOutlines(
            ClientWorld world,
            MatrixStack matrices,
            VertexConsumer lines,
            Vec3d cameraPos,
            long[] heads
    ) {
        for (long packedPos : heads) {
            BlockPos pos = BlockPos.fromLong(packedPos);
            BlockState state = world.getBlockState(pos);

            if (!isSupplyChestBlock(state)) {
                continue;
            }

            VoxelShape shape = state.getOutlineShape(world, pos);
            if (shape == null || shape.isEmpty()) {
                continue;
            }

            VertexRendering.drawOutline(
                    matrices,
                    lines,
                    shape,
                    pos.getX() - cameraPos.x,
                    pos.getY() - cameraPos.y,
                    pos.getZ() - cameraPos.z,
                    OUTLINE_COLOR
            );
        }
    }

    private static void drawGlowBox(
            MatrixStack matrices,
            VertexConsumer quads,
            BlockPos pos,
            Vec3d camPos,
            Box localBox,
            double expand,
            float alpha
    ) {
        double minX = pos.getX() + localBox.minX - camPos.x - expand;
        double minY = pos.getY() + localBox.minY - camPos.y - expand;
        double minZ = pos.getZ() + localBox.minZ - camPos.z - expand;

        double maxX = pos.getX() + localBox.maxX - camPos.x + expand;
        double maxY = pos.getY() + localBox.maxY - camPos.y + expand;
        double maxZ = pos.getZ() + localBox.maxZ - camPos.z + expand;

        VertexRendering.drawFilledBox(
                matrices,
                quads,
                minX, minY, minZ,
                maxX, maxY, maxZ,
                1.0f, 0.647f, 0.0f,
                alpha
        );
    }

    private static boolean isSupplyChestBlock(BlockState state) {
        return state.isOf(Blocks.CHEST);
    }

    private static void rebuildCache(MinecraftClient client, ClientWorld world) {
        HeadTargetScan scan = HeadTargetScanner.scan(client, world);
        SUPPLY_CHEST_POSITIONS_SNAPSHOT = scan.positions();
        DROPPED_ITEM_POSITIONS_SNAPSHOT = scan.droppedItemPositions();
        ITEM_CENTER_MASS_SNAPSHOT = scan.centerMass();
    }

    private static void refreshLootScanFromCurrentPosition(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) {
            return;
        }

        rebuildCache(client, client.world);
        refreshCountdown = REFRESH_EVERY_TICKS;
    }

    private static void requestAsyncPathRebuild(
            MinecraftClient client,
            ClientWorld world,
            long[] chestTargetsSnapshot
    ) {
        if (PATH_SEARCH_RUNNING) {
            return;
        }

        RescueTarget rescueTarget = RESCUE_TARGET;
        boolean rescuePathActive = rescueTarget != null
                && !shouldStopAtRescueTarget(client, rescueTarget, PATH_POSITIONS_SNAPSHOT);

        if (!AUTO_ENABLED || (!rescuePathActive && !canContinuePathfindingWithInventory(client))) {
            clearPathSnapshot();
            return;
        }

        if (client.player == null) {
            clearPathSnapshot();
            return;
        }

        LivePathWorld liveWorld = new LivePathWorld(world);
        BlockPos start = resolvePathStart(liveWorld, client.player.getPos(), client.player.getBlockPos());

        if (start == null) {
            clearPathSnapshot();
            return;
        }

        LongOpenHashSet ignoredTargets = rescuePathActive ? new LongOpenHashSet() : snapshotIgnoredTargets();
        long[] filteredTargets = rescuePathActive
                ? new long[]{rescueTarget.targetPacked()}
                : filterIgnoredTargets(chestTargetsSnapshot, ignoredTargets);

        long stickyTarget = rescuePathActive ? rescueTarget.targetPacked() : PATH_TARGET_SNAPSHOT;
        if (stickyTarget != NO_POS && ignoredTargets.contains(stickyTarget)) {
            stickyTarget = NO_POS;
        }

        int snapshotRadiusChunks = Math.min(client.options.getViewDistance().getValue(), PATH_SNAPSHOT_RADIUS_CHUNKS);
        long[] zombieDangerCells = snapshotZombieDangerCells(world, start, snapshotRadiusChunks);
        long[] snapshotSupplyChests = rescuePathActive ? new long[0] : filteredTargets;

        SnapshotPathWorld snapshot = SnapshotPathWorld.capture(
                world,
                start,
                snapshotRadiusChunks,
                snapshotSupplyChests,
                zombieDangerCells
        );

        long requestId = PATH_REQUEST_COUNTER.incrementAndGet();
        LATEST_PATH_REQUEST_ID = requestId;
        PATH_SEARCH_RUNNING = true;

        PathJob job = new PathJob(
                requestId,
                snapshot,
                start,
                filteredTargets,
                ITEM_CENTER_MASS_SNAPSHOT,
                rescuePathActive ? new long[0] : DROPPED_ITEM_POSITIONS_SNAPSHOT,
                isMainInventoryFull(client.player.getInventory()),
                client.player.getBlockInteractionRange() + REACH_PADDING,
                client.player.getEyeY() - client.player.getY(),
                stickyTarget,
                ignoredTargets,
                rescuePathActive ? new LongOpenHashSet() : snapshotScoutedRescueTargets(),
                rescuePathActive ? PathMode.RESCUE : PathMode.CHEST,
                rescuePathActive ? rescueTarget : null
        );

        PATH_EXECUTOR.execute(() -> {
            PATH_THREAD_IGNORES.set(job.ignoredTargets());

            try {
                PathMode resultMode = job.mode();
                PathResult result;

                if (job.mode() == PathMode.RESCUE) {
                    result = rebuildPathToRescue(job);
                } else if (job.inventoryFull()) {
                    result = rebuildPathToDroppedItem(job);
                    if (result.path().length > 0 && result.target() != NO_POS) {
                        resultMode = PathMode.ITEM;
                    } else {
                        result = rebuildPathToFallbackRescue(job);
                        if (result.path().length > 0 && result.target() != NO_POS) {
                            resultMode = PathMode.SCOUT;
                        }
                    }
                } else {
                    result = rebuildPathToClosestItem(job);

                    if (result.path().length == 0 || result.target() == NO_POS) {
                        result = rebuildPathToDroppedItem(job);
                        if (result.path().length > 0 && result.target() != NO_POS) {
                            resultMode = PathMode.ITEM;
                        } else {
                            result = rebuildPathToFallbackRescue(job);
                            if (result.path().length > 0 && result.target() != NO_POS) {
                                resultMode = PathMode.SCOUT;
                            }
                        }
                    }
                }

                PENDING_PATH_RESULT = new AsyncPathResult(job.requestId(), resultMode, result.path(), result.target());
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            } finally {
                PATH_THREAD_IGNORES.remove();
                PATH_SEARCH_RUNNING = false;
            }
        });
    }

    private static void invalidatePendingPathWork() {
        LATEST_PATH_REQUEST_ID = PATH_REQUEST_COUNTER.incrementAndGet();
        PENDING_PATH_RESULT = null;
    }

    private static void clearPathSnapshot() {
        PATH_POSITIONS_SNAPSHOT = new long[0];
        PATH_TARGET_SNAPSHOT = NO_POS;
        PATH_MODE_SNAPSHOT = PathMode.CHEST;
        AUTO_OFF_PATH_TICKS = 0;
        resetFinalChestApproachState();
    }

    private static void publishPendingPathResult(MinecraftClient client) {
        AsyncPathResult result = PENDING_PATH_RESULT;
        if (result == null) {
            return;
        }

        PENDING_PATH_RESULT = null;

        if (result.requestId() != LATEST_PATH_REQUEST_ID || client.world == null) {
            return;
        }

        if (result.mode() == PathMode.RESCUE) {
            if (result.target() == NO_POS) {
                PATH_POSITIONS_SNAPSHOT = result.path();
                PATH_TARGET_SNAPSHOT = NO_POS;
                PATH_MODE_SNAPSHOT = PathMode.RESCUE;
                AUTO_OFF_PATH_TICKS = 0;
                return;
            }

            RescueTarget rescueTarget = RESCUE_TARGET;
            if (rescueTarget == null || result.target() != rescueTarget.targetPacked()) {
                return;
            }

            if (shouldStopAtRescueTarget(client, rescueTarget, result.path())) {
                clearPathSnapshot();
                return;
            }
        } else if (result.mode() == PathMode.SCOUT) {
            if (result.target() == NO_POS || result.path().length == 0) {
                return;
            }
        } else if (result.mode() == PathMode.ITEM) {
            if (result.target() == NO_POS
                    || result.path().length == 0
                    || !isDroppedItemTargetStillValid(client.world, result.target())) {
                return;
            }
        } else {
            if (result.target() != NO_POS && !isPathTargetStillValid(client.world, result.target())) {
                return;
            }
        }

        PATH_POSITIONS_SNAPSHOT = result.path();
        PATH_TARGET_SNAPSHOT = result.target();
        PATH_MODE_SNAPSHOT = result.mode();
        AUTO_OFF_PATH_TICKS = 0;
    }

    private static LongOpenHashSet snapshotIgnoredTargets() {
        LongOpenHashSet ignored = new LongOpenHashSet();

        ignored.addAll(RECENTLY_CLICKED_TARGETS.keySet());
        ignored.addAll(FAILED_PATH_TARGETS.keySet());
        return ignored;
    }

    private static LongOpenHashSet snapshotScoutedRescueTargets() {
        LongOpenHashSet ignored = new LongOpenHashSet();
        ignored.addAll(SCOUTED_RESCUE_TARGETS.keySet());
        return ignored;
    }

    private static long[] filterIgnoredTargets(long[] targets, LongOpenHashSet ignored) {
        LongArrayList filtered = new LongArrayList();

        for (long target : targets) {
            if (target != NO_POS && !ignored.contains(target) && !isPoliceStationChestTarget(target)) {
                filtered.add(target);
            }
        }

        return filtered.toLongArray();
    }

    static boolean isPoliceStationChestTarget(long targetPacked) {
        if (targetPacked == NO_POS) {
            return false;
        }

        return isPoliceStationPos(BlockPos.fromLong(targetPacked));
    }

    private static boolean isPoliceStationPos(BlockPos pos) {
        return pos.getX() >= POLICE_STATION_MIN_X
                && pos.getX() <= POLICE_STATION_MAX_X
                && pos.getZ() >= POLICE_STATION_MIN_Z
                && pos.getZ() <= POLICE_STATION_MAX_Z;
    }

    private static boolean pathTouchesPoliceStation(long[] path) {
        if (path == null) {
            return false;
        }

        for (long packed : path) {
            if (packed != NO_POS && isPoliceStationPos(BlockPos.fromLong(packed))) {
                return true;
            }
        }

        return false;
    }

    private static void tickFailedPathTargets() {
        if (FAILED_PATH_TARGETS.isEmpty()) {
            return;
        }

        long[] keys = FAILED_PATH_TARGETS.keySet().toLongArray();

        for (long key : keys) {
            int ticks = FAILED_PATH_TARGETS.get(key) - 1;

            if (ticks <= 0) {
                FAILED_PATH_TARGETS.remove(key);
            } else {
                FAILED_PATH_TARGETS.put(key, ticks);
            }
        }
    }

    private static void tickScoutedRescueTargets() {
        if (SCOUTED_RESCUE_TARGETS.isEmpty()) {
            return;
        }

        long[] keys = SCOUTED_RESCUE_TARGETS.keySet().toLongArray();

        for (long key : keys) {
            int ticks = SCOUTED_RESCUE_TARGETS.get(key) - 1;

            if (ticks <= 0) {
                SCOUTED_RESCUE_TARGETS.remove(key);
            } else {
                SCOUTED_RESCUE_TARGETS.put(key, ticks);
            }
        }
    }

    private static boolean isTemporarilyIgnoredTarget(long targetPacked) {
        if (targetPacked == NO_POS) {
            return true;
        }

        LongOpenHashSet workerIgnores = PATH_THREAD_IGNORES.get();
        if (workerIgnores != null) {
            return workerIgnores.contains(targetPacked);
        }

        return RECENTLY_CLICKED_TARGETS.containsKey(targetPacked) || FAILED_PATH_TARGETS.containsKey(targetPacked);
    }

    private static void tickRecentlyClickedTargets() {
        if (RECENTLY_CLICKED_TARGETS.isEmpty()) {
            return;
        }

        long[] keys = RECENTLY_CLICKED_TARGETS.keySet().toLongArray();

        for (long key : keys) {
            int ticks = RECENTLY_CLICKED_TARGETS.get(key) - 1;

            if (ticks <= 0) {
                RECENTLY_CLICKED_TARGETS.remove(key);
            } else {
                RECENTLY_CLICKED_TARGETS.put(key, ticks);
            }
        }
    }

    private static void discardFailedTarget(long targetPacked) {
        if (targetPacked == NO_POS || PATH_THREAD_IGNORES.get() != null) {
            return;
        }

        FAILED_PATH_TARGETS.put(targetPacked, FAILED_TARGET_IGNORE_TICKS);

        if (targetPacked == PATH_TARGET_SNAPSHOT) {
            clearPathSnapshot();
        }

        if (targetPacked == AUTO_LAST_TARGET) {
            resetAutoPathState();
        }
    }

    private static void discardFailedTargets(long[] targets) {
        for (long target : targets) {
            discardFailedTarget(target);
        }
    }

    private static void resetAutoPathState() {
        AUTO_LAST_TARGET = NO_POS;
        AUTO_LAST_PATH_END = NO_POS;
        AUTO_LAST_PATH_REF = null;
        AUTO_OFF_PATH_TICKS = 0;
        AUTO_WAYPOINT_INDEX = 1;
        resetFinalChestApproachState();
    }

    private static void resetFinalChestApproachState() {
        AUTO_FINAL_APPROACH_TARGET = NO_POS;
        AUTO_FINAL_APPROACH_BEST_EYE_DISTANCE = Double.POSITIVE_INFINITY;
        AUTO_FINAL_APPROACH_STUCK_TICKS = 0;
    }

    private static void clearRescueTarget() {
        RESCUE_TARGET = null;
    }

    private static void completeRescueAndSwitchToFightOnly(MinecraftClient client) {
        AUTO_ENABLED = false;
        FIGHT_ONLY_ENABLED = true;
        clearRescueTarget();
        clearPathSnapshot();
        resetAutoPathState();
        invalidatePendingPathWork();
        FORCE_PATH_REBUILD = false;
        pathRefreshCountdown = 0;

        if (client != null) {
            closeOpenChestForRescue(client);
            releaseAutoMovement(client);
            releaseAutoUse(client);
        }
    }

    private static PathResult rebuildPathToClosestItem(PathJob job) {
        PathWorldView world = job.world();
        long[] targetsSnapshot = job.targetsSnapshot();

        if (targetsSnapshot.length == 0) {
            return new PathResult(new long[0], NO_POS);
        }

        PathSearchBudget budget = new PathSearchBudget(PATHFIND_HARD_LIMIT_NANOS);
        BlockPos start = job.start();

        if (start == null || budget.expired()) {
            return new PathResult(new long[0], NO_POS);
        }

        long stickyTarget = job.stickyTarget();
        if (stickyTarget != NO_POS && !isPathTargetStillValid(world, stickyTarget)) {
            stickyTarget = NO_POS;
        }

        long[] candidates = getClosestTargetsByDistance(start, targetsSnapshot, MAX_TARGET_CANDIDATES);
        candidates = includeStickyTargetCandidate(
                start,
                targetsSnapshot,
                candidates,
                stickyTarget,
                MAX_TARGET_CANDIDATES
        );

        if (candidates.length == 0) {
            return new PathResult(new long[0], NO_POS);
        }

        ScoredPath best = findScoredPathToAnyTarget(
                world,
                start,
                candidates,
                job.reach(),
                job.eyeHeight(),
                budget
        );

        if (best == null) {
            if (!budget.expired()) {
                discardFailedTargets(candidates);
            }
            return new PathResult(new long[0], NO_POS);
        }

        return new PathResult(best.path(), best.target());
    }

    private static PathResult rebuildPathToDroppedItem(PathJob job) {
        PathWorldView world = job.world();
        long[] targetsSnapshot = job.droppedItemTargetsSnapshot();

        if (targetsSnapshot.length == 0) {
            return new PathResult(new long[0], NO_POS);
        }

        PathSearchBudget budget = new PathSearchBudget(PATHFIND_HARD_LIMIT_NANOS);
        BlockPos start = job.start();

        if (start == null || budget.expired()) {
            return new PathResult(new long[0], NO_POS);
        }

        long[] candidates = getClosestTargetsByDistance(start, targetsSnapshot, MAX_TARGET_CANDIDATES);
        if (candidates.length == 0) {
            return new PathResult(new long[0], NO_POS);
        }

        ScoredPath best = findScoredPathToAnyDroppedItem(
                world,
                start,
                candidates,
                budget
        );

        if (best == null) {
            return new PathResult(new long[0], NO_POS);
        }

        return new PathResult(best.path(), best.target());
    }

    private static PathResult rebuildPathToRescue(PathJob job) {
        PathWorldView world = job.world();
        RescueTarget rescueTarget = job.rescueTarget();

        if (rescueTarget == null) {
            return new PathResult(new long[0], NO_POS);
        }

        PathSearchBudget budget = new PathSearchBudget(PATHFIND_HARD_LIMIT_NANOS);
        BlockPos start = job.start();

        if (start == null || budget.expired()) {
            return new PathResult(new long[0], NO_POS);
        }

        return rebuildPathToRescueTarget(world, start, rescueTarget, budget, false);
    }

    private static PathResult rebuildPathToFallbackRescue(PathJob job) {
        PathWorldView world = job.world();
        BlockPos start = job.start();

        if (start == null) {
            return new PathResult(new long[0], NO_POS);
        }

        PathSearchBudget budget = new PathSearchBudget(PATHFIND_HARD_LIMIT_NANOS);
        List<RescueTarget> scoutTargets = collectFallbackRescueTargets(start, job.ignoredScoutTargets());

        for (RescueTarget scoutTarget : scoutTargets) {
            if (budget.expired()) {
                break;
            }

            PathResult result = rebuildPathToRescueTarget(world, start, scoutTarget, budget, true);
            if (result.path().length > 0 && result.target() != NO_POS) {
                return result;
            }
        }

        return new PathResult(new long[0], NO_POS);
    }

    private static PathResult rebuildPathToRescueTarget(
            PathWorldView world,
            BlockPos start,
            RescueTarget rescueTarget,
            PathSearchBudget budget,
            boolean avoidPoliceStation
    ) {
        PathSearchCache cache = new PathSearchCache();
        List<ScoredRescueGoal> rescueGoals = collectRescueGoals(
                world,
                start,
                rescueTarget,
                cache,
                RESCUE_SEARCH_BOUNDS,
                budget,
                avoidPoliceStation
        );

        PathResult result = findPathToClosestScoredRescueGoal(
                world,
                start,
                rescueGoals,
                rescueTarget,
                cache,
                RESCUE_SEARCH_BOUNDS,
                budget
        );

        if ((result.path().length == 0 || result.target() == NO_POS) && !budget.expired()) {
            List<ScoredRescueGoal> approachGoals = collectClosestLoadedRescueApproachGoals(
                    world,
                    start,
                    rescueTarget,
                    cache,
                    RESCUE_SEARCH_BOUNDS,
                    budget,
                    avoidPoliceStation
            );

            result = findPathToClosestScoredRescueGoal(
                    world,
                    start,
                    approachGoals,
                    rescueTarget,
                    cache,
                    RESCUE_SEARCH_BOUNDS,
                    budget
            );
        }

        if (budget.expired() || result.path().length == 0 || result.target() == NO_POS) {
            return new PathResult(new long[0], NO_POS);
        }

        if (avoidPoliceStation
                && !isPoliceStationPos(start)
                && pathTouchesPoliceStation(result.path())) {
            return new PathResult(new long[0], NO_POS);
        }

        long[] smoothedPath = smoothPath(world, result.path(), cache, budget);
        return new PathResult(smoothedPath, rescueTarget.targetPacked());
    }

    private static List<RescueTarget> collectFallbackRescueTargets(
            BlockPos start,
            LongOpenHashSet ignoredScoutTargets
    ) {
        ArrayList<RescueTarget> targets = new ArrayList<>();

        for (RescueLocation location : RESCUE_LOCATIONS) {
            if (isPoliceRescueLocation(location)) {
                continue;
            }

            for (RescuePoint point : location.points()) {
                if (isPoliceRescuePoint(point)) {
                    continue;
                }

                long packed = point.blockPos().asLong();
                if (ignoredScoutTargets != null && ignoredScoutTargets.contains(packed)) {
                    continue;
                }

                targets.add(new RescueTarget(location.name(), point, packed));
            }
        }

        targets.sort(Comparator.comparingDouble(target -> rescueTargetDistanceSq(start, target)));
        return targets;
    }

    private static boolean isPoliceRescueLocation(RescueLocation location) {
        return location != null && "Police".equals(location.name());
    }

    private static boolean isPoliceRescuePoint(RescuePoint point) {
        return point != null && isPoliceStationPos(point.blockPos());
    }

    private static double rescueTargetDistanceSq(BlockPos start, RescueTarget target) {
        RescuePoint point = target.point();
        double dx = start.getX() + 0.5 - point.x();
        double dy = start.getY() - point.y();
        double dz = start.getZ() + 0.5 - point.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static long[] includeStickyTargetCandidate(
            BlockPos start,
            long[] allTargets,
            long[] candidates,
            long stickyTarget,
            int maxTargets
    ) {
        if (stickyTarget == NO_POS || isTemporarilyIgnoredTarget(stickyTarget)) {
            return candidates;
        }

        boolean exists = false;
        for (long target : allTargets) {
            if (target == stickyTarget) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            return candidates;
        }

        for (long candidate : candidates) {
            if (candidate == stickyTarget) {
                return candidates;
            }
        }

        BlockPos target = BlockPos.fromLong(stickyTarget);

        int dx = Math.abs(target.getX() - start.getX());
        int dy = Math.abs(target.getY() - start.getY());
        int dz = Math.abs(target.getZ() - start.getZ());

        if (dx > MAX_BFS_XZ + 8 || dz > MAX_BFS_XZ + 8 || dy > MAX_BFS_Y + 8) {
            return candidates;
        }

        LongArrayList result = new LongArrayList();
        result.add(stickyTarget);

        for (long candidate : candidates) {
            if (candidate == stickyTarget) {
                continue;
            }

            if (result.size() >= maxTargets) {
                break;
            }

            result.add(candidate);
        }

        return result.toLongArray();
    }

    private static ScoredPath findScoredPathToAnyTarget(
            PathWorldView world,
            BlockPos start,
            long[] targetCandidates,
            double reach,
            double eyeHeight,
            PathSearchBudget budget
    ) {
        return findScoredPathToAnyTarget(world, start, targetCandidates, reach, eyeHeight, DEFAULT_SEARCH_BOUNDS, budget);
    }

    private static ScoredPath findScoredPathToAnyTarget(
            PathWorldView world,
            BlockPos start,
            long[] targetCandidates,
            double reach,
            double eyeHeight,
            SearchBounds bounds,
            PathSearchBudget budget
    ) {
        if (targetCandidates.length == 0 || budget.expired()) {
            return null;
        }

        PathSearchCache cache = new PathSearchCache();

        Long2LongOpenHashMap reachableGoals = buildReachableGoals(
                world,
                start,
                targetCandidates,
                reach,
                eyeHeight,
                cache,
                bounds,
                budget
        );

        if (budget.expired() || reachableGoals == null || reachableGoals.isEmpty()) {
            return null;
        }

        PathResult result = findPathAStar(world, start, reachableGoals, targetCandidates, cache, bounds, budget);

        if (budget.expired() || result.path().length == 0 || result.target() == NO_POS) {
            return null;
        }

        long[] rawPath = result.path();
        int cost = pathCost(world, rawPath, cache);
        long[] smoothedPath = smoothPath(world, rawPath, cache, budget);

        return new ScoredPath(smoothedPath, result.target(), cost);
    }

    private static ScoredPath findScoredPathToAnyDroppedItem(
            PathWorldView world,
            BlockPos start,
            long[] targetCandidates,
            PathSearchBudget budget
    ) {
        if (targetCandidates.length == 0 || budget.expired()) {
            return null;
        }

        PathSearchCache cache = new PathSearchCache();
        Long2LongOpenHashMap reachableGoals = buildDroppedItemGoals(
                world,
                start,
                targetCandidates,
                cache,
                DEFAULT_SEARCH_BOUNDS,
                budget
        );

        if (budget.expired() || reachableGoals == null || reachableGoals.isEmpty()) {
            return null;
        }

        PathResult result = findPathAStar(
                world,
                start,
                reachableGoals,
                targetCandidates,
                cache,
                DEFAULT_SEARCH_BOUNDS,
                budget
        );

        if (budget.expired() || result.path().length == 0 || result.target() == NO_POS) {
            return null;
        }

        long[] rawPath = result.path();
        int cost = pathCost(world, rawPath, cache);
        long[] smoothedPath = smoothPath(world, rawPath, cache, budget);

        return new ScoredPath(smoothedPath, result.target(), cost);
    }

    private static int pathCost(PathWorldView world, long[] path, PathSearchCache cache) {
        if (path.length <= 1) {
            return 0;
        }

        int total = 0;

        for (int i = 0; i < path.length - 1; i++) {
            BlockPos from = BlockPos.fromLong(path[i]);
            BlockPos to = BlockPos.fromLong(path[i + 1]);

            total += movementCost(world, from, to, cache);

            if (i > 0) {
                BlockPos previous = BlockPos.fromLong(path[i - 1]);
                total += turnWallPenalty(world, previous, from, to, cache);
            }
        }

        return total;
    }

    private static BlockPos resolvePathStart(PathWorldView world, Vec3d playerPos, BlockPos fallback) {
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        int minY = world.getBottomY() + 1;
        int maxY = world.getTopYInclusive() - 2;

        for (int dy = -2; dy <= 1; dy++) {
            int y = fallback.getY() + dy;
            if (y < minY || y > maxY) {
                continue;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos candidate = fallback.add(dx, dy, dz);

                    if (!isLoaded(world, candidate)) {
                        continue;
                    }

                    double feetY = getStandingFeetY(world, candidate);
                    if (Double.isNaN(feetY)) {
                        continue;
                    }

                    double cx = candidate.getX() + 0.5;
                    double cz = candidate.getZ() + 0.5;

                    double horizontalDx = playerPos.x - cx;
                    double horizontalDz = playerPos.z - cz;
                    double verticalDy = playerPos.y - feetY;

                    if (Math.abs(verticalDy) > 1.75) {
                        continue;
                    }

                    double score = horizontalDx * horizontalDx
                            + horizontalDz * horizontalDz
                            + verticalDy * verticalDy * 0.35
                            + Math.abs(dx) * 0.03
                            + Math.abs(dz) * 0.03
                            + Math.abs(dy) * 0.08;

                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }

        return best;
    }

    private static long[] getClosestTargetsByDistance(BlockPos start, long[] targets, int maxTargets) {
        return getClosestTargetsByDistance(start, targets, maxTargets, NO_POS, MAX_BFS_XZ + 8, MAX_BFS_Y + 8);
    }

    private static long[] getClosestTargetsByDistance(BlockPos start, long[] targets, int maxTargets, long excludedTarget) {
        return getClosestTargetsByDistance(start, targets, maxTargets, excludedTarget, MAX_BFS_XZ + 8, MAX_BFS_Y + 8);
    }

    private static long[] getClosestTargetsByDistance(
            BlockPos start,
            long[] targets,
            int maxTargets,
            long excludedTarget,
            int maxTargetXZ,
            int maxTargetY
    ) {
        return getClosestTargetsByDistance(start, targets, maxTargets, new long[]{excludedTarget}, maxTargetXZ, maxTargetY);
    }

    private static long[] getClosestTargetsByDistance(
            BlockPos start,
            long[] targets,
            int maxTargets,
            long[] excludedTargets,
            int maxTargetXZ,
            int maxTargetY
    ) {
        if (maxTargets <= 0) {
            return new long[0];
        }

        long[] best = new long[maxTargets];
        double[] bestDist = new double[maxTargets];

        Arrays.fill(best, NO_POS);
        Arrays.fill(bestDist, Double.POSITIVE_INFINITY);

        for (long packed : targets) {
            if (isExcludedTarget(packed, excludedTargets) || isTemporarilyIgnoredTarget(packed)) {
                continue;
            }

            BlockPos target = BlockPos.fromLong(packed);

            int dx = Math.abs(target.getX() - start.getX());
            int dy = Math.abs(target.getY() - start.getY());
            int dz = Math.abs(target.getZ() - start.getZ());

            if (dx > maxTargetXZ || dz > maxTargetXZ || dy > maxTargetY) {
                continue;
            }

            double dist = dx * dx + dy * dy + dz * dz;

            for (int i = 0; i < maxTargets; i++) {
                if (isBetterDistanceCandidate(packed, dist, best[i], bestDist[i])) {
                    for (int j = maxTargets - 1; j > i; j--) {
                        bestDist[j] = bestDist[j - 1];
                        best[j] = best[j - 1];
                    }

                    bestDist[i] = dist;
                    best[i] = packed;
                    break;
                }
            }
        }

        LongArrayList result = new LongArrayList();
        for (long packed : best) {
            if (packed != NO_POS) {
                result.add(packed);
            }
        }

        return result.toLongArray();
    }

    private static boolean isExcludedTarget(long packed, long[] excludedTargets) {
        if (excludedTargets == null) {
            return false;
        }

        for (long excluded : excludedTargets) {
            if (excluded != NO_POS && packed == excluded) {
                return true;
            }
        }

        return false;
    }

    private static boolean isBetterDistanceCandidate(long candidatePacked, double candidateDist, long currentPacked, double currentDist) {
        int distCompare = Double.compare(candidateDist, currentDist);

        if (distCompare < 0) {
            return true;
        }

        if (distCompare > 0) {
            return false;
        }

        return currentPacked == NO_POS || Long.compare(candidatePacked, currentPacked) < 0;
    }

    private static boolean shouldReuseCurrentPath(MinecraftClient client, ClientWorld world) {
        if (client.player == null || world == null) {
            return false;
        }

        long[] path = PATH_POSITIONS_SNAPSHOT;
        long targetPacked = PATH_TARGET_SNAPSHOT;

        if (path.length == 0 || targetPacked == NO_POS) {
            return false;
        }

        if (!isPathTargetStillValid(world, targetPacked)) {
            return false;
        }

        if (isPathBlockedByLiveZombie(world, path)) {
            return false;
        }

        RescueTarget rescueTarget = RESCUE_TARGET;
        if (rescueTarget != null
                && targetPacked == rescueTarget.targetPacked()
                && !isPathEndWithinRescueTolerance(world, path, rescueTarget)
                && isPlayerNearPathEnd(client, path, RESCUE_APPROACH_REPATH_DISTANCE_SQ)) {
            return false;
        }

        RescueTarget scoutTarget = PATH_MODE_SNAPSHOT == PathMode.SCOUT ? findRescueTargetByPacked(targetPacked) : null;
        if (scoutTarget != null
                && !isPathEndWithinRescueTolerance(world, path, scoutTarget)
                && isPlayerNearPathEnd(client, path, RESCUE_APPROACH_REPATH_DISTANCE_SQ)) {
            return false;
        }

        return isPlayerNearExistingPath(
                world,
                client.player.getPos(),
                path,
                PATH_REUSE_HORIZONTAL_DISTANCE,
                PATH_REUSE_VERTICAL_TOLERANCE
        );
    }

    private static boolean isPathEndWithinRescueTolerance(ClientWorld world, long[] path, RescueTarget rescueTarget) {
        if (path == null || path.length == 0 || rescueTarget == null) {
            return false;
        }

        BlockPos end = BlockPos.fromLong(path[path.length - 1]);
        return isWithinRescueTolerance(rescueTarget.point(), end, getNodeFeetY(world, end));
    }

    private static boolean isPlayerNearPathEnd(MinecraftClient client, long[] path, double maxHorizontalDistanceSq) {
        if (client.player == null || path == null || path.length == 0) {
            return false;
        }

        BlockPos end = BlockPos.fromLong(path[path.length - 1]);
        Vec3d endCenter = nodeCenter(end);
        return horizontalDistanceSq(client.player.getPos(), endCenter) <= maxHorizontalDistanceSq
                && isPlayerVerticallyNearNode(client, end, AUTO_PATH_VERTICAL_TOLERANCE);
    }

    private static boolean isPathTargetStillValid(ClientWorld world, long targetPacked) {
        if (PATH_MODE_SNAPSHOT == PathMode.ITEM) {
            return isDroppedItemTargetStillValid(world, targetPacked);
        }

        if (isActiveRescueTarget(targetPacked)) {
            return true;
        }

        if (isActiveScoutTarget(targetPacked)) {
            return true;
        }

        if (isTemporarilyIgnoredTarget(targetPacked)) {
            return false;
        }

        return isSupplyChestAtLive(world, BlockPos.fromLong(targetPacked));
    }

    private static boolean isPathTargetStillValid(PathWorldView world, long targetPacked) {
        if (isActiveRescueTarget(targetPacked)) {
            return true;
        }

        if (isActiveScoutTarget(targetPacked)) {
            return true;
        }

        if (isTemporarilyIgnoredTarget(targetPacked)) {
            return false;
        }

        return world.isSupplyChestAt(BlockPos.fromLong(targetPacked));
    }

    private static boolean isActiveRescueTarget(long targetPacked) {
        RescueTarget rescueTarget = RESCUE_TARGET;
        return rescueTarget != null && targetPacked == rescueTarget.targetPacked();
    }

    private static boolean isActiveScoutTarget(long targetPacked) {
        return PATH_MODE_SNAPSHOT == PathMode.SCOUT
                && targetPacked != NO_POS
                && targetPacked == PATH_TARGET_SNAPSHOT;
    }

    private static RescueTarget findRescueTargetByPacked(long targetPacked) {
        if (targetPacked == NO_POS) {
            return null;
        }

        for (RescueLocation location : RESCUE_LOCATIONS) {
            for (RescuePoint point : location.points()) {
                long packed = point.blockPos().asLong();
                if (packed == targetPacked) {
                    return new RescueTarget(location.name(), point, packed);
                }
            }
        }

        return null;
    }

    private static boolean isRescueTargetReached(MinecraftClient client, RescueTarget rescueTarget) {
        if (client == null || client.player == null || rescueTarget == null) {
            return false;
        }

        Vec3d playerPos = client.player.getPos();
        RescuePoint point = rescueTarget.point();

        double dx = playerPos.x - point.x();
        double dz = playerPos.z - point.z();
        double dy = Math.abs(playerPos.y - point.y());

        return dx * dx + dz * dz <= RESCUE_ARRIVAL_TOLERANCE_SQ
                && dy <= RESCUE_VERTICAL_TOLERANCE;
    }

    private static boolean shouldStopAtRescueTarget(MinecraftClient client, RescueTarget rescueTarget, long[] path) {
        if (!isRescueTargetReached(client, rescueTarget)) {
            return false;
        }

        if (client == null || client.player == null || client.world == null || path == null || path.length == 0) {
            return true;
        }

        if (!isPathEndWithinRescueTolerance(client.world, path, rescueTarget)) {
            return false;
        }

        BlockPos end = BlockPos.fromLong(path[path.length - 1]);
        Vec3d endCenter = nodeCenter(end);

        if (horizontalDistanceSq(client.player.getPos(), endCenter) <= WAYPOINT_ADVANCE_DISTANCE_SQ
                && isPlayerVerticallyNearNode(client, end, AUTO_PATH_VERTICAL_TOLERANCE)) {
            return true;
        }

        double endScore = rescueDistanceScore(rescueTarget.point(), end, getNodeFeetY(client.world, end));
        double playerScore = rescueTarget.point().squaredDistanceTo(client.player.getPos());
        return playerScore <= endScore + RESCUE_GOAL_SCORE_EPSILON;
    }

    private static boolean shouldStopAtScoutTarget(MinecraftClient client, long targetPacked, long[] path) {
        RescueTarget scoutTarget = findRescueTargetByPacked(targetPacked);
        if (scoutTarget == null) {
            return false;
        }

        return shouldStopAtRescueTarget(client, scoutTarget, path)
                || isPlayerNearPathEnd(client, path, WAYPOINT_ADVANCE_DISTANCE_SQ);
    }

    private static void completeScoutTarget(MinecraftClient client, long targetPacked) {
        if (targetPacked != NO_POS) {
            SCOUTED_RESCUE_TARGETS.put(targetPacked, SCOUTED_RESCUE_IGNORE_TICKS);
        }

        refreshLootScanFromCurrentPosition(client);
        clearPathSnapshot();
        resetAutoPathState();
        invalidatePendingPathWork();
        FORCE_PATH_REBUILD = true;
        pathRefreshCountdown = 0;

        if (client != null) {
            releaseAutoMovement(client);
        }
    }

    static boolean isSupplyChestAtLive(ClientWorld world, BlockPos pos) {
        if (!isLoaded(world, pos)) {
            return false;
        }

        BlockState state = world.getBlockState(pos);
        return isSupplyChestBlock(state);
    }

    private static boolean isDroppedItemTargetStillValid(ClientWorld world, long targetPacked) {
        if (world == null || targetPacked == NO_POS) {
            return false;
        }

        BlockPos target = BlockPos.fromLong(targetPacked);
        Box searchBox = new Box(
                target.getX() - 1.0,
                target.getY() - 1.0,
                target.getZ() - 1.0,
                target.getX() + 2.0,
                target.getY() + 2.0,
                target.getZ() + 2.0
        );

        return !world.getEntitiesByType(
                TypeFilter.instanceOf(ItemEntity.class),
                searchBox,
                item -> item != null
                        && item.isAlive()
                        && !item.getStack().isEmpty()
                        && isWantedLootStack(item.getStack())
        ).isEmpty();
    }

    private static boolean isSupplyChestAt(PathWorldView world, BlockPos pos) {
        return world.isSupplyChestAt(pos);
    }

    private static boolean isPlayerNearExistingPath(
            ClientWorld world,
            Vec3d playerPos,
            long[] path,
            double maxHorizontalDistance,
            double maxVerticalDistance
    ) {
        if (path == null || path.length == 0) {
            return false;
        }

        double maxHorizontalDistanceSq = maxHorizontalDistance * maxHorizontalDistance;

        if (path.length == 1) {
            BlockPos node = BlockPos.fromLong(path[0]);

            double nodeFeetY = getNodeFeetY(world, node);
            double verticalDistance = Math.abs(playerPos.y - nodeFeetY);

            if (verticalDistance > maxVerticalDistance) {
                return false;
            }

            double dx = node.getX() + 0.5 - playerPos.x;
            double dz = node.getZ() + 0.5 - playerPos.z;

            return dx * dx + dz * dz <= maxHorizontalDistanceSq;
        }

        for (int i = 1; i < path.length; i++) {
            BlockPos aNode = BlockPos.fromLong(path[i - 1]);
            BlockPos bNode = BlockPos.fromLong(path[i]);

            double aY = getNodeFeetY(world, aNode);
            double bY = getNodeFeetY(world, bNode);

            Vec3d a = new Vec3d(aNode.getX() + 0.5, aY, aNode.getZ() + 0.5);
            Vec3d b = new Vec3d(bNode.getX() + 0.5, bY, bNode.getZ() + 0.5);

            double t = horizontalProjectionT(playerPos, a, b);
            double segmentFeetY = a.y + (b.y - a.y) * t;
            double verticalDistance = Math.abs(playerPos.y - segmentFeetY);

            if (verticalDistance > maxVerticalDistance) {
                continue;
            }

            double horizontalDistanceSq = horizontalDistanceToSegmentSq(playerPos, a, b);
            if (horizontalDistanceSq <= maxHorizontalDistanceSq) {
                return true;
            }
        }

        return false;
    }

    private static boolean isPathBlockedByLiveZombie(ClientWorld world, long[] path) {
        if (world == null || path == null || path.length == 0) {
            return false;
        }

        LongOpenHashSet dangerCells = collectZombieDangerCells(world, getPathSearchBox(path));
        if (dangerCells.isEmpty()) {
            return false;
        }

        if (path.length == 1) {
            BlockPos node = BlockPos.fromLong(path[0]);
            return isZombieDangerAt(dangerCells, node.getX() + 0.5, getNodeFeetY(world, node), node.getZ() + 0.5);
        }

        for (int i = 1; i < path.length; i++) {
            BlockPos from = BlockPos.fromLong(path[i - 1]);
            BlockPos to = BlockPos.fromLong(path[i]);

            if (pathSegmentIntersectsZombieDanger(
                    dangerCells,
                    from,
                    to,
                    getNodeFeetY(world, from),
                    getNodeFeetY(world, to)
            )) {
                return true;
            }
        }

        return false;
    }

    private static Box getPathSearchBox(long[] path) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (long packed : path) {
            BlockPos pos = BlockPos.fromLong(packed);

            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        return new Box(minX, minY, minZ, maxX + 1.0, maxY + PLAYER_HEIGHT + 1.0, maxZ + 1.0)
                .expand(ZOMBIE_PATH_SEARCH_PADDING, ZOMBIE_PATH_SEARCH_PADDING, ZOMBIE_PATH_SEARCH_PADDING);
    }

    private static boolean pathSegmentIntersectsZombieDanger(
            LongOpenHashSet dangerCells,
            BlockPos from,
            BlockPos to,
            double fromFeetY,
            double toFeetY
    ) {
        double fromX = from.getX() + 0.5;
        double fromZ = from.getZ() + 0.5;
        double toX = to.getX() + 0.5;
        double toZ = to.getZ() + 0.5;

        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        int samples = Math.max(2, (int) Math.ceil(horizontalDistance / PATH_SMOOTH_SAMPLE_SPACING));

        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            double x = fromX + (toX - fromX) * t;
            double y = fromFeetY + (toFeetY - fromFeetY) * t;
            double z = fromZ + (toZ - fromZ) * t;

            if (isZombieDangerAt(dangerCells, x, y, z)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isZombieDangerAt(LongOpenHashSet dangerCells, double centerX, double feetY, double centerZ) {
        if (Double.isNaN(feetY)) {
            return false;
        }

        BlockPos pos = new BlockPos(
                MathHelper.floor(centerX),
                MathHelper.floor(feetY + PATH_SMOOTH_Y_FLOOR_EPSILON),
                MathHelper.floor(centerZ)
        );

        return dangerCells.contains(pos.asLong());
    }

    private static long[] snapshotZombieDangerCells(ClientWorld world, BlockPos center, int chunkRadius) {
        double horizontalRadius = chunkRadius * 16.0 + 16.0;
        double verticalRadius = MAX_BFS_Y + 16.0;
        Box searchBox = new Box(
                center.getX(), center.getY(), center.getZ(),
                center.getX() + 1.0, center.getY() + 1.0, center.getZ() + 1.0
        ).expand(horizontalRadius, verticalRadius, horizontalRadius);

        return collectZombieDangerCells(world, searchBox).toLongArray();
    }

    private static LongOpenHashSet collectZombieDangerCells(ClientWorld world, Box searchBox) {
        LongOpenHashSet dangerCells = new LongOpenHashSet();
        List<ZombieEntity> zombies = world.getEntitiesByType(
                TypeFilter.instanceOf(ZombieEntity.class),
                searchBox,
                HeadHighlighterController::isPathBlockingZombie
        );

        for (ZombieEntity zombie : zombies) {
            addZombieDangerCells(
                    dangerCells,
                    zombie.getBoundingBox().expand(
                            ZOMBIE_PATH_AVOIDANCE_PADDING,
                            ZOMBIE_PATH_VERTICAL_PADDING,
                            ZOMBIE_PATH_AVOIDANCE_PADDING
                    )
            );
        }

        return dangerCells;
    }

    private static boolean isPathBlockingZombie(ZombieEntity zombie) {
        return zombie != null
                && zombie.isAlive()
                && zombie.canHit()
                && !zombie.isSpectator();
    }

    private static void addZombieDangerCells(LongOpenHashSet dangerCells, Box dangerBox) {
        int minX = MathHelper.floor(dangerBox.minX);
        int maxX = MathHelper.floor(dangerBox.maxX - COLLISION_EPSILON);
        int minY = MathHelper.floor(dangerBox.minY - ZOMBIE_PATH_VERTICAL_PADDING);
        int maxY = MathHelper.floor(dangerBox.maxY + ZOMBIE_PATH_VERTICAL_PADDING);
        int minZ = MathHelper.floor(dangerBox.minZ);
        int maxZ = MathHelper.floor(dangerBox.maxZ - COLLISION_EPSILON);

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);
                    dangerCells.add(pos.asLong());
                }
            }
        }
    }

    private static double getNodeFeetY(ClientWorld world, BlockPos node) {
        double standingY = getStandingFeetY(world, node);
        return Double.isNaN(standingY) ? node.getY() : standingY;
    }

    private static boolean isPlayerVerticallyNearNode(MinecraftClient client, BlockPos node, double maxVerticalDistance) {
        if (client.player == null || client.world == null) {
            return false;
        }

        double nodeFeetY = getNodeFeetY(client.world, node);
        return Math.abs(client.player.getY() - nodeFeetY) <= maxVerticalDistance;
    }

    private static PathResult findPathAStar(
            PathWorldView world,
            BlockPos start,
            Long2LongOpenHashMap reachableGoals,
            long[] targetCandidates,
            PathSearchCache cache,
            SearchBounds bounds,
            PathSearchBudget budget
    ) {
        Long2IntOpenHashMap gScore = new Long2IntOpenHashMap();
        gScore.defaultReturnValue(Integer.MAX_VALUE);

        PriorityQueue<AStarNode> open = new PriorityQueue<>(
                Comparator
                        .comparingInt(AStarNode::f)
                        .thenComparingInt(AStarNode::h)
                        .thenComparingLong(AStarNode::packed)
        );

        long startPacked = start.asLong();
        int startH = heuristicToTargets(start, targetCandidates);

        cache.parent.put(startPacked, NO_POS);
        gScore.put(startPacked, 0);
        open.add(new AStarNode(startPacked, 0, startH, startH));

        int visited = 0;

        while (!open.isEmpty() && visited++ < MAX_BFS_NODES) {
            if ((visited & 31) == 0 && budget.expired()) {
                return new PathResult(new long[0], NO_POS);
            }

            AStarNode node = open.poll();

            if (node.g() != gScore.get(node.packed())) {
                continue;
            }

            long touchedTarget = reachableGoals.get(node.packed());
            if (touchedTarget != NO_POS) {
                return reconstructPath(node.packed(), touchedTarget, cache.parent);
            }

            addWalkingNeighborsAStar(
                    world,
                    start,
                    BlockPos.fromLong(node.packed()),
                    node.packed(),
                    node.g(),
                    open,
                    gScore,
                    cache,
                    targetCandidates,
                    bounds,
                    budget
            );
        }

        return new PathResult(new long[0], NO_POS);
    }

    private static int heuristicToTargets(BlockPos pos, long[] targets) {
        int best = Integer.MAX_VALUE;

        for (long packed : targets) {
            BlockPos target = BlockPos.fromLong(packed);

            int dx = Math.abs(pos.getX() - target.getX());
            int dy = Math.abs(pos.getY() - target.getY());
            int dz = Math.abs(pos.getZ() - target.getZ());

            int minXZ = Math.min(dx, dz);
            int maxXZ = Math.max(dx, dz);

            int horizontal = 14 * minXZ + 10 * (maxXZ - minXZ);
            int vertical = Math.max(0, dy - 2) * 10;
            int h = horizontal + vertical - 40;

            if (h < best) {
                best = h;
            }
        }

        return best == Integer.MAX_VALUE ? 0 : Math.max(0, best);
    }

    private static void addWalkingNeighborsAStar(
            PathWorldView world,
            BlockPos start,
            BlockPos current,
            long currentPacked,
            int currentG,
            PriorityQueue<AStarNode> open,
            Long2IntOpenHashMap gScore,
            PathSearchCache cache,
            long[] targetCandidates,
            SearchBounds bounds,
            PathSearchBudget budget
    ) {
        for (int[] offset : WALK_OFFSETS) {
            if (budget.expired()) {
                return;
            }

            int dx = offset[0];
            int dz = offset[1];

            BlockPos flat = current.add(dx, 0, dz);

            if (tryAStarNeighbor(world, start, current, flat, currentPacked, currentG, open, gScore, cache, targetCandidates, bounds, budget)) {
                continue;
            }

            BlockPos up = flat.up();
            if (tryAStarNeighbor(world, start, current, up, currentPacked, currentG, open, gScore, cache, targetCandidates, bounds, budget)) {
                continue;
            }

            for (int drop = 1; drop <= MAX_DROP; drop++) {
                if (budget.expired()) {
                    return;
                }

                BlockPos down = flat.down(drop);

                if (!inSearchBounds(world, start, down, bounds) || !isLoadedCached(world, down, cache)) {
                    break;
                }

                if (tryAStarNeighbor(world, start, current, down, currentPacked, currentG, open, gScore, cache, targetCandidates, bounds, budget)) {
                    break;
                }

                if (!isFallColumnClear(world, down)) {
                    break;
                }
            }
        }
    }

    private static boolean tryAStarNeighbor(
            PathWorldView world,
            BlockPos start,
            BlockPos current,
            BlockPos next,
            long currentPacked,
            int currentG,
            PriorityQueue<AStarNode> open,
            Long2IntOpenHashMap gScore,
            PathSearchCache cache,
            long[] targetCandidates,
            SearchBounds bounds,
            PathSearchBudget budget
    ) {
        if (budget.expired()) {
            return false;
        }

        if (!inSearchBounds(world, start, next, bounds)) {
            return false;
        }

        long nextPacked = next.asLong();

        if (!isLoadedCached(world, next, cache)) {
            return false;
        }

        if (!isWalkableCached(world, next, cache)) {
            return false;
        }

        if (world.isZombieDangerAt(next)) {
            return false;
        }

        if (!canMoveBetweenCached(world, current, next, cache)) {
            return false;
        }

        int moveCost = movementCost(world, current, next, cache);

        long previousPacked = cache.parent.get(currentPacked);
        if (previousPacked != NO_POS) {
            moveCost += turnWallPenalty(world, BlockPos.fromLong(previousPacked), current, next, cache);
        }

        int newG = currentG + moveCost;

        if (newG >= gScore.get(nextPacked)) {
            return false;
        }

        gScore.put(nextPacked, newG);
        cache.parent.put(nextPacked, currentPacked);

        int h = heuristicToTargets(next, targetCandidates);
        open.add(new AStarNode(nextPacked, newG, h, newG + h));

        return true;
    }

    private static int movementCost(PathWorldView world, BlockPos from, BlockPos to, PathSearchCache cache) {
        double fromY = getStandingFeetYCached(world, from, cache);
        double toY = getStandingFeetYCached(world, to, cache);

        if (Double.isNaN(fromY) || Double.isNaN(toY)) {
            return 1000;
        }

        double rise = toY - fromY;
        int cost;

        if (rise > STEP_HEIGHT) {
            cost = MOVE_COST_JUMP_UP;
        } else if (rise > 0.05) {
            cost = MOVE_COST_STEP_UP;
        } else if (rise < -0.5) {
            cost = MOVE_COST_DROP;
        } else {
            cost = MOVE_COST_FLAT;
        }

        if (isDiagonalMove(from, to)) {
            cost = (int) Math.round(cost * 1.41421356237);
        }

        if (isWaterAtFeetNode(world, from) || isWaterAtFeetNode(world, to)) {
            cost += WATER_WALK_PENALTY;
        }

        int wallPenalty = nodeWallPenalty(world, to, cache);
        cost += wallPenalty;

        if (wallPenalty > 0 && isDiagonalMove(from, to)) {
            cost += PATH_DIAGONAL_WALL_PENALTY;
        }

        return cost;
    }

    private static int nodeWallPenalty(PathWorldView world, BlockPos feetPos, PathSearchCache cache) {
        double feetY = getStandingFeetYCached(world, feetPos, cache);
        if (Double.isNaN(feetY)) {
            return 0;
        }

        double x = feetPos.getX() + 0.5;
        double z = feetPos.getZ() + 0.5;

        return isPlayerBoxClearAt(world, x, feetY, z, PATH_NODE_WALL_PROBE_CLEARANCE)
                ? 0
                : PATH_NODE_WALL_PENALTY;
    }

    private static int turnWallPenalty(
            PathWorldView world,
            BlockPos previous,
            BlockPos current,
            BlockPos next,
            PathSearchCache cache
    ) {
        int dx1 = Integer.compare(current.getX() - previous.getX(), 0);
        int dz1 = Integer.compare(current.getZ() - previous.getZ(), 0);
        int dx2 = Integer.compare(next.getX() - current.getX(), 0);
        int dz2 = Integer.compare(next.getZ() - current.getZ(), 0);

        if ((dx1 == 0 && dz1 == 0) || (dx2 == 0 && dz2 == 0)) {
            return 0;
        }

        if (dx1 == dx2 && dz1 == dz2) {
            return 0;
        }

        int currentPenalty = nodeWallPenalty(world, current, cache);
        int nextPenalty = nodeWallPenalty(world, next, cache);

        return currentPenalty > 0 || nextPenalty > 0
                ? PATH_TURN_WALL_PENALTY
                : 0;
    }

    private static PathResult reconstructPath(long goalPacked, long targetPacked, Long2LongOpenHashMap parent) {
        LongArrayList reversed = new LongArrayList();

        long cursor = goalPacked;
        while (cursor != NO_POS) {
            reversed.add(cursor);
            cursor = parent.get(cursor);
        }

        long[] path = new long[reversed.size()];
        for (int i = 0; i < path.length; i++) {
            path[i] = reversed.getLong(path.length - 1 - i);
        }

        return new PathResult(path, targetPacked);
    }

    private static long[] smoothPath(PathWorldView world, long[] rawPath, PathSearchCache cache, PathSearchBudget budget) {
        if (rawPath == null || rawPath.length <= 2 || budget.expired()) {
            return rawPath;
        }

        LongArrayList smoothed = new LongArrayList();
        int index = 0;

        smoothed.add(rawPath[0]);

        while (index < rawPath.length - 1) {
            if (budget.expired()) {
                return rawPath;
            }

            int best = index + 1;
            int max = Math.min(rawPath.length - 1, index + PATH_SMOOTH_MAX_LOOKAHEAD);

            for (int candidate = max; candidate > index + 1; candidate--) {
                if (canSmoothPathRange(world, rawPath, index, candidate, cache, budget)) {
                    best = candidate;
                    break;
                }
            }

            smoothed.add(rawPath[best]);
            index = best;
        }

        return smoothed.toLongArray();
    }

    private static boolean canSmoothPathRange(
            PathWorldView world,
            long[] path,
            int fromIndex,
            int toIndex,
            PathSearchCache cache,
            PathSearchBudget budget
    ) {
        if (toIndex <= fromIndex + 1) {
            return true;
        }

        if (budget.expired()) {
            return false;
        }

        BlockPos from = BlockPos.fromLong(path[fromIndex]);
        BlockPos to = BlockPos.fromLong(path[toIndex]);

        double fromY = getStandingFeetYCached(world, from, cache);
        double toY = getStandingFeetYCached(world, to, cache);

        if (Double.isNaN(fromY) || Double.isNaN(toY)) {
            return false;
        }

        if (Math.abs(toY - fromY) > PATH_SMOOTH_MAX_HEIGHT_DELTA) {
            return false;
        }

        double previousY = fromY;

        for (int i = fromIndex + 1; i <= toIndex; i++) {
            if ((i & 3) == 0 && budget.expired()) {
                return false;
            }

            BlockPos node = BlockPos.fromLong(path[i]);
            double y = getStandingFeetYCached(world, node, cache);

            if (Double.isNaN(y)) {
                return false;
            }

            double delta = y - previousY;

            if (delta < -PATH_SMOOTH_MAX_DOWN_STEP) {
                return false;
            }

            if (Math.abs(delta) > PATH_SMOOTH_MAX_HEIGHT_DELTA) {
                return false;
            }

            previousY = y;
        }

        return isStraightWalkSegmentClear(world, from, to, fromY, toY, budget);
    }

    private static boolean isStraightWalkSegmentClear(
            PathWorldView world,
            BlockPos from,
            BlockPos to,
            double fromFeetY,
            double toFeetY,
            PathSearchBudget budget
    ) {
        double fromX = from.getX() + 0.5;
        double fromZ = from.getZ() + 0.5;
        double toX = to.getX() + 0.5;
        double toZ = to.getZ() + 0.5;

        double dx = toX - fromX;
        double dz = toZ - fromZ;

        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance < 0.0001) {
            return true;
        }

        int samples = Math.max(2, (int) Math.ceil(horizontalDistance / PATH_SMOOTH_SAMPLE_SPACING));
        samples = Math.min(samples, PATH_SMOOTH_MAX_SAMPLES);

        for (int i = 1; i <= samples; i++) {
            if ((i & 7) == 0 && budget.expired()) {
                return false;
            }

            double t = i / (double) samples;

            double x = fromX + (toX - fromX) * t;
            double y = fromFeetY + (toFeetY - fromFeetY) * t;
            double z = fromZ + (toZ - fromZ) * t;

            if (playerBoxTouchesHandOpenableDoor(world, x, y, z)) {
                return false;
            }

            if (trapdoorIntersectsPlayerBoxAt(world, x, y, z)) {
                return false;
            }

            if (!isPlayerBoxClearAt(world, x, y, z, PATH_SMOOTH_EXTRA_WALL_CLEARANCE)) {
                return false;
            }

            BlockPos feetCell = new BlockPos(
                    MathHelper.floor(x),
                    MathHelper.floor(y + PATH_SMOOTH_Y_FLOOR_EPSILON),
                    MathHelper.floor(z)
            );

            if (!isLoaded(world, feetCell)) {
                return false;
            }

            if (world.isZombieDangerAt(feetCell)) {
                return false;
            }

            double actualFeetY = getStandingFeetY(world, feetCell);

            if (Double.isNaN(actualFeetY)) {
                return false;
            }

            if (Math.abs(actualFeetY - y) > PATH_SMOOTH_FEET_Y_TOLERANCE) {
                return false;
            }
        }

        return true;
    }

    private static boolean playerBoxTouchesHandOpenableDoor(PathWorldView world, double centerX, double feetY, double centerZ) {
        Box playerBox = makePlayerCollisionBox(centerX, feetY, centerZ).expand(PATH_SMOOTH_DOOR_SCAN_PADDING);

        int minX = MathHelper.floor(playerBox.minX);
        int maxX = MathHelper.floor(playerBox.maxX - COLLISION_EPSILON);
        int minY = MathHelper.floor(playerBox.minY);
        int maxY = MathHelper.floor(playerBox.maxY - COLLISION_EPSILON);
        int minZ = MathHelper.floor(playerBox.minZ);
        int maxZ = MathHelper.floor(playerBox.maxZ - COLLISION_EPSILON);

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);

                    if (!isLoaded(world, pos)) {
                        return true;
                    }

                    if (isHandOpenableDoor(world.getBlockState(pos))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean inSearchBounds(PathWorldView world, BlockPos start, BlockPos pos, SearchBounds bounds) {
        int dx = Math.abs(pos.getX() - start.getX());
        int dy = Math.abs(pos.getY() - start.getY());
        int dz = Math.abs(pos.getZ() - start.getZ());

        if (dx > bounds.maxXz() || dz > bounds.maxXz() || dy > bounds.maxY()) {
            return false;
        }

        int y = pos.getY();
        return y >= world.getBottomY() + 1 && y <= world.getTopYInclusive() - 2;
    }

    private static boolean isLoaded(ClientWorld world, BlockPos pos) {
        return world.getChunkManager().getChunk(
                pos.getX() >> 4,
                pos.getZ() >> 4,
                ChunkStatus.FULL,
                false
        ) != null;
    }

    private static boolean isLoaded(PathWorldView world, BlockPos pos) {
        return world.isLoaded(pos);
    }

    private static boolean isLoadedCached(PathWorldView world, BlockPos pos, PathSearchCache cache) {
        long chunkKey = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);

        if (cache.loadedChunks.contains(chunkKey)) {
            return true;
        }

        if (cache.unloadedChunks.contains(chunkKey)) {
            return false;
        }

        boolean loaded = world.isLoaded(pos);

        if (loaded) {
            cache.loadedChunks.add(chunkKey);
        } else {
            cache.unloadedChunks.add(chunkKey);
        }

        return loaded;
    }

    private static boolean isWalkableCached(PathWorldView world, BlockPos feetPos, PathSearchCache cache) {
        return !Double.isNaN(getStandingFeetYCached(world, feetPos, cache));
    }

    private static double getStandingFeetY(ClientWorld world, BlockPos feetPos) {
        return getStandingFeetY(new LivePathWorld(world), feetPos);
    }

    private static double getStandingFeetYCached(PathWorldView world, BlockPos feetPos, PathSearchCache cache) {
        long key = feetPos.asLong();
        double cached = cache.feetY.get(key);

        if (cached != UNKNOWN_FEET_Y) {
            return cached;
        }

        double value = getStandingFeetY(world, feetPos);
        cache.feetY.put(key, value);
        return value;
    }

    private static boolean canMoveBetweenCached(PathWorldView world, BlockPos from, BlockPos to, PathSearchCache cache) {
        double fromFeetY = getStandingFeetYCached(world, from, cache);
        double toFeetY = getStandingFeetYCached(world, to, cache);

        if (Double.isNaN(fromFeetY) || Double.isNaN(toFeetY)) {
            return false;
        }

        return canMoveBetweenWithFeetY(world, from, to, fromFeetY, toFeetY);
    }

    private static boolean canMoveBetweenWithFeetY(
            PathWorldView world,
            BlockPos from,
            BlockPos to,
            double fromFeetY,
            double toFeetY
    ) {
        double rise = toFeetY - fromFeetY;

        if (rise > JUMP_HEIGHT) {
            return false;
        }

        if (rise > STEP_HEIGHT) {
            if (!hasJumpHeadroom(world, from, fromFeetY)) {
                return false;
            }

            if (isJumpBlockedByStairHighFace(world, from, to, toFeetY)) {
                return false;
            }
        }

        if (isDiagonalMove(from, to) && Math.abs(rise) > HEIGHT_CHANGE_EPSILON) {
            return false;
        }

        if (rise < -HEIGHT_CHANGE_EPSILON) {
            return isDropMoveClear(world, from, to, fromFeetY, toFeetY);
        }

        if (hasTrapdoorCollisionAlongMove(world, from, to, fromFeetY, toFeetY)) {
            return false;
        }

        return isMovementSweepClear(world, from, to, fromFeetY, toFeetY);
    }

    private static boolean isJumpBlockedByStairHighFace(PathWorldView world, BlockPos from, BlockPos to, double toFeetY) {
        int supportY = MathHelper.floor(toFeetY - COLLISION_EPSILON);
        BlockPos supportPos = new BlockPos(to.getX(), supportY, to.getZ());

        if (!isLoaded(world, supportPos)) {
            return true;
        }

        BlockState state = world.getBlockState(supportPos);

        if (!(state.getBlock() instanceof StairsBlock)) {
            return false;
        }

        VoxelShape shape = state.getCollisionShape(world, supportPos);
        if (shape == null || shape.isEmpty()) {
            return false;
        }

        double localLandingY = toFeetY - supportPos.getY();

        if (localLandingY < 1.0 - 0.02) {
            return false;
        }

        int dx = Integer.compare(to.getX() - from.getX(), 0);
        int dz = Integer.compare(to.getZ() - from.getZ(), 0);

        if (dx == 0 && dz == 0) {
            return false;
        }

        if (dx != 0 && dz != 0) {
            return stairLandingFaceBlocked(shape, localLandingY, dx, true)
                    && stairLandingFaceBlocked(shape, localLandingY, dz, false);
        }

        return dx != 0
                ? stairLandingFaceBlocked(shape, localLandingY, dx, true)
                : stairLandingFaceBlocked(shape, localLandingY, dz, false);
    }

    private static boolean stairLandingFaceBlocked(VoxelShape shape, double localLandingY, int directionIntoBlock, boolean xAxis) {
        double eps = COLLISION_EPSILON * 8.0;

        for (Box box : shape.getBoundingBoxes()) {
            if (Math.abs(box.maxY - localLandingY) > 0.02) {
                continue;
            }

            if (xAxis) {
                if (directionIntoBlock > 0 && box.minX <= eps) {
                    return true;
                }

                if (directionIntoBlock < 0 && box.maxX >= 1.0 - eps) {
                    return true;
                }
            } else {
                if (directionIntoBlock > 0 && box.minZ <= eps) {
                    return true;
                }

                if (directionIntoBlock < 0 && box.maxZ >= 1.0 - eps) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isDiagonalMove(BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());

        return dx == 1 && dz == 1;
    }

    private static boolean isDropMoveClear(PathWorldView world, BlockPos from, BlockPos to, double fromFeetY, double toFeetY) {
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());

        if (dx + dz != 1) {
            return false;
        }

        double fromX = from.getX() + 0.5;
        double fromZ = from.getZ() + 0.5;
        double toX = to.getX() + 0.5;
        double toZ = to.getZ() + 0.5;

        for (int i = 1; i <= MOVE_SWEEP_SAMPLES; i++) {
            double t = i / (double) MOVE_SWEEP_SAMPLES;

            double x = fromX + (toX - fromX) * t;
            double z = fromZ + (toZ - fromZ) * t;

            if (!isPlayerBoxClearAt(world, x, fromFeetY, z)) {
                return false;
            }

            if (trapdoorIntersectsPlayerBoxAt(world, x, fromFeetY, z)) {
                return false;
            }
        }

        double dropDistance = fromFeetY - toFeetY;
        int fallSamples = Math.max(2, (int) Math.ceil(dropDistance * 2.0));

        for (int i = 0; i <= fallSamples; i++) {
            double t = i / (double) fallSamples;
            double y = fromFeetY + (toFeetY - fromFeetY) * t;

            if (!isPlayerBoxClearAt(world, toX, y, toZ)) {
                return false;
            }

            if (trapdoorIntersectsPlayerBoxAt(world, toX, y, toZ)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isMovementSweepClear(PathWorldView world, BlockPos from, BlockPos to, double fromFeetY, double toFeetY) {
        double fromX = from.getX() + 0.5;
        double fromZ = from.getZ() + 0.5;
        double toX = to.getX() + 0.5;
        double toZ = to.getZ() + 0.5;

        double checkFeetY = Math.max(fromFeetY, toFeetY);

        for (int i = 1; i < MOVE_SWEEP_SAMPLES; i++) {
            double t = i / (double) MOVE_SWEEP_SAMPLES;

            double x = fromX + (toX - fromX) * t;
            double z = fromZ + (toZ - fromZ) * t;

            if (!isPlayerBoxClearAt(world, x, checkFeetY, z)) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasJumpHeadroom(PathWorldView world, BlockPos from, double fromFeetY) {
        return isPlayerBoxClearAt(
                world,
                from.getX() + 0.5,
                fromFeetY + JUMP_HEADROOM_RISE,
                from.getZ() + 0.5
        );
    }

    private static boolean isFallColumnClear(PathWorldView world, BlockPos pos) {
        return isPlayerBoxClearAt(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static double getStandingFeetY(PathWorldView world, BlockPos feetPos) {
        if (!isLoaded(world, feetPos)) {
            return Double.NaN;
        }

        double centerX = feetPos.getX() + 0.5;
        double centerZ = feetPos.getZ() + 0.5;

        double best = Double.NaN;

        BlockState sameState = world.getBlockState(feetPos);
        boolean sameCellHasTrapdoor = sameState.getBlock() instanceof TrapdoorBlock;

        BlockPos below = feetPos.down();
        if (isLoaded(world, below)) {
            BlockState belowState = world.getBlockState(below);

            if (!sameCellHasTrapdoor && !isHandOpenableDoor(belowState)) {
                VoxelShape belowShape = belowState.getCollisionShape(world, below);

                for (Box localBox : belowShape.getBoundingBoxes()) {
                    if (!overlapsPlayerFootprint(localBox, below, centerX, centerZ)) {
                        continue;
                    }

                    if (localBox.maxY >= 1.0 - COLLISION_EPSILON && localBox.maxY <= 1.0 + COLLISION_EPSILON) {
                        best = tryStandingCandidate(
                                world,
                                centerX,
                                below.getY() + localBox.maxY,
                                centerZ,
                                best
                        );
                    }
                }
            }
        }

        if (!isHandOpenableDoor(sameState)) {
            if (sameCellHasTrapdoor && !isClosedBottomTrapdoor(sameState)) {
                return best;
            }

            VoxelShape sameShape = sameState.getCollisionShape(world, feetPos);

            for (Box localBox : sameShape.getBoundingBoxes()) {
                if (!overlapsPlayerFootprint(localBox, feetPos, centerX, centerZ)) {
                    continue;
                }

                if (localBox.maxY > COLLISION_EPSILON && localBox.maxY < 1.0 - COLLISION_EPSILON) {
                    best = tryStandingCandidate(
                            world,
                            centerX,
                            feetPos.getY() + localBox.maxY,
                            centerZ,
                            best
                    );
                }
            }
        }

        return best;
    }

    private static double tryStandingCandidate(
            PathWorldView world,
            double centerX,
            double feetY,
            double centerZ,
            double currentBest
    ) {
        if (!isPlayerBoxClearAt(world, centerX, feetY, centerZ)) {
            return currentBest;
        }

        return Double.isNaN(currentBest) || feetY > currentBest ? feetY : currentBest;
    }

    private static boolean overlapsPlayerFootprint(Box localBox, BlockPos shapePos, double centerX, double centerZ) {
        double minX = shapePos.getX() + localBox.minX;
        double maxX = shapePos.getX() + localBox.maxX;
        double minZ = shapePos.getZ() + localBox.minZ;
        double maxZ = shapePos.getZ() + localBox.maxZ;

        return maxX > centerX - PLAYER_HALF_WIDTH
                && minX < centerX + PLAYER_HALF_WIDTH
                && maxZ > centerZ - PLAYER_HALF_WIDTH
                && minZ < centerZ + PLAYER_HALF_WIDTH;
    }

    private static boolean isPlayerBoxClearAt(PathWorldView world, double centerX, double feetY, double centerZ) {
        return isPlayerBoxClearAt(world, centerX, feetY, centerZ, 0.0);
    }

    private static boolean isPlayerBoxClearAt(
            PathWorldView world,
            double centerX,
            double feetY,
            double centerZ,
            double extraHorizontalClearance
    ) {
        Box playerBox = makePlayerCollisionBox(centerX, feetY, centerZ, extraHorizontalClearance);

        int minX = MathHelper.floor(playerBox.minX);
        int maxX = MathHelper.floor(playerBox.maxX - COLLISION_EPSILON);
        int minY = MathHelper.floor(playerBox.minY);
        int maxY = MathHelper.floor(playerBox.maxY - COLLISION_EPSILON);
        int minZ = MathHelper.floor(playerBox.minZ);
        int maxZ = MathHelper.floor(playerBox.maxZ - COLLISION_EPSILON);

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);

                    if (!isLoaded(world, pos)) {
                        return false;
                    }

                    BlockState state = world.getBlockState(pos);

                    if (isHandOpenableDoor(state)) {
                        continue;
                    }

                    VoxelShape collision = state.getCollisionShape(world, pos);
                    if (collision.isEmpty()) {
                        continue;
                    }

                    for (Box localBox : collision.getBoundingBoxes()) {
                        Box worldBox = localBox.offset(pos.getX(), pos.getY(), pos.getZ());

                        if (worldBox.intersects(playerBox)) {
                            return false;
                        }
                    }
                }
            }
        }

        return true;
    }

    private static boolean isHandOpenableDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock && DoorBlock.canOpenByHand(state);
    }

    private static boolean isClosedBottomTrapdoor(BlockState state) {
        return state.getBlock() instanceof TrapdoorBlock
                && state.contains(TrapdoorBlock.OPEN)
                && state.contains(TrapdoorBlock.HALF)
                && !state.get(TrapdoorBlock.OPEN)
                && state.get(TrapdoorBlock.HALF) == BlockHalf.BOTTOM;
    }

    private static boolean isWaterAtFeetNode(PathWorldView world, BlockPos feetPos) {
        if (!isLoaded(world, feetPos)) {
            return false;
        }

        return world.getFluidState(feetPos).isOf(Fluids.WATER)
                || world.getFluidState(feetPos).isOf(Fluids.FLOWING_WATER);
    }

    private static boolean hasTrapdoorCollisionAlongMove(
            PathWorldView world,
            BlockPos from,
            BlockPos to,
            double fromFeetY,
            double toFeetY
    ) {
        double fromX = from.getX() + 0.5;
        double fromZ = from.getZ() + 0.5;
        double toX = to.getX() + 0.5;
        double toZ = to.getZ() + 0.5;

        for (int i = 0; i <= TRAPDOOR_MOVE_SWEEP_SAMPLES; i++) {
            double t = i / (double) TRAPDOOR_MOVE_SWEEP_SAMPLES;

            double x = fromX + (toX - fromX) * t;
            double y = fromFeetY + (toFeetY - fromFeetY) * t;
            double z = fromZ + (toZ - fromZ) * t;

            if (trapdoorIntersectsPlayerBoxAt(world, x, y, z)) {
                return true;
            }
        }

        return false;
    }

    private static boolean trapdoorIntersectsPlayerBoxAt(PathWorldView world, double centerX, double feetY, double centerZ) {
        Box playerBox = makePlayerCollisionBox(centerX, feetY, centerZ);

        int minX = MathHelper.floor(playerBox.minX);
        int maxX = MathHelper.floor(playerBox.maxX - COLLISION_EPSILON);
        int minY = MathHelper.floor(playerBox.minY);
        int maxY = MathHelper.floor(playerBox.maxY - COLLISION_EPSILON);
        int minZ = MathHelper.floor(playerBox.minZ);
        int maxZ = MathHelper.floor(playerBox.maxZ - COLLISION_EPSILON);

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);

                    if (!isLoaded(world, pos)) {
                        return true;
                    }

                    BlockState state = world.getBlockState(pos);

                    if (!(state.getBlock() instanceof TrapdoorBlock)) {
                        continue;
                    }

                    VoxelShape collision = state.getCollisionShape(world, pos);
                    if (collision.isEmpty()) {
                        continue;
                    }

                    for (Box localBox : collision.getBoundingBoxes()) {
                        Box worldBox = localBox.offset(pos.getX(), pos.getY(), pos.getZ());

                        if (worldBox.intersects(playerBox)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private static Box makePlayerCollisionBox(double centerX, double feetY, double centerZ) {
        return makePlayerCollisionBox(centerX, feetY, centerZ, 0.0);
    }

    private static Box makePlayerCollisionBox(
            double centerX,
            double feetY,
            double centerZ,
            double extraHorizontalClearance
    ) {
        double halfWidth = PLAYER_HALF_WIDTH + Math.max(0.0, extraHorizontalClearance);

        return new Box(
                centerX - halfWidth,
                feetY + COLLISION_EPSILON,
                centerZ - halfWidth,
                centerX + halfWidth,
                feetY + PLAYER_HEIGHT - COLLISION_EPSILON,
                centerZ + halfWidth
        );
    }

    private static void drawPath(
            ClientWorld world,
            MatrixStack matrices,
            VertexConsumer lines,
            Vec3d camPos,
            long[] path,
            long targetPacked
    ) {
        if (path.length == 0) {
            return;
        }

        for (int i = 0; i < path.length - 1; i++) {
            BlockPos a = BlockPos.fromLong(path[i]);
            BlockPos b = BlockPos.fromLong(path[i + 1]);

            drawPathSegment(world, matrices, lines, camPos, a, b);
        }

        if (targetPacked != NO_POS) {
            BlockPos last = BlockPos.fromLong(path[path.length - 1]);
            BlockPos target = BlockPos.fromLong(targetPacked);

            double lastY = getRenderFeetY(world, last);

            drawLine(
                    matrices,
                    lines,
                    last.getX() + 0.5 - camPos.x,
                    lastY + 0.12 - camPos.y,
                    last.getZ() + 0.5 - camPos.z,
                    target.getX() + 0.5 - camPos.x,
                    target.getY() + 0.5 - camPos.y,
                    target.getZ() + 0.5 - camPos.z,
                    PATH_TARGET_COLOR
            );
        }
    }

    private static void drawPathSegment(
            ClientWorld world,
            MatrixStack matrices,
            VertexConsumer lines,
            Vec3d camPos,
            BlockPos from,
            BlockPos to
    ) {
        double fromY = getRenderFeetY(world, from);
        double toY = getRenderFeetY(world, to);

        double fromX = from.getX() + 0.5;
        double fromZ = from.getZ() + 0.5;
        double toX = to.getX() + 0.5;
        double toZ = to.getZ() + 0.5;

        boolean cardinal = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ()) == 1;
        boolean drop = cardinal && toY < fromY - HEIGHT_CHANGE_EPSILON;

        if (drop) {
            drawLine(
                    matrices,
                    lines,
                    fromX - camPos.x,
                    fromY + 0.12 - camPos.y,
                    fromZ - camPos.z,
                    toX - camPos.x,
                    fromY + 0.12 - camPos.y,
                    toZ - camPos.z,
                    PATH_COLOR
            );

            drawLine(
                    matrices,
                    lines,
                    toX - camPos.x,
                    fromY + 0.12 - camPos.y,
                    toZ - camPos.z,
                    toX - camPos.x,
                    toY + 0.12 - camPos.y,
                    toZ - camPos.z,
                    PATH_COLOR
            );

            return;
        }

        drawLine(
                matrices,
                lines,
                fromX - camPos.x,
                fromY + 0.12 - camPos.y,
                fromZ - camPos.z,
                toX - camPos.x,
                toY + 0.12 - camPos.y,
                toZ - camPos.z,
                PATH_COLOR
        );
    }

    private static double getRenderFeetY(ClientWorld world, BlockPos pos) {
        double feetY = getStandingFeetY(world, pos);
        return Double.isNaN(feetY) ? pos.getY() : feetY;
    }

    private static void drawLine(
            MatrixStack matrices,
            VertexConsumer lines,
            double x1,
            double y1,
            double z1,
            double x2,
            double y2,
            double z2,
            int argb
    ) {
        float dx = (float) (x2 - x1);
        float dy = (float) (y2 - y1);
        float dz = (float) (z2 - z1);

        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 0.0001f) {
            return;
        }

        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;

        MatrixStack.Entry entry = matrices.peek();

        lines.vertex(entry, (float) x1, (float) y1, (float) z1)
                .color(argb)
                .normal(entry, nx, ny, nz);

        lines.vertex(entry, (float) x2, (float) y2, (float) z2)
                .color(argb)
                .normal(entry, nx, ny, nz);
    }

    private static List<ScoredRescueGoal> collectRescueGoals(
            PathWorldView world,
            BlockPos start,
            RescueTarget rescueTarget,
            PathSearchCache cache,
            SearchBounds bounds,
            PathSearchBudget budget,
            boolean avoidPoliceStation
    ) {
        ArrayList<ScoredRescueGoal> goals = new ArrayList<>();

        BlockPos targetBlock = rescueTarget.point().blockPos();

        int checked = 0;
        for (int dx = -RESCUE_GOAL_SCAN_XZ; dx <= RESCUE_GOAL_SCAN_XZ; dx++) {
            for (int dy = -RESCUE_GOAL_SCAN_Y; dy <= RESCUE_GOAL_SCAN_Y; dy++) {
                for (int dz = -RESCUE_GOAL_SCAN_XZ; dz <= RESCUE_GOAL_SCAN_XZ; dz++) {
                    if ((++checked & 63) == 0 && budget.expired()) {
                        return goals;
                    }

                    BlockPos feet = targetBlock.add(dx, dy, dz);
                    if (!isUsableRescueGoal(
                            world,
                            start,
                            feet,
                            rescueTarget,
                            cache,
                            bounds,
                            avoidPoliceStation
                    )) {
                        continue;
                    }

                    double feetY = getStandingFeetYCached(world, feet, cache);
                    goals.add(new ScoredRescueGoal(
                            feet.asLong(),
                            rescueDistanceScore(rescueTarget.point(), feet, feetY)
                    ));
                }
            }
        }

        goals.sort(Comparator
                .comparingDouble(ScoredRescueGoal::score)
                .thenComparingLong(ScoredRescueGoal::packed));

        return goals;
    }

    private static PathResult findPathToClosestScoredRescueGoal(
            PathWorldView world,
            BlockPos start,
            List<ScoredRescueGoal> scoredGoals,
            RescueTarget rescueTarget,
            PathSearchCache cache,
            SearchBounds bounds,
            PathSearchBudget budget
    ) {
        if (scoredGoals == null || scoredGoals.isEmpty() || budget.expired()) {
            return new PathResult(new long[0], NO_POS);
        }

        int index = 0;
        while (index < scoredGoals.size() && !budget.expired()) {
            Long2LongOpenHashMap goals = new Long2LongOpenHashMap();
            goals.defaultReturnValue(NO_POS);

            double score = scoredGoals.get(index).score();
            int nextIndex = index;

            while (nextIndex < scoredGoals.size()
                    && Math.abs(scoredGoals.get(nextIndex).score() - score) <= RESCUE_GOAL_SCORE_EPSILON) {
                goals.put(scoredGoals.get(nextIndex).packed(), rescueTarget.targetPacked());
                nextIndex++;
            }

            cache.parent.clear();
            PathResult result = findPathAStar(
                    world,
                    start,
                    goals,
                    new long[]{rescueTarget.targetPacked()},
                    cache,
                    bounds,
                    budget
            );

            if (!budget.expired() && result.path().length > 0 && result.target() != NO_POS) {
                return result;
            }

            index = nextIndex;
        }

        return new PathResult(new long[0], NO_POS);
    }

    private static boolean isUsableRescueGoal(
            PathWorldView world,
            BlockPos start,
            BlockPos feet,
            RescueTarget rescueTarget,
            PathSearchCache cache,
            SearchBounds bounds,
            boolean avoidPoliceStation
    ) {
        if (avoidPoliceStation && isPoliceStationPos(feet)) {
            return false;
        }

        if (!inSearchBounds(world, start, feet, bounds)) {
            return false;
        }

        if (!isLoadedCached(world, feet, cache) || !isWalkableCached(world, feet, cache)) {
            return false;
        }

        double feetY = getStandingFeetYCached(world, feet, cache);
        return isWithinRescueTolerance(rescueTarget.point(), feet, feetY);
    }

    private static List<ScoredRescueGoal> collectClosestLoadedRescueApproachGoals(
            PathWorldView world,
            BlockPos start,
            RescueTarget rescueTarget,
            PathSearchCache cache,
            SearchBounds bounds,
            PathSearchBudget budget,
            boolean avoidPoliceStation
    ) {
        long[] bestGoals = new long[RESCUE_APPROACH_GOAL_CANDIDATES];
        double[] bestScores = new double[RESCUE_APPROACH_GOAL_CANDIDATES];

        Arrays.fill(bestGoals, NO_POS);
        Arrays.fill(bestScores, Double.POSITIVE_INFINITY);

        double startFeetY = getStandingFeetYCached(world, start, cache);
        double startScore = rescueDistanceScore(rescueTarget.point(), start, startFeetY);

        int scanXZ = Math.min(bounds.maxXz(), RESCUE_APPROACH_SCAN_XZ);
        int scanY = Math.min(bounds.maxY(), RESCUE_APPROACH_SCAN_Y);
        int checked = 0;

        for (int dx = -scanXZ; dx <= scanXZ; dx += RESCUE_APPROACH_SCAN_STEP) {
            for (int dz = -scanXZ; dz <= scanXZ; dz += RESCUE_APPROACH_SCAN_STEP) {
                for (int dy = -scanY; dy <= scanY; dy++) {
                    if ((++checked & 255) == 0 && budget.expired()) {
                        return scoredRescueGoalList(bestGoals, bestScores);
                    }

                    BlockPos feet = start.add(dx, dy, dz);
                    if (avoidPoliceStation && isPoliceStationPos(feet)) {
                        continue;
                    }

                    if (!inSearchBounds(world, start, feet, bounds)) {
                        continue;
                    }

                    if (!isLoadedCached(world, feet, cache) || !isWalkableCached(world, feet, cache)) {
                        continue;
                    }

                    double feetY = getStandingFeetYCached(world, feet, cache);
                    double score = rescueDistanceScore(rescueTarget.point(), feet, feetY);

                    if (score >= startScore) {
                        continue;
                    }

                    insertRescueApproachGoal(feet.asLong(), score, bestGoals, bestScores);
                }
            }
        }

        return scoredRescueGoalList(bestGoals, bestScores);
    }

    private static List<ScoredRescueGoal> scoredRescueGoalList(long[] bestGoals, double[] bestScores) {
        ArrayList<ScoredRescueGoal> goals = new ArrayList<>();
        for (int i = 0; i < bestGoals.length; i++) {
            if (bestGoals[i] != NO_POS) {
                goals.add(new ScoredRescueGoal(bestGoals[i], bestScores[i]));
            }
        }

        return goals;
    }

    private static void insertRescueApproachGoal(long packed, double score, long[] bestGoals, double[] bestScores) {
        for (long existing : bestGoals) {
            if (existing == packed) {
                return;
            }
        }

        for (int i = 0; i < bestGoals.length; i++) {
            if (score >= bestScores[i]) {
                continue;
            }

            for (int j = bestGoals.length - 1; j > i; j--) {
                bestGoals[j] = bestGoals[j - 1];
                bestScores[j] = bestScores[j - 1];
            }

            bestGoals[i] = packed;
            bestScores[i] = score;
            return;
        }
    }

    private static boolean isWithinRescueTolerance(RescuePoint point, BlockPos feet, double feetY) {
        if (Double.isNaN(feetY)) {
            return false;
        }

        double dx = feet.getX() + 0.5 - point.x();
        double dz = feet.getZ() + 0.5 - point.z();
        double dy = Math.abs(feetY - point.y());

        return dx * dx + dz * dz <= RESCUE_ARRIVAL_TOLERANCE_SQ
                && dy <= RESCUE_VERTICAL_TOLERANCE;
    }

    private static double rescueDistanceScore(RescuePoint point, BlockPos feet, double feetY) {
        if (Double.isNaN(feetY)) {
            return Double.POSITIVE_INFINITY;
        }

        double dx = feet.getX() + 0.5 - point.x();
        double dy = feetY - point.y();
        double dz = feet.getZ() + 0.5 - point.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static Long2LongOpenHashMap buildReachableGoals(
            PathWorldView world,
            BlockPos start,
            long[] targetsSnapshot,
            double reach,
            double eyeHeight,
            PathSearchCache cache,
            SearchBounds bounds,
            PathSearchBudget budget
    ) {
        Long2LongOpenHashMap goals = new Long2LongOpenHashMap();
        goals.defaultReturnValue(NO_POS);

        int scanXZ = (int) Math.ceil(reach + 1.0);
        int scanY = (int) Math.ceil(reach + 2.0);

        int checked = 0;

        for (long targetPacked : targetsSnapshot) {
            if (isTemporarilyIgnoredTarget(targetPacked)) {
                continue;
            }

            if ((++checked & 63) == 0 && budget.expired()) {
                return null;
            }

            BlockPos target = BlockPos.fromLong(targetPacked);

            if (!isLoadedCached(world, target, cache)) {
                continue;
            }

            if (!isSupplyChestAt(world, target)) {
                continue;
            }

            for (int dx = -scanXZ; dx <= scanXZ; dx++) {
                for (int dy = -scanY; dy <= scanY; dy++) {
                    for (int dz = -scanXZ; dz <= scanXZ; dz++) {
                        if ((++checked & 63) == 0 && budget.expired()) {
                            return null;
                        }

                        BlockPos feet = target.add(dx, dy, dz);

                        if (!inSearchBounds(world, start, feet, bounds)) {
                            continue;
                        }

                        if (!isLoadedCached(world, feet, cache)) {
                            continue;
                        }

                        if (!isWalkableCached(world, feet, cache)) {
                            continue;
                        }

                        if (!canReachTargetFromFeetCached(world, feet, target, reach, eyeHeight, cache)) {
                            continue;
                        }

                        long feetPacked = feet.asLong();

                        if (!goals.containsKey(feetPacked)) {
                            goals.put(feetPacked, targetPacked);
                        }
                    }
                }
            }
        }

        return goals;
    }

    private static Long2LongOpenHashMap buildDroppedItemGoals(
            PathWorldView world,
            BlockPos start,
            long[] targetsSnapshot,
            PathSearchCache cache,
            SearchBounds bounds,
            PathSearchBudget budget
    ) {
        Long2LongOpenHashMap goals = new Long2LongOpenHashMap();
        goals.defaultReturnValue(NO_POS);

        int checked = 0;

        for (long targetPacked : targetsSnapshot) {
            if ((++checked & 63) == 0 && budget.expired()) {
                return null;
            }

            BlockPos target = BlockPos.fromLong(targetPacked);

            for (int dx = -DROPPED_ITEM_GOAL_SCAN_XZ; dx <= DROPPED_ITEM_GOAL_SCAN_XZ; dx++) {
                for (int dy = -DROPPED_ITEM_GOAL_SCAN_Y; dy <= DROPPED_ITEM_GOAL_SCAN_Y; dy++) {
                    for (int dz = -DROPPED_ITEM_GOAL_SCAN_XZ; dz <= DROPPED_ITEM_GOAL_SCAN_XZ; dz++) {
                        if ((++checked & 63) == 0 && budget.expired()) {
                            return null;
                        }

                        BlockPos feet = target.add(dx, dy, dz);

                        if (!inSearchBounds(world, start, feet, bounds)) {
                            continue;
                        }

                        if (!isLoadedCached(world, feet, cache) || !isWalkableCached(world, feet, cache)) {
                            continue;
                        }

                        if (!canPickupDroppedItemFromFeet(world, feet, target, cache)) {
                            continue;
                        }

                        long feetPacked = feet.asLong();
                        if (!goals.containsKey(feetPacked)) {
                            goals.put(feetPacked, targetPacked);
                        }
                    }
                }
            }
        }

        return goals;
    }

    private static boolean canPickupDroppedItemFromFeet(
            PathWorldView world,
            BlockPos feet,
            BlockPos target,
            PathSearchCache cache
    ) {
        double feetY = getStandingFeetYCached(world, feet, cache);
        if (Double.isNaN(feetY)) {
            return false;
        }

        double dx = feet.getX() + 0.5 - (target.getX() + 0.5);
        double dz = feet.getZ() + 0.5 - (target.getZ() + 0.5);
        double dy = Math.abs(feetY - target.getY());

        return dx * dx + dz * dz <= DROPPED_ITEM_PICKUP_DISTANCE_SQ
                && dy <= DROPPED_ITEM_PICKUP_VERTICAL_TOLERANCE;
    }

    private static boolean canReachTargetFromFeetCached(
            PathWorldView world,
            BlockPos feetPos,
            BlockPos targetPos,
            double reach,
            double eyeHeight,
            PathSearchCache cache
    ) {
        double feetY = getStandingFeetYCached(world, feetPos, cache);
        if (Double.isNaN(feetY)) {
            return false;
        }

        return canReachTargetFromFeetY(world, feetPos, targetPos, reach, eyeHeight, feetY);
    }

    private static boolean canReachTargetFromFeetY(
            PathWorldView world,
            BlockPos feetPos,
            BlockPos targetPos,
            double reach,
            double eyeHeight,
            double feetY
    ) {
        Vec3d eye = new Vec3d(
                feetPos.getX() + 0.5,
                feetY + eyeHeight,
                feetPos.getZ() + 0.5
        );

        Vec3d targetPoint = getTargetReachPoint(world, targetPos);

        if (eye.squaredDistanceTo(targetPoint) > reach * reach) {
            return false;
        }

        return hasReachLine(world, eye, targetPoint, targetPos);
    }

    private static Vec3d getTargetReachPoint(BlockView world, BlockPos targetPos) {
        BlockState state = world.getBlockState(targetPos);
        VoxelShape shape = state.getOutlineShape(world, targetPos);

        if (shape != null && !shape.isEmpty()) {
            Box bb = shape.getBoundingBox();

            return new Vec3d(
                    targetPos.getX() + (bb.minX + bb.maxX) * 0.5,
                    targetPos.getY() + (bb.minY + bb.maxY) * 0.5,
                    targetPos.getZ() + (bb.minZ + bb.maxZ) * 0.5
            );
        }

        return Vec3d.ofCenter(targetPos);
    }

    private static boolean hasReachLine(BlockView world, Vec3d from, Vec3d to, BlockPos targetPos) {
        BlockHitResult hit = world.raycast(new RaycastContext(
                from,
                to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                ShapeContext.absent()
        ));

        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }

        return hit.getBlockPos().equals(targetPos);
    }

    private static void tickAutoMode(MinecraftClient client, ClientWorld world) {
        if (client.player == null || client.interactionManager == null || world == null) {
            releaseAutoMovement(client);
            return;
        }

        RescueTarget rescueTarget = RESCUE_TARGET;
        boolean rescueTargetSet = AUTO_ENABLED && rescueTarget != null;

        if (rescueTargetSet && closeOpenChestForRescue(client)) {
            releaseAutoMovement(client);
            return;
        }

        if (AUTO_ENABLED && !rescueTargetSet && tryLootOpenChest(client, world)) {
            releaseAutoMovement(client);
            return;
        }

        if (tryManageAutoInventory(client, world)) {
            releaseAutoMovement(client);
            return;
        }

        if (client.currentScreen != null) {
            releaseAutoMovement(client);
            return;
        }

        if (autoClickCooldown > 0) {
            autoClickCooldown--;
        }

        if (autoShootCooldown > 0) {
            autoShootCooldown--;
        }

        if (survivalUseCooldown > 0) {
            survivalUseCooldown--;
        }

        if (autoArmorEquipCooldown > 0) {
            autoArmorEquipCooldown--;
        }

        if (tryAutoEquipArmor(client)) {
            return;
        }

        if (tryUseSurvivalItem(client, world)) {
            return;
        }

        if (tryShootVisibleZombie(client, world)) {
            return;
        }

        if (!AUTO_ENABLED) {
            releaseAutoMovement(client);
            return;
        }

        if (rescueTargetSet && shouldStopAtRescueTarget(client, rescueTarget, PATH_POSITIONS_SNAPSHOT)) {
            completeRescueAndSwitchToFightOnly(client);
            return;
        }

        if (!rescueTargetSet && !canContinuePathfindingWithInventory(client)) {
            releaseAutoMovement(client);
            return;
        }

        long targetPacked = PATH_TARGET_SNAPSHOT;
        long[] path = PATH_POSITIONS_SNAPSHOT;
        PathMode pathMode = PATH_MODE_SNAPSHOT;

        if (targetPacked == NO_POS || path.length == 0) {
            releaseAutoMovement(client);
            return;
        }

        if (pathMode == PathMode.SCOUT && shouldStopAtScoutTarget(client, targetPacked, path)) {
            completeScoutTarget(client, targetPacked);
            return;
        }

        if (rescueTargetSet && targetPacked != rescueTarget.targetPacked()) {
            releaseAutoMovement(client);
            FORCE_PATH_REBUILD = true;
            pathRefreshCountdown = 0;
            return;
        }

        if (!isPathTargetStillValid(world, targetPacked)) {
            releaseAutoMovement(client);
            FORCE_PATH_REBUILD = true;
            pathRefreshCountdown = 0;
            return;
        }

        if (isPathBlockedByLiveZombie(world, path)) {
            releaseAutoMovement(client);
            FORCE_PATH_REBUILD = true;
            pathRefreshCountdown = 0;
            return;
        }

        if (shouldRepathForOffRoutePlayer(client, world, path)) {
            releaseAutoMovement(client);
            FORCE_PATH_REBUILD = true;
            pathRefreshCountdown = 0;
            return;
        }

        if (pathMode == PathMode.CHEST && !rescueTargetSet) {
            long reachableTargetPacked = findReachableTargetFromPlayer(client, world, targetPacked);
            if (reachableTargetPacked != NO_POS) {
                BlockPos reachableTargetPos = BlockPos.fromLong(reachableTargetPacked);

                releaseAutoMovement(client);
                lookAt(client, getTargetReachPoint(world, reachableTargetPos));

                if (autoClickCooldown <= 0) {
                    rightClickTarget(client, world, reachableTargetPos);
                    autoClickCooldown = AUTO_CLICK_COOLDOWN_TICKS;
                }

                return;
            }
        }

        BlockPos next = getNextAutoWaypoint(client, path, targetPacked);
        if (next == null) {
            releaseAutoMovement(client);
            return;
        }

        if (tryCloseOpenDoorBlockingMove(client, world, next)) {
            return;
        }

        if (tryUseDoorOnPath(client, world, path, next)) {
            return;
        }

        if (pathMode == PathMode.CHEST
                && !rescueTargetSet
                && tryDriveCloserToFinalTarget(client, world, path, targetPacked)) {
            return;
        }

        Vec3d aimPoint = getAutoAimPoint(client, path, next);
        float yawError = facePointYawOnlyPrecise(client, aimPoint);
        driveTowardWaypoint(client, world, path, yawError);
    }

    private static boolean shouldRepathForOffRoutePlayer(MinecraftClient client, ClientWorld world, long[] path) {
        if (client.player == null || world == null || path == null || path.length == 0) {
            AUTO_OFF_PATH_TICKS = 0;
            return false;
        }

        if (isAutoInDropTransition(client, world, path)) {
            AUTO_OFF_PATH_TICKS = 0;
            return false;
        }

        Vec3d playerPos = client.player.getPos();
        boolean tooFarFromPath = !isPlayerNearExistingPath(
                world,
                playerPos,
                path,
                AUTO_OFF_PATH_REPATH_DISTANCE,
                AUTO_PATH_VERTICAL_TOLERANCE
        );
        boolean collidingAwayFromPath = client.player.horizontalCollision && !isPlayerNearExistingPath(
                world,
                playerPos,
                path,
                AUTO_COLLIDING_OFF_PATH_REPATH_DISTANCE,
                AUTO_PATH_VERTICAL_TOLERANCE
        );

        if (!tooFarFromPath && !collidingAwayFromPath) {
            AUTO_OFF_PATH_TICKS = 0;
            return false;
        }

        AUTO_OFF_PATH_TICKS++;
        return AUTO_OFF_PATH_TICKS >= AUTO_OFF_PATH_REPATH_TICKS;
    }

    private static boolean tryDriveCloserToFinalTarget(
            MinecraftClient client,
            ClientWorld world,
            long[] path,
            long targetPacked
    ) {
        if (client.player == null || world == null || path == null || path.length == 0 || targetPacked == NO_POS) {
            resetFinalChestApproachState();
            return false;
        }

        if (AUTO_WAYPOINT_INDEX < path.length - 1) {
            resetFinalChestApproachState();
            return false;
        }

        BlockPos finalWaypoint = BlockPos.fromLong(path[path.length - 1]);
        if (!isPlayerVerticallyNearNode(client, finalWaypoint, AUTO_PATH_VERTICAL_TOLERANCE)) {
            resetFinalChestApproachState();
            return false;
        }

        Vec3d playerPos = client.player.getPos();
        if (horizontalDistanceSq(playerPos, nodeCenter(finalWaypoint))
                > AUTO_FINAL_CHEST_APPROACH_WAYPOINT_DISTANCE_SQ) {
            resetFinalChestApproachState();
            return false;
        }

        BlockPos targetPos = BlockPos.fromLong(targetPacked);
        Vec3d targetPoint = getTargetReachPoint(world, targetPos);
        double reach = client.player.getBlockInteractionRange() + REACH_PADDING;
        Vec3d eye = client.player.getEyePos();
        double eyeDistance = eye.distanceTo(targetPoint);

        if (eyeDistance > reach + AUTO_FINAL_CHEST_APPROACH_EXTRA_REACH) {
            resetFinalChestApproachState();
            return false;
        }

        if (eyeDistance <= reach && hasReachLine(world, eye, targetPoint, targetPos)) {
            resetFinalChestApproachState();
            return false;
        }

        if (horizontalDistanceSq(playerPos, targetPoint) <= AUTO_FINAL_CHEST_APPROACH_MIN_HORIZONTAL_DISTANCE_SQ) {
            handleFinalChestApproachProgress(client, targetPacked, eyeDistance);
            releaseAutoMovement(client);
            return true;
        }

        if (!handleFinalChestApproachProgress(client, targetPacked, eyeDistance)) {
            return true;
        }

        float yawError = facePointYawOnlyPrecise(client, targetPoint);
        driveTowardWaypoint(client, world, path, yawError);
        return true;
    }

    private static boolean handleFinalChestApproachProgress(
            MinecraftClient client,
            long targetPacked,
            double eyeDistance
    ) {
        if (AUTO_FINAL_APPROACH_TARGET != targetPacked) {
            AUTO_FINAL_APPROACH_TARGET = targetPacked;
            AUTO_FINAL_APPROACH_BEST_EYE_DISTANCE = eyeDistance;
            AUTO_FINAL_APPROACH_STUCK_TICKS = 0;
            return true;
        }

        if (eyeDistance + AUTO_FINAL_CHEST_APPROACH_PROGRESS_EPSILON
                < AUTO_FINAL_APPROACH_BEST_EYE_DISTANCE) {
            AUTO_FINAL_APPROACH_BEST_EYE_DISTANCE = eyeDistance;
            AUTO_FINAL_APPROACH_STUCK_TICKS = 0;
            return true;
        }

        AUTO_FINAL_APPROACH_STUCK_TICKS++;
        if (AUTO_FINAL_APPROACH_STUCK_TICKS < AUTO_FINAL_CHEST_APPROACH_STUCK_TICKS) {
            return true;
        }

        discardFailedTarget(targetPacked);
        resetFinalChestApproachState();
        releaseAutoMovement(client);
        FORCE_PATH_REBUILD = true;
        pathRefreshCountdown = 0;
        return false;
    }

    private static boolean closeOpenChestForRescue(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> handledScreen)) {
            return false;
        }

        if (!(handledScreen.getScreenHandler() instanceof GenericContainerScreenHandler)) {
            return false;
        }

        if (client.player != null) {
            client.player.closeHandledScreen();
        }

        resetChestLootState();
        CHEST_LOOT_TARGET = NO_POS;
        return true;
    }

    private static boolean tryLootOpenChest(MinecraftClient client, ClientWorld world) {
        if (!(client.currentScreen instanceof HandledScreen<?> handledScreen)) {
            resetChestLootState();
            return false;
        }

        if (!(handledScreen.getScreenHandler() instanceof GenericContainerScreenHandler handler)) {
            resetChestLootState();
            return false;
        }

        if (client.player == null || client.interactionManager == null) {
            return true;
        }

        if (isZombieVeryClose(client, world)) {
            closeChestLootForCombat(client);
            return false;
        }

        if (!canContinuePathfindingWithInventory(client)) {
            client.player.closeHandledScreen();
            resetChestLootState();
            return true;
        }

        if (CHEST_LOOT_SYNC_ID != handler.syncId) {
            CHEST_LOOT_SYNC_ID = handler.syncId;
            CHEST_LOOT_TICKS = 0;
            CHEST_LOOT_EMPTY_TICKS = 0;
            CHEST_LOOT_SAW_CONTENTS = false;
        }

        CHEST_LOOT_TICKS++;

        if (CHEST_LOOT_MOVE_COOLDOWN > 0) {
            CHEST_LOOT_MOVE_COOLDOWN--;
            return true;
        }

        if (tryManageOpenHandledInventory(client, handler, world, true)) {
            CHEST_LOOT_MOVE_COOLDOWN = INVENTORY_MOVE_COOLDOWN_TICKS;
            return true;
        }

        int chestSlots = Math.min(handler.getRows() * 9, handler.slots.size());
        boolean hasLootableContents = hasLootableContainerContents(handler, chestSlots);

        if (hasLootableContents) {
            CHEST_LOOT_SAW_CONTENTS = true;
            CHEST_LOOT_EMPTY_TICKS = 0;
        } else {
            CHEST_LOOT_EMPTY_TICKS++;

            boolean shouldCloseBlankChest = !CHEST_LOOT_SAW_CONTENTS
                    && CHEST_LOOT_TICKS >= CHEST_LOOT_BLANK_CLOSE_TICKS;
            boolean shouldCloseLootedChest = CHEST_LOOT_SAW_CONTENTS
                    && CHEST_LOOT_EMPTY_TICKS >= CHEST_LOOT_EMPTY_CLOSE_TICKS;

            if (shouldCloseBlankChest || shouldCloseLootedChest) {
                client.player.closeHandledScreen();
                resetChestLootState();
                FORCE_PATH_REBUILD = true;
                pathRefreshCountdown = 0;
            }

            return true;
        }

        if (CHEST_LOOT_TICKS < CHEST_LOOT_SETTLE_TICKS) {
            return true;
        }

        int moved = 0;

        for (int slotIndex = 0; slotIndex < chestSlots && moved < CHEST_QUICK_MOVE_PER_TICK; slotIndex++) {
            Slot slot = handler.getSlot(slotIndex);
            if (!slot.hasStack()) {
                continue;
            }

            if (!isWantedLootStack(slot.getStack())) {
                continue;
            }

            client.interactionManager.clickSlot(
                    handler.syncId,
                    slot.id,
                    0,
                    SlotActionType.QUICK_MOVE,
                    client.player
            );
            CHEST_LOOT_MOVE_COOLDOWN = CHEST_LOOT_MOVE_INTERVAL_TICKS;
            moved++;
            return true; // do only one move, then wait for client/server slot sync
        }

        return true;
    }

    private static void resetChestLootState() {
        boolean hadOpenChestSession = CHEST_LOOT_SYNC_ID != -1;
        CHEST_LOOT_SYNC_ID = -1;
        if (hadOpenChestSession) {
            CHEST_LOOT_TARGET = NO_POS;
        }
        CHEST_LOOT_TICKS = 0;
        CHEST_LOOT_EMPTY_TICKS = 0;
        CHEST_LOOT_SAW_CONTENTS = false;
        CHEST_LOOT_MOVE_COOLDOWN = 0;
    }

    private static void closeChestLootForCombat(MinecraftClient client) {
        if (client.player != null) {
            client.player.closeHandledScreen();
        }

        long pausedTarget = CHEST_LOOT_TARGET;
        resetChestLootState();
        CHEST_LOOT_TARGET = NO_POS;

        if (pausedTarget != NO_POS) {
            RECENTLY_CLICKED_TARGETS.remove(pausedTarget);
            FORCE_PATH_REBUILD = true;
            pathRefreshCountdown = 0;
        }
    }

    private static boolean hasLootableContainerContents(ScreenHandler handler, int slotCount) {
        for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
            Slot slot = handler.getSlot(slotIndex);

            if (slot.hasStack() && isWantedLootStack(slot.getStack())) {
                return true;
            }
        }

        return false;
    }

    private static boolean canContinuePathfindingWithInventory(MinecraftClient client) {
        if (client == null || client.player == null || client.interactionManager == null) {
            return true;
        }

        PlayerInventory inventory = client.player.getInventory();

        if (!isMainInventoryFull(inventory)) {
            return true;
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler != null
                && isHandledScreenOpen(client, handler)
                && tryThrowHealthPackForSpace(client, handler)) {
            return true;
        }

        if (!isMainInventoryFull(inventory)) {
            return true;
        }

        if (handler != null
                && isHandledScreenOpen(client, handler)
                && countNamedSlots(inventory, HEALTH_PACK_NAME) > 1
                && (hasHotbarStack(inventory, HEALTH_PACK_NAME) || canMoveHealthPackToHotbar(inventory))) {
            return true;
        }

        if (shouldOpenInventoryForPickupSpace(client, client.world)) {
            return true;
        }

        clearPathSnapshot();
        resetAutoPathState();
        invalidatePendingPathWork();
        return false;
    }

    private static boolean isMainInventoryFull(PlayerInventory inventory) {
        for (int slot = 0; slot < PLAYER_MAIN_INVENTORY_SIZE; slot++) {
            if (inventory.getStack(slot).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private static boolean tryManageAutoInventory(MinecraftClient client, ClientWorld world) {
        if (client.player == null || client.interactionManager == null) {
            resetAutoInventoryState();
            return false;
        }

        if (AUTO_INVENTORY_OPENED) {
            if (!(client.currentScreen instanceof InventoryScreen)
                    || !isHandledScreenOpen(client, client.player.playerScreenHandler)) {
                resetAutoInventoryState();
                return false;
            }

            releaseAutoMovement(client);
            releaseAutoUse(client);

            AUTO_INVENTORY_TICKS++;
            if (AUTO_INVENTORY_TICKS < AUTO_INVENTORY_SETTLE_TICKS) {
                return true;
            }

            if (inventoryMoveCooldown > 0) {
                return true;
            }

            if (tryManageOpenHandledInventory(client, client.player.playerScreenHandler, world, false)) {
                return true;
            }

            closeAutoInventory(client);
            return true;
        }

        if (client.currentScreen != null) {
            return false;
        }

        if (!shouldOpenAutoInventoryForManagement(client, world)) {
            return false;
        }

        PlayerInventory inventory = client.player.getInventory();
        boolean hotbarPrepNeeded = canMoveHealthPackToHotbar(inventory)
                || shouldMoveFoodToHotbar(client, world, inventory)
                || canMoveArmorToHotbar(client, inventory);
        if (isZombieVeryClose(client, world) && !hotbarPrepNeeded) {
            return false;
        }

        releaseAutoMovement(client);
        releaseAutoUse(client);
        client.setScreen(new InventoryScreen(client.player));
        AUTO_INVENTORY_OPENED = true;
        AUTO_INVENTORY_TICKS = 0;
        return true;
    }

    private static boolean tryManageOpenHandledInventory(
            MinecraftClient client,
            ScreenHandler handler,
            ClientWorld world,
            boolean chestScreen
    ) {
        if (client.player == null || client.interactionManager == null || handler == null) {
            return false;
        }

        if (!isHandledScreenOpen(client, handler) || inventoryMoveCooldown > 0) {
            return false;
        }

        if (tryPrepareHotbarFromOpenHandler(client, handler, world)) {
            inventoryMoveCooldown = INVENTORY_MOVE_COOLDOWN_TICKS;
            return true;
        }

        boolean shouldMakeSpace = chestScreen
                ? isMainInventoryFull(client.player.getInventory())
                : shouldOpenInventoryForPickupSpace(client, world);

        if (shouldMakeSpace && tryThrowHealthPackForSpace(client, handler)) {
            inventoryMoveCooldown = INVENTORY_MOVE_COOLDOWN_TICKS;
            return true;
        }

        return false;
    }

    private static boolean shouldOpenAutoInventoryForManagement(MinecraftClient client, ClientWorld world) {
        PlayerInventory inventory = client.player.getInventory();

        if (canMoveHealthPackToHotbar(inventory)) {
            return true;
        }

        if (shouldMoveFoodToHotbar(client, world, inventory)) {
            return true;
        }

        if (canMoveArmorToHotbar(client, inventory)) {
            return true;
        }

        return shouldOpenInventoryForPickupSpace(client, world);
    }

    private static boolean shouldOpenInventoryForPickupSpace(MinecraftClient client, ClientWorld world) {
        if (!AUTO_ENABLED || client == null || client.player == null || world == null) {
            return false;
        }

        PlayerInventory inventory = client.player.getInventory();
        return isMainInventoryFull(inventory)
                && hasWantedDroppedItemsVisible(world)
                && countNamedSlots(inventory, HEALTH_PACK_NAME) > 1
                && (hasHotbarStack(inventory, HEALTH_PACK_NAME) || canMoveHealthPackToHotbar(inventory));
    }

    private static void closeAutoInventory(MinecraftClient client) {
        if (client.player != null) {
            client.player.closeHandledScreen();
        }

        resetAutoInventoryState();
    }

    private static void resetAutoInventoryState() {
        AUTO_INVENTORY_OPENED = false;
        AUTO_INVENTORY_TICKS = 0;
    }

    private static boolean tryPrepareHotbarFromOpenHandler(
            MinecraftClient client,
            ScreenHandler handler,
            ClientWorld world
    ) {
        return tryMoveHealthPackToHotbar(client, handler)
                || tryMoveFoodToHotbar(client, handler, world)
                || tryMoveArmorToHotbar(client, handler);
    }

    private static boolean tryMoveHealthPackToHotbar(MinecraftClient client, ScreenHandler handler) {
        PlayerInventory inventory = client.player.getInventory();
        if (hasHotbarStack(inventory, HEALTH_PACK_NAME)) {
            return false;
        }

        int sourceSlot = findNamedMainInventorySlot(inventory, HEALTH_PACK_NAME);
        if (sourceSlot < 0) {
            return false;
        }

        int targetHotbarSlot = findHotbarSwapTargetForHealthPack(inventory);
        return targetHotbarSlot >= 0
                && swapInventorySlotToHotbar(client, handler, sourceSlot, targetHotbarSlot);
    }

    private static boolean tryMoveFoodToHotbar(MinecraftClient client, ScreenHandler handler, ClientWorld world) {
        PlayerInventory inventory = client.player.getInventory();
        if (!shouldMoveFoodToHotbar(client, world, inventory)) {
            return false;
        }

        int sourceSlot = findNamedMainInventorySlot(inventory, FRIED_CHICKEN_NAME);
        if (sourceSlot < 0) {
            return false;
        }

        int targetHotbarSlot = findHotbarSwapTargetForFood(inventory);
        return targetHotbarSlot >= 0
                && swapInventorySlotToHotbar(client, handler, sourceSlot, targetHotbarSlot);
    }

    private static boolean tryMoveArmorToHotbar(MinecraftClient client, ScreenHandler handler) {
        PlayerInventory inventory = client.player.getInventory();
        int targetHotbarSlot = findHotbarSwapTargetForArmor(inventory);
        if (targetHotbarSlot < 0) {
            return false;
        }

        for (int inventorySlot = PlayerInventory.getHotbarSize();
                inventorySlot < PLAYER_MAIN_INVENTORY_SIZE;
                inventorySlot++) {
            ItemStack stack = inventory.getStack(inventorySlot);
            EquipmentSlot equipmentSlot = getArmorEquipmentSlot(stack);

            if (equipmentSlot == null || !client.player.getEquippedStack(equipmentSlot).isEmpty()) {
                continue;
            }

            return swapInventorySlotToHotbar(client, handler, inventorySlot, targetHotbarSlot);
        }

        return false;
    }

    private static boolean swapInventorySlotToHotbar(
            MinecraftClient client,
            ScreenHandler handler,
            int inventorySlot,
            int hotbarSlot
    ) {
        if (hotbarSlot < 0 || hotbarSlot >= PlayerInventory.getHotbarSize()) {
            return false;
        }

        int screenSlot = getCurrentHandlerInventoryScreenSlot(handler, inventorySlot);
        if (screenSlot < 0 || screenSlot >= handler.slots.size()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                screenSlot,
                hotbarSlot,
                SlotActionType.SWAP,
                client.player
        );

        return true;
    }

    private static boolean tryThrowHealthPackForSpace(MinecraftClient client, ScreenHandler handler) {
        if (extraHealthPackThrowCooldown > 0 || client.player == null || client.interactionManager == null) {
            return false;
        }

        PlayerInventory inventory = client.player.getInventory();
        int inventorySlot = findThrowableHealthPackSlot(inventory);
        if (inventorySlot < 0) {
            return false;
        }

        int screenSlot = getCurrentHandlerInventoryScreenSlot(handler, inventorySlot);
        if (screenSlot < 0 || screenSlot >= handler.slots.size()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                screenSlot,
                1,
                SlotActionType.THROW,
                client.player
        );

        extraHealthPackThrowCooldown = EXTRA_HEALTH_PACK_THROW_COOLDOWN_TICKS;
        return true;
    }

    private static int findThrowableHealthPackSlot(PlayerInventory inventory) {
        int protectedHotbarSlot = findNamedHotbarSlot(inventory, HEALTH_PACK_NAME);
        if (protectedHotbarSlot < 0 || countNamedSlots(inventory, HEALTH_PACK_NAME) <= 1) {
            return -1;
        }

        for (int slot = PlayerInventory.getHotbarSize(); slot < PLAYER_MAIN_INVENTORY_SIZE; slot++) {
            if (isNamedStack(inventory.getStack(slot), HEALTH_PACK_NAME)) {
                return slot;
            }
        }

        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            if (slot != protectedHotbarSlot && isNamedStack(inventory.getStack(slot), HEALTH_PACK_NAME)) {
                return slot;
            }
        }

        return -1;
    }

    private static boolean canMoveHealthPackToHotbar(PlayerInventory inventory) {
        return !hasHotbarStack(inventory, HEALTH_PACK_NAME)
                && findNamedMainInventorySlot(inventory, HEALTH_PACK_NAME) >= 0
                && findHotbarSwapTargetForHealthPack(inventory) >= 0;
    }

    private static boolean shouldMoveFoodToHotbar(MinecraftClient client, ClientWorld world, PlayerInventory inventory) {
        return client.player.getHungerManager().getFoodLevel() <= FOOD_TRIGGER_LEVEL
                && !isZombieVeryClose(client, world)
                && findFoodHotbarSlot(inventory) < 0
                && findNamedMainInventorySlot(inventory, FRIED_CHICKEN_NAME) >= 0
                && findHotbarSwapTargetForFood(inventory) >= 0;
    }

    private static boolean canMoveArmorToHotbar(MinecraftClient client, PlayerInventory inventory) {
        if (findHotbarSwapTargetForArmor(inventory) < 0) {
            return false;
        }

        for (int slot = PlayerInventory.getHotbarSize(); slot < PLAYER_MAIN_INVENTORY_SIZE; slot++) {
            EquipmentSlot equipmentSlot = getArmorEquipmentSlot(inventory.getStack(slot));
            if (equipmentSlot != null && client.player.getEquippedStack(equipmentSlot).isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private static int findHotbarSwapTargetForHealthPack(PlayerInventory inventory) {
        int emptySlot = findEmptyHotbarSlot(inventory);
        if (emptySlot >= 0) {
            return emptySlot;
        }

        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            if (!isGunStack(inventory.getStack(slot))) {
                return slot;
            }
        }

        return -1;
    }

    private static int findHotbarSwapTargetForFood(PlayerInventory inventory) {
        int emptySlot = findEmptyHotbarSlot(inventory);
        if (emptySlot >= 0) {
            return emptySlot;
        }

        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isGunStack(stack) && !isNamedStack(stack, HEALTH_PACK_NAME)) {
                return slot;
            }
        }

        return -1;
    }

    private static int findHotbarSwapTargetForArmor(PlayerInventory inventory) {
        int emptySlot = findEmptyHotbarSlot(inventory);
        if (emptySlot >= 0) {
            return emptySlot;
        }

        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isGunStack(stack) && !isNamedStack(stack, HEALTH_PACK_NAME)) {
                return slot;
            }
        }

        return -1;
    }

    private static int findEmptyHotbarSlot(PlayerInventory inventory) {
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            if (inventory.getStack(slot).isEmpty()) {
                return slot;
            }
        }

        return -1;
    }

    private static int findNamedMainInventorySlot(PlayerInventory inventory, String expectedName) {
        for (int slot = PlayerInventory.getHotbarSize(); slot < PLAYER_MAIN_INVENTORY_SIZE; slot++) {
            if (isNamedStack(inventory.getStack(slot), expectedName)) {
                return slot;
            }
        }

        return -1;
    }

    private static boolean hasHotbarStack(PlayerInventory inventory, String expectedName) {
        return findNamedHotbarSlot(inventory, expectedName) >= 0;
    }

    private static int countNamedSlots(PlayerInventory inventory, String expectedName) {
        int count = 0;

        for (int slot = 0; slot < PLAYER_MAIN_INVENTORY_SIZE; slot++) {
            if (isNamedStack(inventory.getStack(slot), expectedName)) {
                count++;
            }
        }

        return count;
    }

    private static boolean hasWantedDroppedItemsVisible(ClientWorld world) {
        if (world == null || DROPPED_ITEM_POSITIONS_SNAPSHOT.length == 0) {
            return false;
        }

        for (long target : DROPPED_ITEM_POSITIONS_SNAPSHOT) {
            if (isDroppedItemTargetStillValid(world, target)) {
                return true;
            }
        }

        return false;
    }

    private static int getCurrentHandlerInventoryScreenSlot(ScreenHandler handler, int inventorySlot) {
        if (handler instanceof GenericContainerScreenHandler containerHandler) {
            int chestSlots = containerHandler.getRows() * 9;
            int hotbarSize = PlayerInventory.getHotbarSize();

            if (inventorySlot >= 0 && inventorySlot < hotbarSize) {
                return chestSlots + 27 + inventorySlot;
            }

            if (inventorySlot >= hotbarSize && inventorySlot < PLAYER_MAIN_INVENTORY_SIZE) {
                return chestSlots + (inventorySlot - hotbarSize);
            }

            return -1;
        }

        return getPlayerInventoryScreenSlot(inventorySlot);
    }

    private static boolean isHandledScreenOpen(MinecraftClient client, ScreenHandler handler) {
        return client.currentScreen instanceof HandledScreen<?> handledScreen
                && handledScreen.getScreenHandler() == handler;
    }

    private static boolean tryAutoEquipArmor(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null || autoArmorEquipCooldown > 0) {
            return false;
        }

        PlayerInventory inventory = client.player.getInventory();

        for (int inventorySlot = 0; inventorySlot < PlayerInventory.getHotbarSize(); inventorySlot++) {
            ItemStack stack = inventory.getStack(inventorySlot);
            EquipmentSlot equipmentSlot = getArmorEquipmentSlot(stack);

            if (equipmentSlot == null || !client.player.getEquippedStack(equipmentSlot).isEmpty()) {
                continue;
            }

            releaseAutoMovement(client);
            releaseAutoUse(client);

            inventory.setSelectedSlot(inventorySlot);
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            client.player.swingHand(Hand.MAIN_HAND);

            autoArmorEquipCooldown = AUTO_ARMOR_EQUIP_COOLDOWN_TICKS;
            return true;
        }

        return false;
    }

    private static int getPlayerInventoryScreenSlot(int inventorySlot) {
        int hotbarSize = PlayerInventory.getHotbarSize();

        if (inventorySlot >= 0 && inventorySlot < hotbarSize) {
            return 36 + inventorySlot;
        }

        if (inventorySlot >= hotbarSize && inventorySlot < PLAYER_MAIN_INVENTORY_SIZE) {
            return inventorySlot;
        }

        return -1;
    }

    private static boolean tryUseSurvivalItem(MinecraftClient client, ClientWorld world) {
        if (client.player == null || client.interactionManager == null || client.options == null) {
            return false;
        }

        boolean zombieTooCloseForFood = isZombieVeryClose(client, world);

        if (client.player.isUsingItem()) {
            ItemStack activeItem = client.player.getActiveItem();
            SurvivalUseType activeType = getSurvivalUseType(activeItem);

            // stop eating immediately if danger gets too close
            if (activeType == SurvivalUseType.FOOD && zombieTooCloseForFood) {
                releaseAutoUse(client);
                client.player.stopUsingItem();
                survivalUseCooldown = 0;
                return false;
            }

            if (activeType != null && stillNeedsSurvivalItem(client, activeItem)) {
                releaseAutoMovement(client);
                client.options.useKey.setPressed(true);
                return true;
            }

            releaseAutoUse(client);
            return false;
        }

        int slot = getNeededSurvivalHotbarSlot(client, zombieTooCloseForFood);
        if (slot < 0) {
            releaseAutoUse(client);
            return false;
        }

        ItemStack selectedStack = client.player.getInventory().getStack(slot);

        releaseAutoMovement(client);
        client.player.getInventory().setSelectedSlot(slot);
        client.options.useKey.setPressed(true);

        if (survivalUseCooldown <= 0) {
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            client.player.swingHand(Hand.MAIN_HAND);
            survivalUseCooldown = getSurvivalUseRetryTicks(selectedStack);
        }

        return true;
    }

    private static int getNeededSurvivalHotbarSlot(MinecraftClient client, boolean zombieTooCloseForFood) {
        if (client.player == null) {
            return -1;
        }

        PlayerInventory inventory = client.player.getInventory();

        if (client.player.getHealth() <= HEALTH_PACK_TRIGGER_HEALTH) {
            int healthSlot = findNamedHotbarSlot(inventory, HEALTH_PACK_NAME);
            if (healthSlot >= 0) {
                return healthSlot;
            }
        }

        if (!zombieTooCloseForFood && client.player.getHungerManager().getFoodLevel() <= FOOD_TRIGGER_LEVEL) {
            int foodSlot = findFoodHotbarSlot(inventory);
            if (foodSlot >= 0) {
                return foodSlot;
            }
        }

        return -1;
    }

    private static boolean stillNeedsSurvivalItem(MinecraftClient client, ItemStack activeItem) {
        if (client.player == null) {
            return false;
        }

        SurvivalUseType type = getSurvivalUseType(activeItem);
        if (type == null) {
            return false;
        }

        return switch (type) {
            case HEALTH_PACK -> client.player.getHealth() < HEALTH_PACK_STOP_HEALTH;
            case FOOD -> client.player.getHungerManager().getFoodLevel() < FOOD_STOP_LEVEL;
        };
    }

    private static boolean isSurvivalUseStack(ItemStack stack) {
        return getSurvivalUseType(stack) != null;
    }

    private static SurvivalUseType getSurvivalUseType(ItemStack stack) {
        if (isNamedStack(stack, HEALTH_PACK_NAME)) {
            return SurvivalUseType.HEALTH_PACK;
        }

        if (isNamedStack(stack, FRIED_CHICKEN_NAME)) {
            return SurvivalUseType.FOOD;
        }

        return null;
    }

    private static int getSurvivalUseRetryTicks(ItemStack stack) {
        return getSurvivalUseType(stack) == SurvivalUseType.FOOD
                ? FOOD_USE_RETRY_TICKS
                : SURVIVAL_USE_RETRY_TICKS;
    }

    private static void releaseAutoUse(MinecraftClient client) {
        if (client != null && client.options != null) {
            client.options.useKey.setPressed(false);
        }
    }

    private static boolean tryShootVisibleZombie(MinecraftClient client, ClientWorld world) {
        if (client.player == null || client.interactionManager == null || client.options == null) {
            return false;
        }

        ZombieTargetChoice targetChoice = findVisibleZombieTarget(client, world);
        if (targetChoice == null) {
            releaseAutoUse(client);
            return false;
        }

        int gunSlot = findGunHotbarSlot(client, targetChoice.directionalZombieCount());
        if (gunSlot < 0) {
            releaseAutoUse(client);
            return false;
        }

        releaseAutoMovement(client);
        client.player.getInventory().setSelectedSlot(gunSlot);
        lookAt(client, targetChoice.aimPoint());

        if (isNamedStack(client.player.getInventory().getStack(gunSlot), M16_NAME)) {
            client.options.useKey.setPressed(true);
            return true;
        }

        releaseAutoUse(client);

        if (autoShootCooldown > 0) {
            return true;
        }

        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
        client.player.swingHand(Hand.MAIN_HAND);
        autoShootCooldown = AUTO_SHOOT_COOLDOWN_TICKS;

        return true;
    }

    private static ZombieTargetChoice findVisibleZombieTarget(MinecraftClient client, ClientWorld world) {
        if (client.player == null) {
            return null;
        }

        Vec3d eye = client.player.getEyePos();
        Box searchBox = client.player.getBoundingBox().expand(ZOMBIE_SHOOT_RANGE);
        List<ZombieEntity> zombies = world.getEntitiesByType(
                TypeFilter.instanceOf(ZombieEntity.class),
                searchBox,
                zombie -> isShootableZombie(client, zombie)
        );

        List<ZombieAimCandidate> candidates = new java.util.ArrayList<>();
        ZombieAimCandidate best = null;

        for (ZombieEntity zombie : zombies) {
            Vec3d aimPoint = getBestZombieAimPoint(world, eye, zombie);
            if (aimPoint == null) {
                continue;
            }

            double distSq = eye.squaredDistanceTo(aimPoint);
            if (distSq > ZOMBIE_SHOOT_RANGE_SQ) {
                continue;
            }

            Vec3d direction = aimPoint.subtract(eye);
            double lenSq = direction.lengthSquared();
            if (lenSq < 1.0E-6) {
                continue;
            }

            direction = direction.multiply(1.0 / Math.sqrt(lenSq));

            ZombieAimCandidate candidate = new ZombieAimCandidate(zombie, aimPoint, distSq, direction);
            candidates.add(candidate);

            if (best == null || distSq < best.distSq()) {
                best = candidate;
            }
        }

        if (best == null) {
            return null;
        }

        int directionalZombieCount = 0;
        for (ZombieAimCandidate candidate : candidates) {
            if (candidate.direction().dotProduct(best.direction()) >= ZOMBIE_DIRECTION_CONE_COS) {
                directionalZombieCount++;
            }
        }

        return new ZombieTargetChoice(best.zombie(), best.aimPoint(), directionalZombieCount);
    }

    private static boolean isShootableZombie(MinecraftClient client, ZombieEntity zombie) {
        return zombie != null
                && zombie.isAlive()
                && zombie.canHit()
                && !zombie.isSpectator()
                && !zombie.isInvisibleTo(client.player);
    }

    private static Vec3d getZombieHeadTarget(ZombieEntity zombie) {
        return new Vec3d(zombie.getPos().x, zombie.getEyeY() - 0.08, zombie.getPos().z);
    }

    private static boolean hasClearShot(ClientWorld world, Vec3d from, Vec3d to) {
        BlockHitResult hit = world.raycast(new RaycastContext(
                from,
                to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                ShapeContext.absent()
        ));

        return hit.getType() == HitResult.Type.MISS;
    }

    private static int findGunHotbarSlot(MinecraftClient client, int visibleZombieCount) {
        if (client.player == null) {
            return -1;
        }

        PlayerInventory inventory = client.player.getInventory();
        int selectedSlot = inventory.getSelectedSlot();
        String[] priority = getGunPriority(visibleZombieCount);

        for (String gunName : priority) {
            if (!hasAmmoForGun(inventory, gunName)) {
                continue;
            }

            if (isNamedStack(inventory.getStack(selectedSlot), gunName)) {
                return selectedSlot;
            }

            for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
                if (isNamedStack(inventory.getStack(slot), gunName)) {
                    return slot;
                }
            }
        }

        return -1;
    }

    private static boolean hasAmmoForGun(PlayerInventory inventory, String gunName) {
        String ammoName = getAmmoName(gunName);
        return ammoName != null && countNamedStacks(inventory, ammoName) > 0;
    }

    private static String getAmmoName(String gunName) {
        return switch (gunName) {
            case PISTOL_NAME -> PISTOL_AMMO_NAME;
            case M16_NAME -> M16_AMMO_NAME;
            case SHOTGUN_NAME -> SHOTGUN_AMMO_NAME;
            default -> null;
        };
    }

    private static int countNamedStacks(PlayerInventory inventory, String expectedName) {
        int count = 0;

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);

            if (isNamedStack(stack, expectedName)) {
                count += stack.getCount();
            }
        }

        return count;
    }

    private static int findNamedHotbarSlot(PlayerInventory inventory, String expectedName) {
        int selectedSlot = inventory.getSelectedSlot();

        if (isNamedStack(inventory.getStack(selectedSlot), expectedName)) {
            return selectedSlot;
        }

        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            if (isNamedStack(inventory.getStack(slot), expectedName)) {
                return slot;
            }
        }

        return -1;
    }

    private static int findFoodHotbarSlot(PlayerInventory inventory) {
        int selectedSlot = inventory.getSelectedSlot();

        if (isNamedStack(inventory.getStack(selectedSlot), FRIED_CHICKEN_NAME)) {
            return selectedSlot;
        }

        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            if (isNamedStack(inventory.getStack(slot), FRIED_CHICKEN_NAME)) {
                return slot;
            }
        }

        return -1;
    }

    private static String[] getGunPriority(int visibleZombieCount) {
        if (visibleZombieCount <= LOW_ZOMBIE_COUNT_MAX) {
            return LOW_ZOMBIE_GUN_PRIORITY;
        }

        if (visibleZombieCount <= MEDIUM_ZOMBIE_COUNT_MAX) {
            return MEDIUM_ZOMBIE_GUN_PRIORITY;
        }

        return HIGH_ZOMBIE_GUN_PRIORITY;
    }

    private static boolean isNamedStack(ItemStack stack, String expectedName) {
        return stack != null
                && !stack.isEmpty()
                && stack.getName().getString().trim().equalsIgnoreCase(expectedName);
    }

    static boolean isWantedLootStack(ItemStack stack) {
        return isAmmoStack(stack)
                || isNamedStack(stack, HEALTH_PACK_NAME)
                || isNamedStack(stack, FRIED_CHICKEN_NAME)
                || getArmorEquipmentSlot(stack) != null;
    }

    private static boolean isAmmoStack(ItemStack stack) {
        return isNamedStack(stack, PISTOL_AMMO_NAME)
                || isNamedStack(stack, M16_AMMO_NAME)
                || isNamedStack(stack, SHOTGUN_AMMO_NAME);
    }

    private static boolean isGunStack(ItemStack stack) {
        return isNamedStack(stack, PISTOL_NAME)
                || isNamedStack(stack, M16_NAME)
                || isNamedStack(stack, SHOTGUN_NAME);
    }

    private static EquipmentSlot getArmorEquipmentSlot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        if (equippable == null) {
            return null;
        }

        EquipmentSlot slot = equippable.slot();
        if (slot == null || !slot.isArmorSlot()) {
            return null;
        }

        return switch (slot) {
            case HEAD  -> stack.isIn(ItemTags.HEAD_ARMOR)  ? EquipmentSlot.HEAD  : null;
            case CHEST -> stack.isIn(ItemTags.CHEST_ARMOR) ? EquipmentSlot.CHEST : null;
            case LEGS  -> stack.isIn(ItemTags.LEG_ARMOR)   ? EquipmentSlot.LEGS  : null;
            case FEET  -> stack.isIn(ItemTags.FOOT_ARMOR)  ? EquipmentSlot.FEET  : null;
            default    -> null;
        };
    }

    private static BlockPos getNextAutoWaypoint(MinecraftClient client, long[] path, long targetPacked) {
        if (client.player == null || path.length == 0) {
            return null;
        }

        long pathEnd = path[path.length - 1];

        boolean pathChanged = targetPacked != AUTO_LAST_TARGET
                || pathEnd != AUTO_LAST_PATH_END
                || path != AUTO_LAST_PATH_REF;

        if (pathChanged) {
            AUTO_LAST_TARGET = targetPacked;
            AUTO_LAST_PATH_END = pathEnd;
            AUTO_LAST_PATH_REF = path;

            AUTO_WAYPOINT_INDEX = findBestAutoWaypointIndex(client, path);

            if (AUTO_WAYPOINT_INDEX < 0) {
                FORCE_PATH_REBUILD = true;
                pathRefreshCountdown = 0;
                return null;
            }
        } else if (isAutoWaypointMisaligned(client, path)) {
            int bestIndex = findBestAutoWaypointIndex(client, path);

            if (bestIndex < 0) {
                FORCE_PATH_REBUILD = true;
                pathRefreshCountdown = 0;
                return null;
            }

            AUTO_WAYPOINT_INDEX = Math.max(AUTO_WAYPOINT_INDEX, bestIndex);
        }

        if (AUTO_WAYPOINT_INDEX < 0) {
            FORCE_PATH_REBUILD = true;
            pathRefreshCountdown = 0;
            return null;
        }

        if (AUTO_WAYPOINT_INDEX >= path.length) {
            AUTO_WAYPOINT_INDEX = path.length - 1;
        }

        Vec3d playerPos = client.player.getPos();

        while (AUTO_WAYPOINT_INDEX < path.length - 1) {
            BlockPos currentNode = BlockPos.fromLong(path[AUTO_WAYPOINT_INDEX]);
            BlockPos nextNode = BlockPos.fromLong(path[AUTO_WAYPOINT_INDEX + 1]);

            Vec3d currentCenter = nodeCenter(currentNode);
            Vec3d nextCenter = nodeCenter(nextNode);

            double distToCurrentSq = horizontalDistanceSq(playerPos, currentCenter);

            boolean verticalOk = isPlayerVerticallyNearNode(client, currentNode, AUTO_PATH_VERTICAL_TOLERANCE);

            boolean closeEnoughToCurrent = verticalOk && distToCurrentSq <= WAYPOINT_ADVANCE_DISTANCE_SQ;
            boolean passedCurrent = verticalOk
                    && distToCurrentSq <= WAYPOINT_PASS_DISTANCE_SQ
                    && hasPassedNodeHorizontally(playerPos, currentCenter, nextCenter);

            if (closeEnoughToCurrent || passedCurrent) {
                AUTO_WAYPOINT_INDEX++;
            } else {
                break;
            }
        }

        if (isAutoWaypointMisaligned(client, path)) {
            int bestIndex = findBestAutoWaypointIndex(client, path);

            if (bestIndex < 0) {
                FORCE_PATH_REBUILD = true;
                pathRefreshCountdown = 0;
                return null;
            }

            AUTO_WAYPOINT_INDEX = Math.max(AUTO_WAYPOINT_INDEX, bestIndex);
        }

        return BlockPos.fromLong(path[AUTO_WAYPOINT_INDEX]);
    }

    private static boolean isAutoWaypointMisaligned(MinecraftClient client, long[] path) {
        if (client.player == null || path.length <= 1) {
            return false;
        }

        if (client.world != null && isAutoInDropTransition(client, client.world, path)) {
            return false;
        }

        int currentIndex = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);
        int bestIndex = findBestAutoWaypointIndex(client, path);

        if (bestIndex < 0) {
            return true;
        }

        if (bestIndex <= currentIndex) {
            return false;
        }

        double currentDistSq = distanceToAutoSegmentSq(client, path, currentIndex);
        double bestDistSq = distanceToAutoSegmentSq(client, path, bestIndex);

        if (currentDistSq > AUTO_REALIGN_DISTANCE_SQ) {
            return true;
        }

        return bestDistSq + AUTO_REALIGN_IMPROVEMENT_SQ < currentDistSq;
    }

    private static int findBestAutoWaypointIndex(MinecraftClient client, long[] path) {
        if (client.player == null || client.world == null || path.length == 0) {
            return -1;
        }

        if (path.length == 1) {
            BlockPos only = BlockPos.fromLong(path[0]);
            return isPlayerVerticallyNearNode(client, only, AUTO_PATH_VERTICAL_TOLERANCE) ? 0 : -1;
        }

        if (isAutoInDropTransition(client, client.world, path)) {
            return MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);
        }

        Vec3d playerPos = client.player.getPos();

        double bestScore = Double.POSITIVE_INFINITY;
        double bestT = 0.0;
        int bestSegmentEndIndex = -1;

        for (int i = 1; i < path.length; i++) {
            Vec3d a = nodeCenter(BlockPos.fromLong(path[i - 1]));
            Vec3d b = nodeCenter(BlockPos.fromLong(path[i]));

            double t = horizontalProjectionT(playerPos, a, b);

            double segmentFeetY = a.y + (b.y - a.y) * t;
            double verticalDistance = Math.abs(playerPos.y - segmentFeetY);

            if (verticalDistance > AUTO_PATH_VERTICAL_TOLERANCE) {
                continue;
            }

            double horizontalDistanceSq = horizontalDistanceToSegmentSq(playerPos, a, b);
            double score = horizontalDistanceSq
                    + verticalDistance * verticalDistance * AUTO_VERTICAL_SEGMENT_SCORE_WEIGHT;

            if (score < bestScore) {
                bestScore = score;
                bestT = t;
                bestSegmentEndIndex = i;
            }
        }

        if (bestSegmentEndIndex < 0) {
            return -1;
        }

        int nextIndex = bestSegmentEndIndex;

        if (bestT >= AUTO_SEGMENT_ADVANCE_T && bestSegmentEndIndex < path.length - 1) {
            nextIndex = bestSegmentEndIndex + 1;
        }

        return MathHelper.clamp(nextIndex, 1, path.length - 1);
    }

    private static double distanceToAutoSegmentSq(MinecraftClient client, long[] path, int waypointIndex) {
        if (client.player == null || client.world == null || path.length == 0) {
            return Double.POSITIVE_INFINITY;
        }

        Vec3d playerPos = client.player.getPos();

        if (path.length == 1) {
            BlockPos node = BlockPos.fromLong(path[0]);

            if (!isPlayerVerticallyNearNode(client, node, AUTO_PATH_VERTICAL_TOLERANCE)) {
                return Double.POSITIVE_INFINITY;
            }

            return horizontalDistanceSq(playerPos, nodeCenter(node));
        }

        int segmentEnd = MathHelper.clamp(waypointIndex, 1, path.length - 1);

        Vec3d a = nodeCenter(BlockPos.fromLong(path[segmentEnd - 1]));
        Vec3d b = nodeCenter(BlockPos.fromLong(path[segmentEnd]));

        double t = horizontalProjectionT(playerPos, a, b);
        double segmentFeetY = a.y + (b.y - a.y) * t;
        double verticalDistance = Math.abs(playerPos.y - segmentFeetY);

        if (verticalDistance > AUTO_PATH_VERTICAL_TOLERANCE) {
            return Double.POSITIVE_INFINITY;
        }

        double horizontalDistanceSq = horizontalDistanceToSegmentSq(playerPos, a, b);
        return horizontalDistanceSq + verticalDistance * verticalDistance * AUTO_VERTICAL_SEGMENT_SCORE_WEIGHT;
    }

    private static double horizontalProjectionT(Vec3d p, Vec3d a, Vec3d b) {
        double abX = b.x - a.x;
        double abZ = b.z - a.z;

        double lenSq = abX * abX + abZ * abZ;
        if (lenSq < 0.0001) {
            return 0.0;
        }

        double apX = p.x - a.x;
        double apZ = p.z - a.z;

        double t = (apX * abX + apZ * abZ) / lenSq;

        if (t < 0.0) {
            return 0.0;
        }

        if (t > 1.0) {
            return 1.0;
        }

        return t;
    }

    private static double horizontalDistanceToSegmentSq(Vec3d p, Vec3d a, Vec3d b) {
        double t = horizontalProjectionT(p, a, b);

        double closestX = a.x + (b.x - a.x) * t;
        double closestZ = a.z + (b.z - a.z) * t;

        double dx = p.x - closestX;
        double dz = p.z - closestZ;

        return dx * dx + dz * dz;
    }

    private static Vec3d nodeCenter(BlockPos pos) {
        double feetY = pos.getY();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.world != null) {
            double standingY = getStandingFeetY(client.world, pos);
            if (!Double.isNaN(standingY)) {
                feetY = standingY;
            }

            return wallAvoidedNodeCenter(client.world, pos, feetY);
        }

        return new Vec3d(pos.getX() + 0.5, feetY, pos.getZ() + 0.5);
    }

    private static Vec3d wallAvoidedNodeCenter(ClientWorld world, BlockPos pos, double feetY) {
        PathWorldView pathWorld = new LivePathWorld(world);

        double centerX = pos.getX() + 0.5;
        double centerZ = pos.getZ() + 0.5;

        int pushX = 0;
        int pushZ = 0;

        if (!isPlayerBoxClearAt(pathWorld, centerX - AUTO_WALL_AVOIDANCE_PROBE, feetY, centerZ)) {
            pushX++;
        }

        if (!isPlayerBoxClearAt(pathWorld, centerX + AUTO_WALL_AVOIDANCE_PROBE, feetY, centerZ)) {
            pushX--;
        }

        if (!isPlayerBoxClearAt(pathWorld, centerX, feetY, centerZ - AUTO_WALL_AVOIDANCE_PROBE)) {
            pushZ++;
        }

        if (!isPlayerBoxClearAt(pathWorld, centerX, feetY, centerZ + AUTO_WALL_AVOIDANCE_PROBE)) {
            pushZ--;
        }

        if (pushX == 0 && pushZ == 0) {
            return new Vec3d(centerX, feetY, centerZ);
        }

        double len = Math.sqrt(pushX * pushX + pushZ * pushZ);
        double offsetX = AUTO_WALL_AVOIDANCE_OFFSET * pushX / len;
        double offsetZ = AUTO_WALL_AVOIDANCE_OFFSET * pushZ / len;

        double nudgedX = centerX + offsetX;
        double nudgedZ = centerZ + offsetZ;

        if (isPlayerBoxClearAt(pathWorld, nudgedX, feetY, nudgedZ)) {
            return new Vec3d(nudgedX, feetY, nudgedZ);
        }

        if (pushX != 0) {
            double xOnly = centerX + AUTO_WALL_AVOIDANCE_OFFSET * Math.signum(pushX);
            if (isPlayerBoxClearAt(pathWorld, xOnly, feetY, centerZ)) {
                return new Vec3d(xOnly, feetY, centerZ);
            }
        }

        if (pushZ != 0) {
            double zOnly = centerZ + AUTO_WALL_AVOIDANCE_OFFSET * Math.signum(pushZ);
            if (isPlayerBoxClearAt(pathWorld, centerX, feetY, zOnly)) {
                return new Vec3d(centerX, feetY, zOnly);
            }
        }

        return new Vec3d(centerX, feetY, centerZ);
    }

    private static double horizontalDistanceSq(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static boolean hasPassedNodeHorizontally(Vec3d playerPos, Vec3d currentCenter, Vec3d nextCenter) {
        double segmentX = nextCenter.x - currentCenter.x;
        double segmentZ = nextCenter.z - currentCenter.z;

        double segmentLenSq = segmentX * segmentX + segmentZ * segmentZ;

        if (segmentLenSq < 0.0001) {
            return false;
        }

        double playerX = playerPos.x - currentCenter.x;
        double playerZ = playerPos.z - currentCenter.z;

        double dot = playerX * segmentX + playerZ * segmentZ;

        return dot > 0.18;
    }

    private static Vec3d getAutoAimPoint(MinecraftClient client, long[] path, BlockPos fallbackWaypoint) {
        if (client.player == null || path == null || path.length < 2) {
            return nodeCenter(fallbackWaypoint);
        }

        int index = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);

        Vec3d playerPos = client.player.getPos();

        BlockPos fromNode = BlockPos.fromLong(path[index - 1]);
        BlockPos toNode = BlockPos.fromLong(path[index]);

        Vec3d a = nodeCenter(fromNode);
        Vec3d b = nodeCenter(toNode);

        double abX = b.x - a.x;
        double abZ = b.z - a.z;
        double len = Math.sqrt(abX * abX + abZ * abZ);

        if (len < 0.0001) {
            return b;
        }

        boolean dropSegment = client.world != null && isAutoDropSegment(client.world, fromNode, toNode);

        double t = horizontalProjectionT(playerPos, a, b);

        double lookaheadDistance = dropSegment ? AUTO_DROP_LOOKAHEAD_DISTANCE : AUTO_LOOKAHEAD_DISTANCE;
        double lookaheadT = lookaheadDistance / len;
        double targetT = MathHelper.clamp(t + lookaheadT, 0.0, 1.0);

        if (dropSegment) {
            return lerp(a, b, targetT);
        }

        if (targetT >= 0.999 && index < path.length - 1) {
            Vec3d c = nodeCenter(BlockPos.fromLong(path[index + 1]));

            if (isHorizontalTurn(a, b, c)
                    && horizontalDistanceSq(playerPos, b) > AUTO_CORNER_LOOKAHEAD_DISTANCE_SQ) {
                return b;
            }

            double bcX = c.x - b.x;
            double bcZ = c.z - b.z;
            double nextLen = Math.sqrt(bcX * bcX + bcZ * bcZ);

            if (nextLen > 0.0001) {
                double nextT = MathHelper.clamp(AUTO_LOOKAHEAD_DISTANCE / nextLen, 0.0, 1.0);
                return lerp(b, c, nextT);
            }
        }

        return lerp(a, b, targetT);
    }

    private static boolean isHorizontalTurn(Vec3d a, Vec3d b, Vec3d c) {
        double abX = b.x - a.x;
        double abZ = b.z - a.z;
        double bcX = c.x - b.x;
        double bcZ = c.z - b.z;

        double abLen = Math.sqrt(abX * abX + abZ * abZ);
        double bcLen = Math.sqrt(bcX * bcX + bcZ * bcZ);

        if (abLen < 0.0001 || bcLen < 0.0001) {
            return false;
        }

        double normalizedCross = Math.abs((abX * bcZ - abZ * bcX) / (abLen * bcLen));
        return normalizedCross > 0.25;
    }

    private static Vec3d lerp(Vec3d a, Vec3d b, double t) {
        return new Vec3d(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t
        );
    }

    private static boolean isAutoDropSegment(ClientWorld world, BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());

        if (dx + dz != 1) {
            return false;
        }

        double fromY = getNodeFeetY(world, from);
        double toY = getNodeFeetY(world, to);

        return toY < fromY - HEIGHT_CHANGE_EPSILON;
    }

    private static boolean isAutoInDropTransition(MinecraftClient client, ClientWorld world, long[] path) {
        if (!AUTO_ENABLED || client.player == null || world == null || path == null || path.length < 2) {
            return false;
        }

        int index = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);

        BlockPos from = BlockPos.fromLong(path[index - 1]);
        BlockPos to = BlockPos.fromLong(path[index]);

        if (!isAutoDropSegment(world, from, to)) {
            return false;
        }

        Vec3d playerPos = client.player.getPos();
        Vec3d a = nodeCenter(from);
        Vec3d b = nodeCenter(to);

        double minY = Math.min(a.y, b.y) - AUTO_DROP_VERTICAL_PADDING;
        double maxY = Math.max(a.y, b.y) + AUTO_DROP_VERTICAL_PADDING;

        if (playerPos.y < minY || playerPos.y > maxY) {
            return false;
        }

        return horizontalDistanceToSegmentSq(playerPos, a, b) <= AUTO_DROP_REUSE_HORIZONTAL_DISTANCE_SQ;
    }

    private static float facePointYawOnlyPrecise(MinecraftClient client, Vec3d target) {
        if (client.player == null) {
            return 180.0f;
        }

        Vec3d playerPos = client.player.getPos();

        double dx = target.x - playerPos.x;
        double dz = target.z - playerPos.z;

        double horizontalSq = dx * dx + dz * dz;
        if (horizontalSq < 0.001) {
            return 0.0f;
        }

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float currentYaw = client.player.getYaw();
        float yawError = MathHelper.wrapDegrees(targetYaw - currentYaw);

        float maxYawStep = client.player.isOnGround()
                ? AUTO_GROUND_MAX_YAW_CHANGE_PER_TICK
                : AUTO_AIR_MAX_YAW_CHANGE_PER_TICK;

        float yawStep = MathHelper.clamp(yawError, -maxYawStep, maxYawStep);
        client.player.setYaw(currentYaw + yawStep);

        return Math.abs(MathHelper.wrapDegrees(targetYaw - client.player.getYaw()));
    }

    private static void driveTowardWaypoint(MinecraftClient client, ClientWorld world, long[] path, float yawError) {
        if (client.player == null || path.length == 0) {
            return;
        }

        AUTO_WAS_DRIVING = true;

        client.options.backKey.setPressed(false);
        client.options.sprintKey.setPressed(true);

        levelAutoMovePitch(client);

        boolean yawReady = Math.abs(yawError) <= AUTO_MOVE_YAW_TOLERANCE;
        client.options.forwardKey.setPressed(yawReady);

        if (!yawReady) {
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(shouldJumpOutOfWater(client, world, path));
            return;
        }

        applySideCorrection(client, path);

        BlockPos waypoint = BlockPos.fromLong(path[AUTO_WAYPOINT_INDEX]);
        boolean enteringLowJumpClearanceAhead = isEnteringLowJumpClearanceAhead(client, world, path);
        boolean shouldJump = client.player.isOnGround()
                && client.player.horizontalCollision
                && !enteringLowJumpClearanceAhead;

        double waypointFeetY = getStandingFeetY(world, waypoint);
        if (!Double.isNaN(waypointFeetY)) {
            Vec3d waypointCenter = new Vec3d(
                    waypoint.getX() + 0.5,
                    waypointFeetY,
                    waypoint.getZ() + 0.5
            );

            double rise = waypointFeetY - client.player.getY();
            double horizontalDistSq = horizontalDistanceSq(client.player.getPos(), waypointCenter);

            if (rise > STEP_HEIGHT + JUMP_RISE_EPSILON && horizontalDistSq <= JUMP_TRIGGER_DISTANCE_SQ) {
                shouldJump = true;
            }
        }

        if (!shouldJump && !enteringLowJumpClearanceAhead && shouldAutoSprintJump(client, world, path, yawError)) {
            shouldJump = true;
        }

        if (!shouldJump && shouldJumpOutOfWater(client, world, path)) {
            shouldJump = true;
        }

        client.options.jumpKey.setPressed(shouldJump);
    }

    private static boolean shouldJumpOutOfWater(MinecraftClient client, ClientWorld world, long[] path) {
        if (client.player == null || world == null || path == null || path.length == 0) {
            return false;
        }

        if (!client.player.isTouchingWater()) {
            return false;
        }

        PathWorldView pathWorld = new LivePathWorld(world);
        int index = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 0, path.length - 1);
        int end = Math.min(path.length - 1, index + WATER_EXIT_LOOKAHEAD_NODES);
        double playerY = client.player.getY();

        for (int i = index; i <= end; i++) {
            BlockPos node = BlockPos.fromLong(path[i]);
            if (isWaterAtFeetNode(pathWorld, node)) {
                continue;
            }

            double feetY = getStandingFeetY(pathWorld, node);
            if (!Double.isNaN(feetY) && feetY >= playerY - WATER_EXIT_MAX_DROP) {
                return true;
            }
        }

        return false;
    }

    private static void levelAutoMovePitch(MinecraftClient client) {
        if (client.player == null) {
            return;
        }

        float currentPitch = client.player.getPitch();
        float pitchError = AUTO_MOVE_TARGET_PITCH - currentPitch;
        float pitchStep = MathHelper.clamp(
                pitchError,
                -AUTO_MAX_PITCH_CHANGE_PER_TICK,
                AUTO_MAX_PITCH_CHANGE_PER_TICK
        );

        client.player.setPitch(MathHelper.clamp(currentPitch + pitchStep, -90.0f, 90.0f));
    }

    private static void releaseAutoMovement(MinecraftClient client) {
        if (!AUTO_WAS_DRIVING || client == null || client.options == null) {
            return;
        }

        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);

        resetSideCorrection();
        AUTO_WAS_DRIVING = false;
    }

    private static long findReachableTargetFromPlayer(MinecraftClient client, ClientWorld world, long preferredTargetPacked) {
        if (client.player == null) {
            return NO_POS;
        }

        Vec3d eye = client.player.getEyePos();
        double reach = client.player.getBlockInteractionRange() + REACH_PADDING;
        double reachSq = reach * reach;

        long best = NO_POS;
        double bestDistSq = Double.POSITIVE_INFINITY;

        for (long packed : SUPPLY_CHEST_POSITIONS_SNAPSHOT) {
            if (packed == NO_POS || isTemporarilyIgnoredTarget(packed) || isPoliceStationChestTarget(packed)) {
                continue;
            }

            if (!isPathTargetStillValid(world, packed)) {
                continue;
            }

            BlockPos pos = BlockPos.fromLong(packed);
            Vec3d targetPoint = getTargetReachPoint(world, pos);

            double distSq = eye.squaredDistanceTo(targetPoint);
            if (distSq > reachSq) {
                continue;
            }

            if (!hasReachLine(world, eye, targetPoint, pos)) {
                continue;
            }

            if (isBetterReachableClickCandidate(packed, distSq, best, bestDistSq, preferredTargetPacked)) {
                best = packed;
                bestDistSq = distSq;
            }
        }

        return best;
    }

    private static boolean isBetterReachableClickCandidate(
            long candidatePacked,
            double candidateDistSq,
            long currentPacked,
            double currentDistSq,
            long preferredTargetPacked
    ) {
        if (currentPacked == NO_POS) {
            return true;
        }

        boolean candidateIsPlannedTarget = candidatePacked == preferredTargetPacked;
        boolean currentIsPlannedTarget = currentPacked == preferredTargetPacked;

        if (candidateIsPlannedTarget != currentIsPlannedTarget) {
            return candidateIsPlannedTarget;
        }

        int distCompare = Double.compare(candidateDistSq, currentDistSq);

        if (distCompare != 0) {
            return distCompare < 0;
        }

        return Long.compare(candidatePacked, currentPacked) < 0;
    }

    private static void rightClickTarget(MinecraftClient client, ClientWorld world, BlockPos targetPos) {
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        Vec3d eye = client.player.getEyePos();
        Vec3d targetPoint = getTargetReachPoint(world, targetPos);

        BlockHitResult hit = world.raycast(new RaycastContext(
                eye,
                targetPoint,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                ShapeContext.absent()
        ));

        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(targetPos)) {
            hit = new BlockHitResult(targetPoint, Direction.UP, targetPos, false);
        }

        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);

        CHEST_LOOT_TARGET = targetPos.asLong();
        markTargetClicked(targetPos.asLong());
    }

    private static void markTargetClicked(long targetPacked) {
        RECENTLY_CLICKED_TARGETS.put(targetPacked, CLICKED_TARGET_IGNORE_TICKS);
        FORCE_PATH_REBUILD = true;
        pathRefreshCountdown = 0;

        clearPathSnapshot();
        resetAutoPathState();
    }

    private static void markTargetObservedLooted(long targetPacked) {
        if (targetPacked == NO_POS) {
            return;
        }

        boolean wasIgnored = RECENTLY_CLICKED_TARGETS.containsKey(targetPacked);
        RECENTLY_CLICKED_TARGETS.put(targetPacked, CLICKED_TARGET_IGNORE_TICKS);

        if (wasIgnored && targetPacked != PATH_TARGET_SNAPSHOT && targetPacked != AUTO_LAST_TARGET) {
            return;
        }

        FORCE_PATH_REBUILD = true;
        pathRefreshCountdown = 0;

        if (targetPacked == PATH_TARGET_SNAPSHOT) {
            clearPathSnapshot();
        }

        if (targetPacked == AUTO_LAST_TARGET) {
            resetAutoPathState();
        }
    }

    private static boolean tryCloseOpenDoorBlockingMove(MinecraftClient client, ClientWorld world, BlockPos waypoint) {
        if (client.player == null || client.interactionManager == null || autoClickCooldown > 0) {
            return false;
        }

        BlockPos doorBase = findOpenDoorBlockingPlayerMove(world, client.player.getPos(), waypoint);

        if (doorBase == null) {
            return false;
        }

        Vec3d hitPos = getDoorClickPoint(doorBase);

        double reach = client.player.getBlockInteractionRange() + REACH_PADDING;
        if (client.player.getEyePos().squaredDistanceTo(hitPos) > reach * reach) {
            return false;
        }

        releaseAutoMovement(client);
        lookAt(client, hitPos);
        rightClickBlock(client, world, doorBase, hitPos);

        autoClickCooldown = AUTO_CLICK_COOLDOWN_TICKS;

        FORCE_PATH_REBUILD = true;
        pathRefreshCountdown = 0;

        return true;
    }

    private static BlockPos findOpenDoorBlockingPlayerMove(ClientWorld world, Vec3d playerFeetPos, BlockPos waypoint) {
        double toFeetY = getStandingFeetY(world, waypoint);
        if (Double.isNaN(toFeetY)) {
            toFeetY = waypoint.getY();
        }

        double toX = waypoint.getX() + 0.5;
        double toZ = waypoint.getZ() + 0.5;

        Box scanBox = sweptPlayerBox(
                playerFeetPos.x,
                playerFeetPos.y,
                playerFeetPos.z,
                toX,
                toFeetY,
                toZ
        ).expand(1.0);

        int minX = MathHelper.floor(scanBox.minX);
        int maxX = MathHelper.floor(scanBox.maxX - COLLISION_EPSILON);
        int minY = MathHelper.floor(scanBox.minY);
        int maxY = MathHelper.floor(scanBox.maxY - COLLISION_EPSILON);
        int minZ = MathHelper.floor(scanBox.minZ);
        int maxZ = MathHelper.floor(scanBox.maxZ - COLLISION_EPSILON);

        LongOpenHashSet checkedDoorBases = new LongOpenHashSet();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);

                    if (!isLoaded(world, pos)) {
                        continue;
                    }

                    BlockState state = world.getBlockState(pos);

                    if (!isOpenHandOpenableDoor(state)) {
                        continue;
                    }

                    BlockPos base = getDoorBasePos(pos.toImmutable(), state);

                    if (!checkedDoorBases.add(base.asLong())) {
                        continue;
                    }

                    if (openDoorIntersectsPlayerMove(
                            world,
                            base,
                            playerFeetPos.x,
                            playerFeetPos.y,
                            playerFeetPos.z,
                            toX,
                            toFeetY,
                            toZ
                    )) {
                        return base;
                    }
                }
            }
        }

        return null;
    }

    private static boolean openDoorIntersectsPlayerMove(
            ClientWorld world,
            BlockPos doorBase,
            double fromX,
            double fromFeetY,
            double fromZ,
            double toX,
            double toFeetY,
            double toZ
    ) {
        for (int i = 0; i <= OPEN_DOOR_CLOSE_SWEEP_SAMPLES; i++) {
            double t = i / (double) OPEN_DOOR_CLOSE_SWEEP_SAMPLES;

            double x = fromX + (toX - fromX) * t;
            double y = fromFeetY + (toFeetY - fromFeetY) * t;
            double z = fromZ + (toZ - fromZ) * t;

            Box playerBox = makePlayerCollisionBox(x, y, z);

            if (openDoorIntersectsPlayerBox(world, doorBase, playerBox)) {
                return true;
            }
        }

        return false;
    }

    private static boolean openDoorIntersectsPlayerBox(ClientWorld world, BlockPos doorBase, Box playerBox) {
        return openDoorPartIntersectsPlayerBox(world, doorBase, playerBox)
                || openDoorPartIntersectsPlayerBox(world, doorBase.up(), playerBox);
    }

    private static boolean openDoorPartIntersectsPlayerBox(ClientWorld world, BlockPos pos, Box playerBox) {
        if (!isLoaded(world, pos)) {
            return false;
        }

        BlockState state = world.getBlockState(pos);

        if (!isOpenHandOpenableDoor(state)) {
            return false;
        }

        VoxelShape collision = state.getCollisionShape(world, pos);
        if (collision.isEmpty()) {
            return false;
        }

        for (Box localBox : collision.getBoundingBoxes()) {
            Box worldBox = localBox.offset(pos.getX(), pos.getY(), pos.getZ());

            if (worldBox.intersects(playerBox)) {
                return true;
            }
        }

        return false;
    }

    private static Box sweptPlayerBox(
            double fromX,
            double fromFeetY,
            double fromZ,
            double toX,
            double toFeetY,
            double toZ
    ) {
        Box from = makePlayerCollisionBox(fromX, fromFeetY, fromZ);
        Box to = makePlayerCollisionBox(toX, toFeetY, toZ);

        return new Box(
                Math.min(from.minX, to.minX),
                Math.min(from.minY, to.minY),
                Math.min(from.minZ, to.minZ),
                Math.max(from.maxX, to.maxX),
                Math.max(from.maxY, to.maxY),
                Math.max(from.maxZ, to.maxZ)
        );
    }

    private static boolean tryUseDoorOnPath(MinecraftClient client, ClientWorld world, long[] path, BlockPos waypoint) {
        if (client.player == null || client.interactionManager == null || autoClickCooldown > 0) {
            return false;
        }

        Direction pathDirection = getCurrentAutoPathDirection(client, path, waypoint);
        if (pathDirection == null) {
            return false;
        }

        BlockPos playerFeet = client.player.getBlockPos();
        BlockPos doorBase = findClosedDoorOnPathSegment(world, playerFeet, waypoint, pathDirection);

        if (doorBase == null) {
            return false;
        }

        Vec3d hitPos = getDoorClickPoint(doorBase);

        double reach = client.player.getBlockInteractionRange() + REACH_PADDING;
        if (client.player.getEyePos().squaredDistanceTo(hitPos) > reach * reach) {
            return false;
        }

        releaseAutoMovement(client);
        lookAt(client, hitPos);
        rightClickBlock(client, world, doorBase, hitPos);

        autoClickCooldown = AUTO_CLICK_COOLDOWN_TICKS;
        return true;
    }

    private static Direction getCurrentAutoPathDirection(MinecraftClient client, long[] path, BlockPos waypoint) {
        if (client.player == null) {
            return null;
        }

        if (path != null && path.length >= 2) {
            int index = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);

            for (int i = index; i > 0; i--) {
                BlockPos from = BlockPos.fromLong(path[i - 1]);
                BlockPos to = BlockPos.fromLong(path[i]);

                Direction direction = horizontalDirectionBetween(from, to);
                if (direction != null) {
                    return direction;
                }
            }
        }

        Vec3d playerPos = client.player.getPos();

        double dx = waypoint.getX() + 0.5 - playerPos.x;
        double dz = waypoint.getZ() + 0.5 - playerPos.z;

        if (Math.abs(dx) < 0.05 && Math.abs(dz) < 0.05) {
            return null;
        }

        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0.0 ? Direction.EAST : Direction.WEST;
        }

        return dz > 0.0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static Direction horizontalDirectionBetween(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();

        if (dx == 0 && dz == 0) {
            return null;
        }

        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }

        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static BlockPos findClosedDoorOnPathSegment(
            ClientWorld world,
            BlockPos from,
            BlockPos waypoint,
            Direction pathDirection
    ) {
        BlockPos door;

        door = getClosedDoorBaseAt(world, waypoint, pathDirection);
        if (door != null) {
            return door;
        }

        door = getClosedDoorBaseAt(world, waypoint.up(), pathDirection);
        if (door != null) {
            return door;
        }

        door = getClosedDoorBaseAt(world, waypoint.down(), pathDirection);
        if (door != null) {
            return door;
        }

        door = getClosedDoorBaseAt(world, from, pathDirection);
        if (door != null) {
            return door;
        }

        door = getClosedDoorBaseAt(world, from.up(), pathDirection);
        if (door != null) {
            return door;
        }

        return getClosedDoorBaseAt(world, from.down(), pathDirection);
    }

    private static BlockPos getClosedDoorBaseAt(ClientWorld world, BlockPos pos, Direction pathDirection) {
        if (!isLoaded(world, pos)) {
            return null;
        }

        BlockState state = world.getBlockState(pos);

        if (!isClosedDoorBlockingPath(state, pathDirection)) {
            return null;
        }

        BlockPos base = getDoorBasePos(pos, state);

        if (!isLoaded(world, base)) {
            return null;
        }

        BlockState baseState = world.getBlockState(base);

        if (!isClosedDoorBlockingPath(baseState, pathDirection)) {
            return null;
        }

        return base;
    }

    private static boolean isClosedDoorBlockingPath(BlockState state, Direction pathDirection) {
        return isClosedHandOpenableDoor(state)
                && state.contains(DoorBlock.FACING)
                && state.get(DoorBlock.FACING).getAxis() == pathDirection.getAxis();
    }

    private static boolean isClosedHandOpenableDoor(BlockState state) {
        return isHandOpenableDoor(state)
                && state.contains(DoorBlock.OPEN)
                && !state.get(DoorBlock.OPEN)
                && state.contains(DoorBlock.HALF);
    }

    private static boolean isOpenHandOpenableDoor(BlockState state) {
        return isHandOpenableDoor(state)
                && state.contains(DoorBlock.OPEN)
                && state.get(DoorBlock.OPEN)
                && state.contains(DoorBlock.HALF);
    }

    private static BlockPos getDoorBasePos(BlockPos pos, BlockState state) {
        return state.contains(DoorBlock.HALF) && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                ? pos.down()
                : pos;
    }

    private static Vec3d getDoorClickPoint(BlockPos doorBase) {
        return new Vec3d(doorBase.getX() + 0.5, doorBase.getY() + 0.75, doorBase.getZ() + 0.5);
    }

    private static void rightClickBlock(MinecraftClient client, ClientWorld world, BlockPos blockPos, Vec3d hitPos) {
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        BlockHitResult hit = world.raycast(new RaycastContext(
                client.player.getEyePos(),
                hitPos,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                ShapeContext.absent()
        ));

        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(blockPos)) {
            hit = new BlockHitResult(hitPos, Direction.UP, blockPos, false);
        }

        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private static void lookAt(MinecraftClient client, Vec3d target) {
        if (client.player == null) {
            return;
        }

        Vec3d eye = client.player.getEyePos();

        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;

        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));

        pitch = MathHelper.clamp(pitch, -90.0f, 90.0f);

        client.player.setYaw(yaw);
        client.player.setPitch(pitch);
    }

    private static void applySideCorrection(MinecraftClient client, long[] path) {
        if (client.player == null) {
            return;
        }

        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);

        if (!client.player.isOnGround()) {
            return;
        }

        if (path.length < 2) {
            resetSideCorrection();
            return;
        }

        int currentIndex = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);

        if (AUTO_SIDE_CORRECTION_SEGMENT != currentIndex) {
            AUTO_SIDE_CORRECTION_SEGMENT = currentIndex;
            AUTO_SIDE_CORRECTION_DIR = 0;
        }

        BlockPos previous = BlockPos.fromLong(path[currentIndex - 1]);
        BlockPos current = BlockPos.fromLong(path[currentIndex]);

        int dxBlocks = Math.abs(current.getX() - previous.getX());
        int dzBlocks = Math.abs(current.getZ() - previous.getZ());

        if (dxBlocks != 0 && dzBlocks != 0) {
            resetSideCorrection();
            return;
        }

        Vec3d a = nodeCenter(previous);
        Vec3d b = nodeCenter(current);
        Vec3d p = client.player.getPos();

        double segmentX = b.x - a.x;
        double segmentZ = b.z - a.z;

        double lenSq = segmentX * segmentX + segmentZ * segmentZ;
        if (lenSq < 0.0001) {
            resetSideCorrection();
            return;
        }

        double playerX = p.x - a.x;
        double playerZ = p.z - a.z;

        double side = (segmentX * playerZ - segmentZ * playerX) / Math.sqrt(lenSq);

        if (AUTO_SIDE_CORRECTION_DIR == 0) {
            if (side > PATH_SIDE_CORRECTION_ENGAGE) {
                AUTO_SIDE_CORRECTION_DIR = -1;
            } else if (side < -PATH_SIDE_CORRECTION_ENGAGE) {
                AUTO_SIDE_CORRECTION_DIR = 1;
            }
        } else if (Math.abs(side) < PATH_SIDE_CORRECTION_RELEASE) {
            AUTO_SIDE_CORRECTION_DIR = 0;
        } else if (AUTO_SIDE_CORRECTION_DIR == -1 && side < -PATH_SIDE_CORRECTION_SWITCH) {
            AUTO_SIDE_CORRECTION_DIR = 1;
        } else if (AUTO_SIDE_CORRECTION_DIR == 1 && side > PATH_SIDE_CORRECTION_SWITCH) {
            AUTO_SIDE_CORRECTION_DIR = -1;
        }

        if (AUTO_SIDE_CORRECTION_DIR < 0) {
            client.options.leftKey.setPressed(true);
        } else if (AUTO_SIDE_CORRECTION_DIR > 0) {
            client.options.rightKey.setPressed(true);
        }
    }

    private static void resetSideCorrection() {
        AUTO_SIDE_CORRECTION_DIR = 0;
        AUTO_SIDE_CORRECTION_SEGMENT = -1;
    }

    private static void tickZombieMotionEstimator(MinecraftClient client, ClientWorld world) {
        if (client.player == null || world == null) {
            LAST_ZOMBIE_POSITIONS.clear();
            ZOMBIE_ESTIMATED_VELOCITIES.clear();
            return;
        }

        Box searchBox = client.player.getBoundingBox().expand(ZOMBIE_SHOOT_RANGE + 8.0);
        List<ZombieEntity> zombies = world.getEntitiesByType(
                TypeFilter.instanceOf(ZombieEntity.class),
                searchBox,
                zombie -> zombie != null && zombie.isAlive() && !zombie.isRemoved()
        );

        HashSet<Integer> seenIds = new HashSet<>();

        for (ZombieEntity zombie : zombies) {
            int id = zombie.getId();
            seenIds.add(id);

            Vec3d currentPos = zombie.getPos();
            Vec3d previousPos = LAST_ZOMBIE_POSITIONS.put(id, currentPos);

            if (previousPos == null) {
                ZOMBIE_ESTIMATED_VELOCITIES.put(id, Vec3d.ZERO);
                continue;
            }

            Vec3d rawVelocity = currentPos.subtract(previousPos);
            rawVelocity = new Vec3d(rawVelocity.x, 0.0, rawVelocity.z);

            Vec3d previousVelocity = ZOMBIE_ESTIMATED_VELOCITIES.getOrDefault(id, Vec3d.ZERO);
            Vec3d smoothedVelocity = previousVelocity.multiply(1.0 - ZOMBIE_VELOCITY_BLEND)
                    .add(rawVelocity.multiply(ZOMBIE_VELOCITY_BLEND));

            ZOMBIE_ESTIMATED_VELOCITIES.put(
                    id,
                    clampHorizontalLead(smoothedVelocity, ZOMBIE_MAX_HORIZONTAL_LEAD)
            );
        }

        LAST_ZOMBIE_POSITIONS.entrySet().removeIf(entry -> !seenIds.contains(entry.getKey()));
        ZOMBIE_ESTIMATED_VELOCITIES.entrySet().removeIf(entry -> !seenIds.contains(entry.getKey()));
    }

    private static Vec3d getBestZombieAimPoint(ClientWorld world, Vec3d eye, ZombieEntity zombie) {
        Vec3d predictedHead = getPredictedZombieHeadTarget(zombie);
        if (hasSafeClearShot(world, eye, predictedHead)) {
            return predictedHead;
        }

        Vec3d predictedChest = getPredictedZombieChestTarget(zombie);
        if (hasSafeClearShot(world, eye, predictedChest)) {
            return predictedChest;
        }

        return null;
    }

    private static Vec3d getPredictedZombieHeadTarget(ZombieEntity zombie) {
        Vec3d lead = getEstimatedZombieHorizontalLead(zombie);
        Box box = zombie.getBoundingBox();

        double aimY = MathHelper.clamp(
                zombie.getEyeY() - 0.08,
                box.minY + 0.90,
                box.maxY - 0.05
        );

        return new Vec3d(
                zombie.getPos().x + lead.x,
                aimY,
                zombie.getPos().z + lead.z
        );
    }

    private static Vec3d getPredictedZombieChestTarget(ZombieEntity zombie) {
        Vec3d lead = getEstimatedZombieHorizontalLead(zombie);
        Box box = zombie.getBoundingBox();
        double aimY = box.minY + (box.maxY - box.minY) * 0.72;

        return new Vec3d(
                zombie.getPos().x + lead.x,
                aimY,
                zombie.getPos().z + lead.z
        );
    }

    private static Vec3d getEstimatedZombieHorizontalLead(ZombieEntity zombie) {
        Vec3d velocity = ZOMBIE_ESTIMATED_VELOCITIES.getOrDefault(zombie.getId(), Vec3d.ZERO);
        return clampHorizontalLead(
                velocity.multiply(ZOMBIE_LEAD_TICKS),
                ZOMBIE_MAX_HORIZONTAL_LEAD
        );
    }

    private static Vec3d clampHorizontalLead(Vec3d value, double maxLength) {
        double lenSq = value.x * value.x + value.z * value.z;
        if (lenSq <= maxLength * maxLength) {
            return new Vec3d(value.x, 0.0, value.z);
        }

        double scale = maxLength / Math.sqrt(lenSq);
        return new Vec3d(value.x * scale, 0.0, value.z * scale);
    }

    private static boolean hasSafeClearShot(ClientWorld world, Vec3d from, Vec3d to) {
        if (!hasClearShot(world, from, to)) {
            return false;
        }

        Vec3d dir = to.subtract(from);
        double dirLenSq = dir.lengthSquared();
        if (dirLenSq < 1.0E-6) {
            return false;
        }
        dir = dir.multiply(1.0 / Math.sqrt(dirLenSq));

        Vec3d right = new Vec3d(-dir.z, 0.0, dir.x);
        double rightLenSq = right.lengthSquared();
        if (rightLenSq < 1.0E-6) {
            right = new Vec3d(1.0, 0.0, 0.0);
        } else {
            right = right.multiply(1.0 / Math.sqrt(rightLenSq));
        }

        Vec3d up = dir.crossProduct(right);
        double upLenSq = up.lengthSquared();
        if (upLenSq < 1.0E-6) {
            up = new Vec3d(0.0, 1.0, 0.0);
        } else {
            up = up.multiply(1.0 / Math.sqrt(upLenSq));
        }

        return hasClearShot(world, from, to.add(right.multiply(SAFE_SHOT_LATERAL_MARGIN)))
                && hasClearShot(world, from, to.add(right.multiply(-SAFE_SHOT_LATERAL_MARGIN)))
                && hasClearShot(world, from, to.add(up.multiply(SAFE_SHOT_VERTICAL_MARGIN)))
                && hasClearShot(world, from, to.add(up.multiply(-SAFE_SHOT_VERTICAL_MARGIN)));
    }

    private static boolean isZombieVeryClose(MinecraftClient client, ClientWorld world) {
        if (client.player == null || world == null) {
            return false;
        }

        Box searchBox = client.player.getBoundingBox().expand(
                FOOD_CANCEL_NEAR_ZOMBIE_RANGE,
                1.75,
                FOOD_CANCEL_NEAR_ZOMBIE_RANGE
        );

        List<ZombieEntity> zombies = world.getEntitiesByType(
                TypeFilter.instanceOf(ZombieEntity.class),
                searchBox,
                zombie -> isShootableZombie(client, zombie)
        );

        for (ZombieEntity zombie : zombies) {
            if (client.player.squaredDistanceTo(zombie) <= FOOD_CANCEL_NEAR_ZOMBIE_RANGE_SQ) {
                return true;
            }
        }

        return false;
    }

    private static boolean isEnteringLowJumpClearanceAhead(MinecraftClient client, ClientWorld world, long[] path) {
        if (client.player == null || world == null || path == null || path.length == 0) {
            return false;
        }

        PathWorldView pathWorld = new LivePathWorld(world);
        Vec3d playerPos = client.player.getPos();

        if (hasLowJumpClearanceAt(pathWorld, playerPos.x, playerPos.y, playerPos.z)) {
            return false;
        }

        if (path.length < 2) {
            return false;
        }

        int index = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);
        double remaining = AUTO_LOW_CLEARANCE_JUMP_SUPPRESS_LOOKAHEAD;

        for (int i = index; i < path.length && remaining > 0.0; i++) {
            BlockPos fromNode = BlockPos.fromLong(path[i - 1]);
            BlockPos toNode = BlockPos.fromLong(path[i]);
            Vec3d a = nodeCenter(fromNode);
            Vec3d b = nodeCenter(toNode);

            double abX = b.x - a.x;
            double abZ = b.z - a.z;
            double len = Math.sqrt(abX * abX + abZ * abZ);
            if (len < 0.0001) {
                continue;
            }

            double startT = i == index ? horizontalProjectionT(playerPos, a, b) : 0.0;
            double segmentDistance = len * (1.0 - startT);
            if (segmentDistance <= 0.0) {
                continue;
            }

            double scanDistance = Math.min(segmentDistance, remaining);
            int samples = Math.max(1, (int) Math.ceil(scanDistance / AUTO_LOW_CLEARANCE_SAMPLE_SPACING));

            for (int sample = 1; sample <= samples; sample++) {
                double distance = scanDistance * sample / samples;
                double t = startT + distance / len;
                Vec3d point = lerp(a, b, t);

                if (hasLowJumpClearanceAt(pathWorld, point.x, point.y, point.z)) {
                    return true;
                }
            }

            remaining -= scanDistance;
        }

        return false;
    }

    private static boolean hasLowJumpClearanceAt(PathWorldView world, double centerX, double feetY, double centerZ) {
        return isPlayerBoxClearAt(world, centerX, feetY, centerZ)
                && !isPlayerBoxClearAt(world, centerX, feetY + JUMP_HEADROOM_RISE, centerZ);
    }

    private static boolean isSprintJumpSegmentSuitable(MinecraftClient client, ClientWorld world, long[] path) {
        if (client.player == null || world == null || path == null || path.length < 2) {
            return false;
        }

        int index = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);

        BlockPos from = BlockPos.fromLong(path[index - 1]);
        BlockPos to = BlockPos.fromLong(path[index]);

        double fromFeetY = getNodeFeetY(world, from);
        double toFeetY = getNodeFeetY(world, to);

        if (Double.isNaN(fromFeetY) || Double.isNaN(toFeetY)) {
            return false;
        }

        double rise = toFeetY - fromFeetY;
        if (rise > AUTO_SPRINT_JUMP_MAX_RISE || rise < -AUTO_SPRINT_JUMP_MAX_DROP) {
            return false;
        }

        if (index < path.length - 1) {
            Vec3d a = nodeCenter(from);
            Vec3d b = nodeCenter(to);
            Vec3d c = nodeCenter(BlockPos.fromLong(path[index + 1]));

            double abX = b.x - a.x;
            double abZ = b.z - a.z;
            double bcX = c.x - b.x;
            double bcZ = c.z - b.z;

            double abLen = Math.sqrt(abX * abX + abZ * abZ);
            double bcLen = Math.sqrt(bcX * bcX + bcZ * bcZ);

            if (abLen > 1.0E-4 && bcLen > 1.0E-4) {
                double dot = (abX * bcX + abZ * bcZ) / (abLen * bcLen);
                if (dot < AUTO_SPRINT_JUMP_SHARP_TURN_DOT) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean shouldAutoSprintJump(
            MinecraftClient client,
            ClientWorld world,
            long[] path,
            float yawError
    ) {
        if (client.player == null || world == null || path == null || path.length < 2) {
            return false;
        }

        if (!client.player.isOnGround() || client.player.horizontalCollision || client.player.isTouchingWater()) {
            return false;
        }

        if (isAutoInDropTransition(client, world, path)) {
            return false;
        }

        if (isNearSharpTurn(client, path)) {
            return false;
        }

        if (Math.abs(yawError) > AUTO_SPRINT_JUMP_YAW_TOLERANCE) {
            return false;
        }

        Vec3d velocity = client.player.getVelocity();
        double horizontalSpeedSq = velocity.x * velocity.x + velocity.z * velocity.z;
        if (horizontalSpeedSq < AUTO_SPRINT_JUMP_MIN_SPEED_SQ) {
            return false;
        }

        if (!isSprintJumpSegmentSuitable(client, world, path)) {
            return false;
        }

        int index = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);
        BlockPos waypoint = BlockPos.fromLong(path[index]);
        Vec3d waypointCenter = nodeCenter(waypoint);

        if (horizontalDistanceSq(client.player.getPos(), waypointCenter) < AUTO_SPRINT_JUMP_MIN_WAYPOINT_DIST_SQ) {
            return false;
        }

        PathWorldView pathWorld = new LivePathWorld(world);
        boolean currentLowJumpClearance = hasLowJumpClearanceAt(
                pathWorld,
                client.player.getX(),
                client.player.getY(),
                client.player.getZ()
        );

        if (!currentLowJumpClearance && !isPlayerBoxClearAt(
                pathWorld,
                client.player.getX(),
                client.player.getY() + JUMP_HEADROOM_RISE,
                client.player.getZ()
        )) {
            return false;
        }

        Vec3d look = client.player.getRotationVec(1.0f);
        double lookLenSq = look.x * look.x + look.z * look.z;
        if (lookLenSq > 1.0E-6) {
            double invLookLen = 1.0 / Math.sqrt(lookLenSq);
            double probeX = client.player.getX() + look.x * invLookLen * 0.30;
            double probeZ = client.player.getZ() + look.z * invLookLen * 0.30;
            boolean probeLowJumpClearance = hasLowJumpClearanceAt(pathWorld, probeX, client.player.getY(), probeZ);

            if (!currentLowJumpClearance && probeLowJumpClearance) {
                return false;
            }

            if (!probeLowJumpClearance && !isPlayerBoxClearAt(
                    pathWorld,
                    probeX,
                    client.player.getY() + JUMP_HEADROOM_RISE,
                    probeZ
            )) {
                return false;
            }
        }

        return true;
    }
    private static boolean isSharpTurnForSprintJump(Vec3d a, Vec3d b, Vec3d c) {
        double abX = b.x - a.x;
        double abZ = b.z - a.z;
        double bcX = c.x - b.x;
        double bcZ = c.z - b.z;

        double abLen = Math.sqrt(abX * abX + abZ * abZ);
        double bcLen = Math.sqrt(bcX * bcX + bcZ * bcZ);

        if (abLen < 1.0E-4 || bcLen < 1.0E-4) {
            return false;
        }

        double dot = (abX * bcX + abZ * bcZ) / (abLen * bcLen);
        return dot < AUTO_SPRINT_JUMP_SHARP_TURN_DOT;
    }

    private static boolean isNearSharpTurn(MinecraftClient client, long[] path) {
        if (client.player == null || path == null || path.length < 3) {
            return false;
        }

        int index = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);
        Vec3d playerPos = client.player.getPos();

        // upcoming turn at current waypoint
        if (index < path.length - 1) {
            Vec3d a = nodeCenter(BlockPos.fromLong(path[index - 1]));
            Vec3d b = nodeCenter(BlockPos.fromLong(path[index]));
            Vec3d c = nodeCenter(BlockPos.fromLong(path[index + 1]));

            if (isSharpTurnForSprintJump(a, b, c)
                    && horizontalDistanceSq(playerPos, b) <= AUTO_SPRINT_JUMP_TURN_SUPPRESS_DISTANCE_SQ) {
                return true;
            }
        }

        // just-passed turn, because waypoint may already have advanced
        if (index > 1) {
            Vec3d a = nodeCenter(BlockPos.fromLong(path[index - 2]));
            Vec3d b = nodeCenter(BlockPos.fromLong(path[index - 1]));
            Vec3d c = nodeCenter(BlockPos.fromLong(path[index]));

            if (isSharpTurnForSprintJump(a, b, c)
                    && horizontalDistanceSq(playerPos, b) <= AUTO_SPRINT_JUMP_TURN_SUPPRESS_DISTANCE_SQ) {
                return true;
            }
        }

        return false;
    }
}

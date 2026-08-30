package unblonded.sahil;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import org.lwjgl.glfw.GLFW;
import unblonded.sahil.cmds.AutoTrade;
import unblonded.sahil.cmds.ChestCycleCmd;
import unblonded.sahil.cmds.CommandManager;
import unblonded.sahil.cmds.StopAll;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class Sahil implements ClientModInitializer {
    CopyOnWriteArrayList<Trade> trades = new CopyOnWriteArrayList<>();
    public static boolean autoTrade = false;
    private static boolean nextVillagerSessionQueued = false;
    private final Map<Integer, Integer> localUsesThisSession = new HashMap<>();
    public static MinecraftClient client;

    public static VillagerEntity currentTradingTarget = null;

    // Baritone hand-off: set once we've told Baritone to walk somewhere,
    // cleared once it's done pathing (success or failure).
    public static VillagerEntity pendingBaritoneArrival = null;
    private static BlockPos pendingBaritoneTarget = null;
    private static final double ARRIVAL_DISTANCE = 3.0; // how close counts as "actually arrived" vs "gave up"

    public static VillagerEntity pendingInteractTarget = null;
    public static int settleTicksStable = 0;
    public static final int SETTLE_TICKS_REQUIRED = 6;
    private static final float LOOK_SPEED = 8.0f;
    private static final float LOOK_TOLERANCE = 3.0f;

    private static final int LOW_RESOURCE_THRESHOLD = 128;

    public static Runnable onAllVillagersDone = null;

    public static final Set<UUID> tradedVillagers = new HashSet<>();
    // Optional blacklist box — if set, any interact position inside this box will be skipped.
    private static Box blacklistBox = null;

    public static boolean resupplyPending = false;
        public static boolean isResupplying = false;

    /** Force-start a resupply immediately (clears pending and any resupply flags). */
    public static void forceStartResupply() {
        System.out.println("Force-starting resupply (graceful)");
        resupplyPending = false;
        isResupplying = false;
        startResupply();
    }

    private static final List<TradeRule> RULES = List.of(
            new TradeRule(Items.ROTTEN_FLESH, 32, Items.EMERALD),
            new TradeRule(Items.EMERALD, 3, Items.EXPERIENCE_BOTTLE)
    );


    KeyBinding tradeKeyBinding;

    @Override
    public void onInitializeClient() {
        client = MinecraftClient.getInstance();

        CommandManager.register(new AutoTrade());
        CommandManager.register(new ChestCycleCmd());
        CommandManager.register(new StopAll());
        CommandManager.init();

        tradeKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.sahil.execute_trade",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_GRAVE_ACCENT,
                KeyBinding.Category.MISC
        ));

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof MerchantScreen merchantScreen && client.player != null && client.world != null) {
                localUsesThisSession.clear();
                trades.clear();

                Box reachBox = client.player.getBoundingBox().expand(6);
                client.world.getEntitiesByClass(VillagerEntity.class, reachBox, e -> RestockDetector.glowingVillagerIds.contains(e.getId()))
                        .forEach(RestockDetector::removeGlow);

                boolean[] printed = {false};

                ScreenEvents.beforeRender(screen).register((s, drawContext, mouseX, mouseY, tickDelta) -> {
                    MerchantScreenHandler handler = merchantScreen.getScreenHandler();
                    if (!printed[0] && !handler.getRecipes().isEmpty()) {
                        printed[0] = true;
                        captureAndPrintTrades(handler.getRecipes());
                    }
                });
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ChestCycle.tick(client);

            if (pendingBaritoneArrival != null) {
                handleBaritoneArrivalCheck(client);
                return; // don't do anything else while Baritone is walking
            }

            if (pendingInteractTarget != null) {
                handleArrivalLook(client);
                return; // don't run anything else while settling into the interact
            }

            while (tradeKeyBinding.wasPressed()) {
                if (client.currentScreen instanceof MerchantScreen manualScreen) {
                    MerchantScreenHandler manualHandler = manualScreen.getScreenHandler();
                    int manualIndex = findMatchingTradeIndex(client, manualHandler, manualHandler.getRecipes(), localUsesThisSession);
                    if (manualIndex != -1) {
                        selectTrade(client, manualHandler, manualIndex);
                        confirmTrade(client, manualHandler);
                        localUsesThisSession.merge(manualIndex, 1, Integer::sum);
                        System.out.println("Manual trade executed via keybind, index " + manualIndex);
                    } else {
                        System.out.println("Manual trade keybind pressed but no matching trade found");
                    }
                }
            }

            if (!autoTrade) return;
            if (!(client.currentScreen instanceof MerchantScreen merchantScreen)) return;
            MerchantScreenHandler handler = merchantScreen.getScreenHandler();

            int index = findMatchingTradeIndex(client, handler, handler.getRecipes(), localUsesThisSession);
            if (index != -1) {
                selectTrade(client, handler, index);
                confirmTrade(client, handler);
                localUsesThisSession.merge(index, 1, Integer::sum);
                System.out.println("Executed trade index " + index);
            } else {
                System.out.println("No more matching trades — closing screen");
                if (currentTradingTarget != null) {
                    tradedVillagers.add(currentTradingTarget.getUuid());
                    System.out.println("Marked traded: " + currentTradingTarget.getUuid());
                    currentTradingTarget = null;
                }
                client.player.closeHandledScreen();

                if (countItem(client, Items.ROTTEN_FLESH) < LOW_RESOURCE_THRESHOLD) {
                    System.out.println("Low on flesh after this villager — running resupply");
                    autoTrade = false;
                    if (ChestCycle.isRunning()) {
                        // Graceful: request resupply and let ChestCycle stop at next safe point
                        System.out.println("ChestCycle already running; requesting graceful resupply when safe");
                        resupplyPending = true;
                    } else {
                        // No cycle running: start resupply immediately
                        resupplyPending = false;
                        startResupply();
                    }
                } else {
                    queueNextVillagerSession();
                }
            }
        });
    }

    public static void startResupply() {
        if (isResupplying) {
            System.out.println("Resupply already in progress, skipping");
            resupplyPending = true;
            return;
        }

        if (ChestCycle.isRunning()) {
            System.out.println("Chest cycle already running, waiting...");
            resupplyPending = true;
            return;
        }

        isResupplying = true;
        resupplyPending = false;

        ChestCycle.startCycle(() -> {
            // This runs when the cycle completes
            System.out.println("Resupply complete!");
            isResupplying = false;

            // If there's a pending resupply request, start it immediately
            if (resupplyPending) {
                System.out.println("Starting pending resupply...");
                resupplyPending = false;
                startResupply();
            } else {
                // Resume trading
                autoTrade = true;
                queueNextVillagerSession();
            }
        });
    }

    private static void queueNextVillagerSession() {
        if (!autoTrade || nextVillagerSessionQueued || client == null || client.player == null) return;
        System.out.println("Queueing next villager session");

        nextVillagerSessionQueued = true;
        client.execute(() -> {
            try {
                boolean baritoneBusy = BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing();
                if (autoTrade && !baritoneBusy && !(client.currentScreen instanceof MerchantScreen)) {
                    startNextVillagerSession();
                }
            } finally {
                nextVillagerSessionQueued = false;
            }
        });
    }

    private static int countItem(MinecraftClient client, Item item) {
        int count = 0;
        for (ItemStack stack : client.player.getInventory().getMainStacks()) {
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    private static int findMatchingTradeIndex(MinecraftClient client, MerchantScreenHandler handler, TradeOfferList recipes, Map<Integer, Integer> localUses) {
        for (TradeRule rule : RULES) {
            for (int i = 0; i < recipes.size(); i++) {
                TradeOffer offer = recipes.get(i);
                if (offer.isDisabled()) continue;

                int knownUses = offer.getUses() + localUses.getOrDefault(i, 0);
                if (knownUses >= offer.getMaxUses()) continue;

                ItemStack buy1 = offer.getFirstBuyItem().itemStack();
                ItemStack buy2 = offer.getSecondBuyItem().map(t -> t.itemStack()).orElse(ItemStack.EMPTY);
                ItemStack sell = offer.getSellItem();

                boolean buySideOk = rule.matchesBuySide(buy1) || rule.matchesBuySide(buy2);
                boolean sellSideOk = rule.matchesSellSide(sell);

                if (buySideOk && sellSideOk && hasBuyResources(client, offer)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean hasBuyResources(MinecraftClient client, TradeOffer offer) {
        if (client.player == null) return false;

        ItemStack buy1 = offer.getFirstBuyItem().itemStack();
        ItemStack buy2 = offer.getSecondBuyItem().map(t -> t.itemStack()).orElse(ItemStack.EMPTY);

        boolean hasBuy1 = hasAtLeast(client, buy1);
        boolean hasBuy2 = buy2.isEmpty() || hasAtLeast(client, buy2);

        return hasBuy1 && hasBuy2;
    }

    private static boolean hasAtLeast(MinecraftClient client, ItemStack required) {
        if (required.isEmpty()) return true;

        int needed = required.getCount();
        int have = 0;

        for (ItemStack stack : client.player.getInventory().getMainStacks()) {
            if (stack.isOf(required.getItem())) {
                have += stack.getCount();
            }
        }

        return have >= needed;
    }

    public static void selectTrade(MinecraftClient client, MerchantScreenHandler handler, int tradeIndex) {
        if (client.player == null) return;

        TradeOfferList recipes = handler.getRecipes();
        if (tradeIndex < 0 || tradeIndex >= recipes.size()) return;

        TradeOffer offer = recipes.get(tradeIndex);
        if (offer.isDisabled()) return;

        client.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(tradeIndex));
        handler.setRecipeIndex(tradeIndex);
    }

    public static void confirmTrade(MinecraftClient client, MerchantScreenHandler handler) {
        if (client.player == null || client.interactionManager == null) return;

        client.interactionManager.clickSlot(
                handler.syncId,
                2,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );
    }

    private void captureAndPrintTrades(TradeOfferList recipes) {
        for (TradeOffer offer : recipes) {
            ItemStack buy1Stack = offer.getFirstBuyItem().itemStack();
            ItemStack buy2Stack = offer.getSecondBuyItem().map(t -> t.itemStack()).orElse(ItemStack.EMPTY);
            ItemStack sellStack = offer.getSellItem();

            Trade trade = buy2Stack.isEmpty() ? new Trade(buy1Stack, sellStack) : new Trade(buy1Stack, buy2Stack, sellStack);
            trades.add(trade);
        }
    }

    /**
     * Picks the next untraded villager (serpentine order) with a valid interactable
     * position. Doesn't pre-validate a path — Baritone does that itself when we hand
     * it the goal; if it can't get there, handleBaritoneArrivalCheck marks it unreachable.
     */
    public static VillagerEntity findBestReachableVillager(double radius, int maxDistance) {
        if (client.player == null || client.world == null) return null;

        Box searchBox = client.player.getBoundingBox().expand(radius);
        List<VillagerEntity> nearby = new ArrayList<>(client.world.getEntitiesByClass(
                VillagerEntity.class, searchBox,
                v -> !Sahil.tradedVillagers.contains(v.getUuid())
        ));

        List<VillagerEntity> ordered = getVillagersInSerpentineOrder(nearby);

        for (VillagerEntity v : ordered) {
            BlockPos interactPos = findInteractablePositionNear(v.getBlockPos(), maxDistance);
            if (interactPos == null) {
                System.out.println("SKIP " + v.getUuid() + " at " + v.getBlockPos() + " — no interactable position found");
                continue;
            }

            // Only target villagers that have a brewing stand within ~1.4 blocks
            boolean hasBrewing = false;
            double maxDistSq = 1.4 * 1.4;
            for (int dx = -1; dx <= 1 && !hasBrewing; dx++) {
                for (int dz = -1; dz <= 1 && !hasBrewing; dz++) {
                    for (int dy = -1; dy <= 1 && !hasBrewing; dy++) {
                        BlockPos check = v.getBlockPos().add(dx, dy, dz);
                        double distSq = check.getSquaredDistance(v.getBlockPos());
                        if (distSq > maxDistSq) continue;
                        var state = client.world.getBlockState(check);
                        if (state.getBlock() instanceof net.minecraft.block.BrewingStandBlock) {
                            hasBrewing = true;
                        }
                    }
                }
            }

            if (!hasBrewing) {
                System.out.println("SKIP " + v.getUuid() + " at " + v.getBlockPos() + " — no nearby brewing stand");
                continue;
            }

            return v;
        }

        System.out.println("No reachable villager found among " + ordered.size() + " candidates");
        return null;
    }

    public static BlockPos findInteractablePositionNear(BlockPos targetPos, int maxDistance) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || targetPos == null) return null;

        int[][] cardinalOffsets = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        List<BlockPos> candidates = new ArrayList<>();

        for (int[] dir : cardinalOffsets) {
            for (int dist = 1; dist <= maxDistance; dist++) {
                BlockPos candidate = targetPos.add(dir[0] * dist, 0, dir[1] * dist);
                boolean walkable = PathFinding.isWalkable(client.world, candidate);
                boolean clean = PathFinding.isCleanStandingSpot(client.world, candidate);
                boolean passable = PathFinding.isPassable(client.world, candidate);

                System.out.println("  dir=" + Arrays.toString(dir) + " dist=" + dist + " pos=" + candidate
                        + " block=" + client.world.getBlockState(candidate).getBlock()
                        + " walkable=" + walkable + " clean=" + clean + " passable=" + passable);

                if (walkable && clean) {
                    candidates.add(candidate);
                    break;
                }
                if (blocksOutwardSearch(client.world, candidate)) break;
            }
        }

        if (candidates.isEmpty()) return null;
        BlockPos playerPos = client.player.getBlockPos();
        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(playerPos)));
        System.out.println("Chosen interact pos: " + candidates.get(0));
        return candidates.get(0);
    }

    private static boolean blocksOutwardSearch(net.minecraft.client.world.ClientWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.getBlock() instanceof net.minecraft.block.BedBlock) return true;
        return state.isSolidBlock(world, pos);
    }

    /**
     * Simple local BFS-based reachability test that only considers walkable standing
     * positions (solid ground with feet/head clear). This is conservative and fast
     * and will reject positions inside fully enclosed pens.
     */
    private static boolean simplePathExists(BlockPos start, BlockPos goal, int maxNodes, int maxDistSq) {
        if (client == null || client.world == null || start == null || goal == null) {
            System.out.println("simplePathExists: invalid inputs start=" + start + " goal=" + goal);
            return false;
        }
        var world = client.world;

        boolean startWalkable = PathFinding.isWalkable(world, start);
        boolean goalWalkable = PathFinding.isWalkable(world, goal);
        System.out.println("simplePathExists: start=" + start + " walkable=" + startWalkable + " goal=" + goal + " walkable=" + goalWalkable);

        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(start);
        java.util.HashSet<Long> visited = new java.util.HashSet<>();
        visited.add(start.asLong());

        int nodes = 0;
        int[][] cardinal = { {1,0}, {-1,0}, {0,1}, {0,-1} };

        while (!q.isEmpty() && nodes < maxNodes) {
            BlockPos cur = q.poll();
            nodes++;
            if (cur.equals(goal)) {
                System.out.println("simplePathExists: reached goal, nodesVisited=" + nodes + " visitedSize=" + visited.size());
                return true;
            }

            for (int[] d : cardinal) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos cand = cur.add(d[0], dy, d[1]);
                    if (visited.contains(cand.asLong())) continue;
                    if (cand.getSquaredDistance(start) > maxDistSq) continue;
                    if (!PathFinding.isWalkable(world, cand)) continue;
                    visited.add(cand.asLong());
                    q.add(cand);
                }
            }
        }
        System.out.println("simplePathExists: failed to reach goal, nodesVisited=" + nodes + " visitedSize=" + visited.size());
        return false;
    }

    /**
     * Probe Baritone quickly to see if it believes a path exists to the given block.
     * This sets a short-lived custom goal and watches whether Baritone begins pathing.
     * The probe is run on a background thread and restored to the player's position
     * afterwards so it doesn't leave Baritone walking somewhere.
     */
    private static boolean baritonePathExists(BlockPos target, int timeoutMs) {
        try {
            var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone == null || client == null || client.player == null) return false;

            BlockPos playerPos = client.player.getBlockPos();
            GoalBlock probeGoal = new GoalBlock(target.getX(), target.getY(), target.getZ());

            final boolean[] found = {false};
            Thread t = new Thread(() -> {
                try {
                    // Give Baritone the probe goal
                    baritone.getCustomGoalProcess().setGoalAndPath(probeGoal);

                    long end = System.currentTimeMillis() + timeoutMs;
                    while (System.currentTimeMillis() < end) {
                        if (baritone.getPathingBehavior().isPathing()) {
                            found[0] = true;
                            break;
                        }
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    }
                } catch (Throwable ignored) {
                } finally {
                    // Cancel/restore by setting goal to player's current block so Baritone stops moving
                    try {
                        GoalBlock cancel = new GoalBlock(playerPos.getX(), playerPos.getY(), playerPos.getZ());
                        baritone.getCustomGoalProcess().setGoalAndPath(cancel);
                    } catch (Throwable ignored) {}
                }
            }, "sahil-baritone-probe");

            t.setDaemon(true);
            t.start();
            t.join(timeoutMs + 200);
            return found[0];
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<VillagerEntity> getVillagersInSerpentineOrder(List<VillagerEntity> villagers) {
        if (villagers.isEmpty()) return villagers;

        double minX = villagers.stream().mapToDouble(net.minecraft.entity.Entity::getX).min().orElse(0);
        double maxX = villagers.stream().mapToDouble(net.minecraft.entity.Entity::getX).max().orElse(0);
        double minZ = villagers.stream().mapToDouble(net.minecraft.entity.Entity::getZ).min().orElse(0);
        double maxZ = villagers.stream().mapToDouble(net.minecraft.entity.Entity::getZ).max().orElse(0);

        boolean useX = (maxX - minX) >= (maxZ - minZ);

        List<VillagerEntity> byY = new ArrayList<>(villagers);
        byY.sort((a, b) -> Double.compare(b.getY(), a.getY()));

        double LEVEL_THRESHOLD = 2.0;
        List<List<VillagerEntity>> rows = new ArrayList<>();
        for (VillagerEntity v : byY) {
            boolean placed = false;
            for (List<VillagerEntity> row : rows) {
                if (Math.abs(row.get(0).getY() - v.getY()) <= LEVEL_THRESHOLD) {
                    row.add(v);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                List<VillagerEntity> newRow = new ArrayList<>();
                newRow.add(v);
                rows.add(newRow);
            }
        }

        List<VillagerEntity> result = new ArrayList<>();
        boolean rightToLeft = true;
        Comparator<VillagerEntity> axisComparator = useX
                ? Comparator.comparingDouble(net.minecraft.entity.Entity::getX)
                : Comparator.comparingDouble(net.minecraft.entity.Entity::getZ);

        for (List<VillagerEntity> row : rows) {
            row.sort(rightToLeft ? axisComparator.reversed() : axisComparator);
            result.addAll(row);
            rightToLeft = !rightToLeft;
        }

        return result;
    }

    public static void startNextVillagerSession() {
        VillagerEntity target = findBestReachableVillager(32.0, 3);
        if (target == null) {
            System.out.println("No more reachable untraded villagers — stopping");
            if (onAllVillagersDone != null) {
                Runnable cb = onAllVillagersDone;
                onAllVillagersDone = null;
                cb.run();
            }
            return;
        }

        BlockPos interactPos = findInteractablePositionNear(target.getBlockPos(), 3);
        if (interactPos == null) {
            System.out.println("Lost interactable position for " + target.getUuid() + " — skipping");
            tradedVillagers.add(target.getUuid());
            startNextVillagerSession();
            return;
        }

        currentTradingTarget = target;
        pendingBaritoneArrival = target;
        pendingBaritoneTarget = interactPos;

        BaritoneAPI.getProvider().getPrimaryBaritone()
                .getCustomGoalProcess()
                .setGoalAndPath(new GoalBlock(interactPos.getX(), interactPos.getY(), interactPos.getZ()));

        System.out.println("Baritone walking to " + interactPos + " for villager " + target.getUuid());
    }

    private static void handleBaritoneArrivalCheck(MinecraftClient client) {
        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        boolean stillPathing = baritone.getPathingBehavior().isPathing();
        if (stillPathing) return; // keep waiting

        VillagerEntity target = pendingBaritoneArrival;
        BlockPos wanted = pendingBaritoneTarget;
        pendingBaritoneArrival = null;
        pendingBaritoneTarget = null;

        if (client.player == null || target == null) return;

        double dist = client.player.getEntityPos().distanceTo(wanted.toCenterPos());
        if (dist <= ARRIVAL_DISTANCE) {
            pendingInteractTarget = target;
            settleTicksStable = 0;
        } else {
            System.out.println("Baritone gave up before reaching " + target.getUuid() + " (dist " + dist + ") — marking unreachable");
            tradedVillagers.add(target.getUuid());
            queueNextVillagerSession();
        }
    }

    private static void handleArrivalLook(MinecraftClient client) {
        if (pendingInteractTarget == null || client.player == null) return;
        var player = client.player;

        Vec3d eyePos = player.getEyePos();
        Vec3d targetPos = pendingInteractTarget.getEyePos();
        double dx = targetPos.x - eyePos.x;
        double dy = targetPos.y - eyePos.y;
        double dz = targetPos.z - eyePos.z;
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizDist));

        float yawDiff = wrapDegrees(targetYaw - player.getYaw());
        float pitchDiff = targetPitch - player.getPitch();

        float yawStep = Math.min(LOOK_SPEED, Math.abs(yawDiff)) * Math.signum(yawDiff);
        float pitchStep = Math.min(LOOK_SPEED, Math.abs(pitchDiff)) * Math.signum(pitchDiff);

        player.setYaw(player.getYaw() + yawStep);
        player.setPitch(player.getPitch() + pitchStep);

        boolean facing = Math.abs(yawDiff) < LOOK_TOLERANCE && Math.abs(pitchDiff) < LOOK_TOLERANCE;

        if (facing) {
            settleTicksStable++;
            if (settleTicksStable >= SETTLE_TICKS_REQUIRED) {
                VillagerEntity target = pendingInteractTarget;
                pendingInteractTarget = null;
                player.swingHand(Hand.MAIN_HAND);
                client.interactionManager.interactEntity(player, target, Hand.MAIN_HAND);
            }
        } else {
            settleTicksStable = 0;
        }
    }

    private static float wrapDegrees(float degrees) {
        degrees = degrees % 360.0f;
        if (degrees >= 180.0f) degrees -= 360.0f;
        if (degrees < -180.0f) degrees += 360.0f;
        return degrees;
    }
}
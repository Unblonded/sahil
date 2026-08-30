package unblonded.sahil;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class ChestCycle {

    private enum State {
        IDLE,
        TELEPORT_HOME_DEPOSIT, WAIT_HOME_DEPOSIT,
        WALK_TO_DEPOSIT, ARRIVE_DEPOSIT, WAIT_DEPOSIT_SCREEN, DEPOSITING, CLOSE_DEPOSIT,
        WALK_TO_COLLECT, ARRIVE_COLLECT, WAIT_COLLECT_SCREEN, WITHDRAWING, CLOSE_COLLECT,
        TRADING,
        TELEPORT_HOME_RETURN, WAIT_HOME_RETURN,
        CHECK_COMPLETE
    }

    private static State state = State.IDLE;
    private static BlockPos depositChestPos;
    private static BlockPos collectChestPos;
    private static final Deque<Integer> pendingClicks = new ArrayDeque<>();
    private static int clickCooldown = 0;
    private static int homeWaitTicks = 0;
    private static int screenWaitTicks = 0;
    private static int cycleCount = 0;
    private static final int MAX_CYCLES = 3; // Run 3 cycles then stop

    private static final int CLICK_INTERVAL = 3;
    private static final int HOME_WAIT_TICKS = 60; // 3 seconds (20 ticks/sec)
    private static final int SCREEN_WAIT_TIMEOUT = 100;
    private static final int FLESH_THRESHOLD = 64; // Stop when we have this much flesh

    private static final List<Item> DEPOSIT_ITEMS = List.of(Items.EMERALD, Items.EXPERIENCE_BOTTLE);
    private static final Item PICKUP_ITEM = Items.ROTTEN_FLESH;
    private static Runnable onCycleComplete = null;

    public static void startCycle() {
        if (state != State.IDLE) {
            System.out.println("Cycle already running");
            return;
        }
        cycleCount = 0;
        System.out.println("Starting chest cycle - teleporting home to deposit");
        state = State.TELEPORT_HOME_DEPOSIT;
    }

    public static void startCycle(Runnable onComplete) {
        onCycleComplete = onComplete;
        startCycle();
    }

    public static void stop() {
        System.out.println("ChestCycle.stop(): cancelling and resetting cycle (no callback)");
        // Abort any running goal in Baritone
        try {
            var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && Sahil.client != null && Sahil.client.player != null) {
                BlockPos p = Sahil.client.player.getBlockPos();
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(p.getX(), p.getY(), p.getZ()));
            }
        } catch (Throwable ignored) {}

        // Reset state without running the onCycleComplete callback
        setIdle(false);

        pendingClicks.clear();
        clickCooldown = 0;
        homeWaitTicks = 0;
        screenWaitTicks = 0;
        cycleCount = 0;
        Sahil.autoTrade = false;
        Sahil.onAllVillagersDone = null;

        // Also clear Sahil's resupply flags so a subsequent resupply can start cleanly
        try {
            Sahil.resupplyPending = false;
            Sahil.isResupplying = false;
        } catch (Throwable ignored) {}
    }

    /**
     * Set the cycle to idle. If runCallback is true, execute the onCycleComplete callback
     * (used for normal completion). If false, clear the callback without running it
     * (used when we want to abort and start a fresh resupply immediately).
     */
    private static void setIdle(boolean runCallback) {
        System.out.println("setIdle(runCallback=" + runCallback + ")");
        state = State.IDLE;
        cycleCount = 0;
        if (runCallback) {
            if (onCycleComplete != null) {
                Runnable callback = onCycleComplete;
                onCycleComplete = null;
                callback.run();
            }
        } else {
            onCycleComplete = null;
        }
    }

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    public static void tick(MinecraftClient client) {
        switch (state) {
            case IDLE -> {}

            case TELEPORT_HOME_DEPOSIT -> {
                System.out.println("Teleporting home to deposit...");
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand("home 3");
                }
                homeWaitTicks = HOME_WAIT_TICKS;
                state = State.WAIT_HOME_DEPOSIT;
            }

            case WAIT_HOME_DEPOSIT -> {
                if (homeWaitTicks > 0) {
                    homeWaitTicks--;
                    return;
                }
                System.out.println("Arrived home, walking to deposit chest");
                state = State.WALK_TO_DEPOSIT;
            }

            case WALK_TO_DEPOSIT -> {
                depositChestPos = findLabeledChest("deposit", 32.0);
                if (depositChestPos == null) {
                    System.out.println("No deposit chest found — aborting cycle");
                                        setIdle(true);
                    return;
                }
                BlockPos interactPos = Sahil.findInteractablePositionNear(depositChestPos, 3);
                if (interactPos == null) {
                    System.out.println("Deposit chest unreachable — aborting cycle");
                                        setIdle(true);
                    return;
                }
                System.out.println("WALK_TO_DEPOSIT: goal=" + interactPos + " depositChestPos=" + depositChestPos);
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
                        .setGoalAndPath(new GoalBlock(interactPos.getX(), interactPos.getY(), interactPos.getZ()));
                state = State.ARRIVE_DEPOSIT;
            }

            case ARRIVE_DEPOSIT -> {
                boolean still = BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing();
                System.out.println("ARRIVE_DEPOSIT: pathing=" + still);
                if (still) return;
                System.out.println("ARRIVE_DEPOSIT: pathing finished — opening chest " + depositChestPos);
                openChest(client, depositChestPos);
                screenWaitTicks = 0;
                state = State.WAIT_DEPOSIT_SCREEN;
            }

            case WAIT_DEPOSIT_SCREEN -> {
                if (client.currentScreen instanceof GenericContainerScreen screen) {
                    queueDepositClicks(screen.getScreenHandler());
                    state = State.DEPOSITING;
                    return;
                }
                screenWaitTicks++;
                if (screenWaitTicks > SCREEN_WAIT_TIMEOUT) {
                    System.out.println("Deposit chest never opened — aborting cycle");
                                        setIdle(true);
                }
            }

            case DEPOSITING -> {
                if (!(client.currentScreen instanceof GenericContainerScreen)) {
                    pendingClicks.clear();
                    state = State.CLOSE_DEPOSIT;
                    return;
                }
                if (pendingClicks.isEmpty()) {
                    state = State.CLOSE_DEPOSIT;
                    return;
                }
                if (clickCooldown > 0) { clickCooldown--; return; }
                int slot = pendingClicks.poll();
                if (client.player != null && client.interactionManager != null) {
                    client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, slot, 0, SlotActionType.QUICK_MOVE, client.player);
                }
                clickCooldown = CLICK_INTERVAL;
            }

            case CLOSE_DEPOSIT -> {
                if (client.player != null) {
                    client.player.closeHandledScreen();
                }
                System.out.println("Deposit done, walking to collect chest");
                // If Sahil requested an immediate resupply, gracefully stop here and start it
                if (Sahil.resupplyPending) {
                    System.out.println("Resupply requested during cycle — stopping before walking to collect");
                    // abort without running the original completion callback
                    setIdle(false);
                    // clear pending and start a fresh resupply
                    Sahil.resupplyPending = false;
                    Sahil.forceStartResupply();
                    return;
                }
                state = State.WALK_TO_COLLECT;
            }

            case WALK_TO_COLLECT -> {
                collectChestPos = findLabeledChest("collect", 32.0);
                if (collectChestPos == null) {
                    System.out.println("No collect chest found — aborting cycle");
                                        setIdle(true);
                    return;
                }
                BlockPos interactPos = Sahil.findInteractablePositionNear(collectChestPos, 3);
                if (interactPos == null) {
                    System.out.println("Collect chest unreachable — aborting cycle");
                                        setIdle(true);
                    return;
                }
                System.out.println("WALK_TO_COLLECT: goal=" + interactPos + " collectChestPos=" + collectChestPos);
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
                        .setGoalAndPath(new GoalBlock(interactPos.getX(), interactPos.getY(), interactPos.getZ()));
                state = State.ARRIVE_COLLECT;
            }

            case ARRIVE_COLLECT -> {
                boolean still = BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing();
                System.out.println("ARRIVE_COLLECT: pathing=" + still);
                if (still) return;
                System.out.println("ARRIVE_COLLECT: pathing finished — opening chest " + collectChestPos);
                openChest(client, collectChestPos);
                screenWaitTicks = 0;
                state = State.WAIT_COLLECT_SCREEN;
            }

            case WAIT_COLLECT_SCREEN -> {
                if (client.currentScreen instanceof GenericContainerScreen screen) {
                        System.out.println("WAIT_COLLECT_SCREEN: opened chest screen");
                        queueWithdrawAllClicks(screen.getScreenHandler());
                        state = State.WITHDRAWING;
                        return;
                }
                screenWaitTicks++;
                if (screenWaitTicks > SCREEN_WAIT_TIMEOUT) {
                        System.out.println("Collect chest never opened — aborting cycle");
                        setIdle(true);
                }
            }

            case WITHDRAWING -> {
                if (!(client.currentScreen instanceof GenericContainerScreen screen)) {
                    System.out.println("WITHDRAWING: screen closed unexpectedly");
                    state = State.CLOSE_COLLECT;
                    return;
                }
                if (clickCooldown > 0) { clickCooldown--; return; }

                GenericContainerScreenHandler handler = screen.getScreenHandler();
                boolean hasMoreFlesh = hasMatchingItemInChest(handler, PICKUP_ITEM);
                int emptySlots = countEmptyMainSlots(client);

                System.out.println("WITHDRAWING: hasMoreFlesh=" + hasMoreFlesh + " emptySlots=" + emptySlots);
                if (!hasMoreFlesh || emptySlots <= 1) {
                    System.out.println("WITHDRAWING: stopping (no more flesh or only 1 slot left)");
                    state = State.CLOSE_COLLECT;
                    return;
                }

                collectAllMatching(client, handler, PICKUP_ITEM);
                clickCooldown = CLICK_INTERVAL;
            }

            case CLOSE_COLLECT -> {
                if (client.player != null) {
                    client.player.closeHandledScreen();
                }
                System.out.println("Collect done, starting trading");

                // If a resupply was requested while collecting, stop here and start it
                if (Sahil.resupplyPending) {
                    System.out.println("Resupply requested during collection — stopping before trading");
                    setIdle(false);
                    Sahil.resupplyPending = false;
                    Sahil.forceStartResupply();
                    return;
                }

                state = State.TRADING;
                Sahil.autoTrade = true;
                Sahil.onAllVillagersDone = () -> {
                    System.out.println("All villagers traded, checking if we need more flesh");
                    state = State.CHECK_COMPLETE;
                };
                Sahil.startNextVillagerSession();
            }

            case TRADING -> {
                // Sahil's own tick loop drives trading; onAllVillagersDone advances us.
                // If a resupply was requested while trading, abort trading and start resupply gracefully.
                if (Sahil.resupplyPending) {
                    System.out.println("Resupply requested during trading — aborting trading and starting resupply");
                    setIdle(false);
                    Sahil.resupplyPending = false;
                    Sahil.forceStartResupply();
                    return;
                }
            }

            case CHECK_COMPLETE -> {
                int fleshCount = countItem(client, PICKUP_ITEM);
                System.out.println("Current flesh: " + fleshCount + "/" + FLESH_THRESHOLD);

                if (fleshCount >= FLESH_THRESHOLD) {
                    System.out.println("Have enough flesh (" + fleshCount + "), cycle complete!");
                    setIdle(true);
                } else {
                    cycleCount++;
                    if (cycleCount >= MAX_CYCLES) {
                        System.out.println("Reached max cycles (" + MAX_CYCLES + "), stopping!");
                        setIdle(true);
                    } else {
                        System.out.println("Need more flesh. Cycle " + cycleCount + "/" + MAX_CYCLES + " complete, teleporting home");
                        state = State.TELEPORT_HOME_RETURN;
                    }
                }
            }

            case TELEPORT_HOME_RETURN -> {
                Sahil.autoTrade = false;
                System.out.println("Teleporting home to deposit after trading...");
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand("home 3");
                }
                homeWaitTicks = HOME_WAIT_TICKS;
                state = State.WAIT_HOME_RETURN;
            }

            case WAIT_HOME_RETURN -> {
                if (homeWaitTicks > 0) {
                    homeWaitTicks--;
                    return;
                }
                System.out.println("Back home, walking to deposit chest");
                state = State.WALK_TO_DEPOSIT;
            }
        }
    }

    private static void queueDepositClicks(GenericContainerScreenHandler handler) {
        pendingClicks.clear();
        int chestSlotCount = handler.getRows() * 9;
        for (int i = chestSlotCount; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && DEPOSIT_ITEMS.contains(stack.getItem())) {
                pendingClicks.add(i);
            }
        }
    }

    private static void queueWithdrawAllClicks(GenericContainerScreenHandler handler) {
        pendingClicks.clear();
        int chestSlotCount = handler.getRows() * 9;
        for (int i = 0; i < chestSlotCount; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.isOf(PICKUP_ITEM)) {
                pendingClicks.add(i);
            }
        }
    }

    private static int findFirstFleshSlotInPlayerInv(GenericContainerScreenHandler handler) {
        int chestSlotCount = handler.getRows() * 9;
        for (int i = chestSlotCount; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.isOf(PICKUP_ITEM)) {
                return i;
            }
        }
        return -1;
    }

    private static int countEmptyMainSlots(MinecraftClient client) {
        if (client.player == null) return 0;
        int empty = 0;
        for (int i = 0; i < 36; i++) { // 36 = 9 hotbar + 27 inventory
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isEmpty()) empty++;
        }
        return empty;
    }

    private static int countItem(MinecraftClient client, Item item) {
        if (client.player == null) return 0;
        int count = 0;
        for (ItemStack stack : client.player.getInventory().getMainStacks()) {
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    private static void openChest(MinecraftClient client, BlockPos chestPos) {
        if (client.player == null) return;
        client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, chestPos.toCenterPos());
        client.execute(() -> {
            if (client.player == null) return;
            var hit = client.player.raycast(5.0, 1.0f, false);
            if (hit instanceof BlockHitResult blockHit && blockHit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
                if (client.interactionManager != null) {
                    client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, blockHit);
                }
            } else {
                System.out.println("Raycast didn't hit a block when opening chest at " + chestPos);
            }
        });
    }

    public static BlockPos findLabeledChest(String keyword, double radius) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return null;

        Box searchBox = client.player.getBoundingBox().expand(radius);
        BlockPos min = new BlockPos((int) searchBox.minX, (int) searchBox.minY, (int) searchBox.minZ);
        BlockPos max = new BlockPos((int) searchBox.maxX, (int) searchBox.maxY, (int) searchBox.maxZ);

        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (client.world.getBlockEntity(pos) instanceof ChestBlockEntity) {
                for (Direction dir : Direction.values()) {
                    BlockPos signPos = pos.offset(dir);
                    if (client.world.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
                        String text = getSignText(sign);
                        if (text.toLowerCase().contains(keyword.toLowerCase())) {
                            return pos.toImmutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String getSignText(SignBlockEntity sign) {
        StringBuilder sb = new StringBuilder();
        var frontText = sign.getFrontText();
        for (Text line : frontText.getMessages(false)) {
            sb.append(line.getString()).append(" ");
        }
        return sb.toString();
    }

    private static void collectAllMatching(MinecraftClient client, GenericContainerScreenHandler handler, Item item) {
        if (client.player == null || client.interactionManager == null) return;

        int chestSlotCount = handler.getRows() * 9;
        int emptySlots = countEmptyMainSlots(client);

        // Leave at least 1 slot empty
        if (emptySlots <= 1) {
            System.out.println("Only 1 slot left, stopping pickup to leave room");
            return;
        }

        // Collect all matching items using shift-click
        for (int i = 0; i < chestSlotCount; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.isOf(item)) {
                // Check again before each pickup
                if (countEmptyMainSlots(client) <= 1) {
                    System.out.println("Reached 1 slot remaining, stopping pickup");
                    break;
                }

                // Shift-click to quick move
                client.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                clickCooldown = CLICK_INTERVAL;
            }
        }

        System.out.println("Pickup complete. Empty slots: " + countEmptyMainSlots(client));
    }

    private static int findFirstEmptyPlayerSlot(GenericContainerScreenHandler handler) {
        int chestSlotCount = handler.getRows() * 9;
        for (int i = chestSlotCount; i < handler.slots.size(); i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return i;
        }
        return -1;
    }

    private static boolean hasMatchingItemInChest(GenericContainerScreenHandler handler, Item item) {
        int chestSlotCount = handler.getRows() * 9;
        for (int i = 0; i < chestSlotCount; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.isOf(item)) return true;
        }
        return false;
    }
}
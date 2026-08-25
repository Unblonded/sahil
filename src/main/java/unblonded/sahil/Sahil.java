package unblonded.sahil;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.command.argument.EntityAnchorArgumentType;
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
import unblonded.sahil.cmds.CommandManager;
import unblonded.sahil.cmds.tst;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class Sahil implements ClientModInitializer {
    CopyOnWriteArrayList<Trade> trades = new CopyOnWriteArrayList<>();
    public static boolean autoTrade = false;
    private final Map<Integer, Integer> localUsesThisSession = new HashMap<>();
    public static MinecraftClient client;

    public static VillagerEntity currentTradingTarget = null;

    public static final Set<UUID> tradedVillagers = new HashSet<>();

    private static final List<TradeRule> RULES = List.of(
            new TradeRule(Items.ROTTEN_FLESH, 32, Items.EMERALD),
            new TradeRule(Items.EMERALD, 3, Items.EXPERIENCE_BOTTLE)
    );

    KeyBinding tradeKeyBinding;

    @Override
    public void onInitializeClient() {
        client = MinecraftClient.getInstance();

        CommandManager.register(new AutoTrade());
        CommandManager.register(new tst());
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
            PathFollower.tick(client);
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

    /**
     * Checks rules in priority order across the whole trade list first,
     * so an earlier rule is fully exhausted before a later rule is considered.
     */
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

    public static VillagerEntity findBestReachableVillager(double radius, int maxDistance) {
        if (client.player == null || client.world == null) return null;

        System.out.println("Currently traded UUIDs: " + tradedVillagers);

        Vec3d playerPos = client.player.getEntityPos();
        Box searchBox = client.player.getBoundingBox().expand(radius);

        List<VillagerEntity> nearby = client.world.getEntitiesByClass(
                VillagerEntity.class, searchBox,
                v -> !Sahil.tradedVillagers.contains(v.getUuid())
        );

        for (VillagerEntity v : nearby) {
            System.out.println("Candidate: " + v.getUuid() + " (excluded=" + Sahil.tradedVillagers.contains(v.getUuid()) + ")");
        }

        nearby.sort(Comparator.comparingDouble(v -> v.squaredDistanceTo(playerPos)));

        for (VillagerEntity v : nearby) {
            BlockPos interactPos = PathFinding.findInteractablePositionNear(v.getBlockPos(), maxDistance);
            if (interactPos == null) continue;

            List<BlockPos> path = PathFinding.findPath(interactPos);
            if (!path.isEmpty()) {
                System.out.println("Selected: " + v.getUuid());
                return v;
            }
        }

        return null;
    }

    public static void startNextVillagerSession() {
        VillagerEntity target = findBestReachableVillager(32.0, 3);
        if (target == null) {
            System.out.println("No more reachable untraded villagers — stopping");
            return;
        }

        BlockPos interactPos = PathFinding.findInteractablePositionNear(target.getBlockPos(), 3);
        List<BlockPos> path = PathFinding.findPath(interactPos);

        currentTradingTarget = target; // set up front so the tick loop can reference it if needed

        PathFollower.startPath(path, () -> {
            client.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());
            client.interactionManager.interactEntity(client.player, target, Hand.MAIN_HAND);
        });
    }
}
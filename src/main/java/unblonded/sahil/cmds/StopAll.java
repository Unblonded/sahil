package unblonded.sahil.cmds;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import unblonded.sahil.ChestCycle;
import unblonded.sahil.Sahil;

public class StopAll extends Command {
    public StopAll() {
        super("stopall");
    }

    @Override
    public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
        builder.executes(context -> {
            Sahil.autoTrade = false;
            Sahil.tradeOnly = false;
            ChestCycle.stop();
            // Clear Sahil's resupply flags so resupply can be started again later
            try { Sahil.resupplyPending = false; Sahil.isResupplying = false; } catch (Throwable ignored) {}
            Sahil.pendingInteractTarget = null;
            Sahil.settleTicksStable = 0;
            Sahil.currentTradingTarget = null;
            Sahil.tradedVillagers.clear();
            Sahil.onAllVillagersDone = null;
            Sahil.startedWithFlesh = null;

            if (Sahil.client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.MerchantScreen
                    || Sahil.client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.GenericContainerScreen) {
                Sahil.client.player.closeHandledScreen();
            }

            Sahil.client.player.sendMessage(Text.literal("Stopped everything, reset villager list"), false);
            return 1;
        });
    }
}
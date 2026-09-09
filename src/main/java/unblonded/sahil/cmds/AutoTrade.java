package unblonded.sahil.cmds;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import unblonded.sahil.Sahil;

public class AutoTrade extends Command {
    public AutoTrade() {
        super("autotrade", "Toggle Auto Trade");
    }

    @Override
    public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
        builder.executes(context -> {
            Sahil.tradeOnly = false;
            Sahil.autoTrade = !Sahil.autoTrade;
            MinecraftClient.getInstance().player.sendMessage(Text.literal("Auto Trade is " + (Sahil.autoTrade ? "Enabled" : "Disabled")), false);
            return 1;
        });
    }
}
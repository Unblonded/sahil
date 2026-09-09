package unblonded.sahil.cmds;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import unblonded.sahil.Sahil;

public class TradeOnly extends Command {
    public TradeOnly() {
        super("tradeonly", "Toggle Trade Only mode");
    }

    @Override
    public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
        builder.executes(context -> {
            Sahil.autoTrade = false;
            Sahil.tradeOnly = !Sahil.tradeOnly;
            MinecraftClient.getInstance().player.sendMessage(Text.literal("Trade Only is " + (Sahil.tradeOnly ? "Enabled" : "Disabled")), false);
            return 1;
        });
    }
}

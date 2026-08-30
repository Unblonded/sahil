package unblonded.sahil.cmds;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import unblonded.sahil.ChestCycle;

public class ChestCycleCmd extends Command {
    public ChestCycleCmd() { super("chestcycle"); }
    @Override
    public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
        builder.executes(context -> {
            ChestCycle.startCycle();
            return 1;
        });
    }
}
package unblonded.sahil.cmds;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Hand;
import unblonded.sahil.PathFinding;
import unblonded.sahil.PathFollower;
import unblonded.sahil.Sahil;

public class tst extends Command {
    public tst() {
        super("test");
    }

    @Override
    public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
        builder.executes(context -> {
            VillagerEntity target = Sahil.findBestReachableVillager(32.0, 4);
            if (target == null) {
                System.out.println("No untraded villagers nearby");
                return 1;
            }

            PathFollower.startPath(PathFinding.findPath(PathFinding.findInteractablePositionNear(target.getBlockPos(), 3)), () -> {
                Sahil.currentTradingTarget = target;
                Sahil.client.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());
                Sahil.client.interactionManager.interactEntity(Sahil.client.player, target, Hand.MAIN_HAND);
            });

            return 1;
        });
    }
}
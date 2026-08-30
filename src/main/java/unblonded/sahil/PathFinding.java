package unblonded.sahil;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

public class PathFinding {

    public static boolean isWalkable(ClientWorld world, BlockPos pos) {
        BlockPos below = pos.down();
        BlockState belowState = world.getBlockState(below);

        boolean solidGround = !belowState.isAir() && belowState.isSolidBlock(world, below);
        boolean feetClear = isPassable(world, pos);
        boolean headClear = isPassable(world, pos.up());

        return solidGround && feetClear && headClear;
    }

    public static boolean isPassable(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || !state.isSolidBlock(world, pos);
    }

    public static boolean isCleanStandingSpot(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir();
    }
}
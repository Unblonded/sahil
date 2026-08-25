package unblonded.sahil;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class PathFinding {

    private static final int MAX_FALL_DISTANCE = 4; // how many blocks it's allowed to drop in one move
    private static final int MAX_SEARCH_NODES = 5000; // safety cap so a bad target can't hang forever

    private record Node(BlockPos pos, Node parent, double gCost, double hCost) {
        double fCost() {
            return gCost + hCost;
        }
    }

    /**
     * Finds a path from start to goal using A*.
     * Returns an ordered list of BlockPos waypoints (excluding start, including goal),
     * or an empty list if no path was found.
     */
    public static List<BlockPos> findPath(BlockPos goal) {
        MinecraftClient client = Sahil.client;
        ClientWorld world = client.world;
        if (world == null) return List.of();
        BlockPos start = client.player.getBlockPos();

        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingDouble(Node::fCost));
        Map<BlockPos, Double> bestGCost = new HashMap<>();
        Set<BlockPos> closedSet = new HashSet<>();

        Node startNode = new Node(start, null, 0, heuristic(start, goal));
        openSet.add(startNode);
        bestGCost.put(start, 0.0);

        int iterations = 0;

        while (!openSet.isEmpty()) {
            if (++iterations > MAX_SEARCH_NODES) {
                System.out.println("Pathfinding gave up after " + MAX_SEARCH_NODES + " nodes — no path found");
                return List.of();
            }

            Node current = openSet.poll();

            if (current.pos.equals(goal)) {
                return reconstructPath(current);
            }

            if (closedSet.contains(current.pos)) continue;
            closedSet.add(current.pos);

            for (BlockPos neighborPos : getNeighbors(world, current.pos)) {
                if (closedSet.contains(neighborPos)) continue;

                double moveCost = current.pos.getSquaredDistance(neighborPos) > 1.5 ? 1.4 : 1.0; // diagonal vs straight
                double fallPenalty = Math.max(0, current.pos.getY() - neighborPos.getY()) * 0.1; // slight preference against big drops
                double tentativeG = current.gCost + moveCost + fallPenalty;

                if (tentativeG < bestGCost.getOrDefault(neighborPos, Double.MAX_VALUE)) {
                    bestGCost.put(neighborPos, tentativeG);
                    Node neighborNode = new Node(neighborPos, current, tentativeG, heuristic(neighborPos, goal));
                    openSet.add(neighborNode);
                }
            }
        }

        System.out.println("No path found to " + goal);
        return List.of();
    }

    private static List<BlockPos> reconstructPath(Node endNode) {
        LinkedList<BlockPos> path = new LinkedList<>();
        Node current = endNode;
        while (current.parent != null) {
            path.addFirst(current.pos);
            current = current.parent;
        }
        return path;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        // Euclidean distance — admissible and works well for open 3D movement
        return Math.sqrt(a.getSquaredDistance(b));
    }

    /**
     * Generates walkable neighbors: 8 horizontal directions at the same level,
     * plus drop-down variants of each direction for descending into holes/ledges.
     * No jump/step-up logic — only same-level and downward movement.
     */
    private static List<BlockPos> getNeighbors(ClientWorld world, BlockPos pos) {
        List<BlockPos> neighbors = new ArrayList<>();

        int[][] directions = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},   // cardinal
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}  // diagonal
        };

        for (int[] dir : directions) {
            BlockPos sameLevel = pos.add(dir[0], 0, dir[1]);

            if (isWalkable(world, sameLevel)) {
                neighbors.add(sameLevel);
            } else {
                // try dropping down at this direction if same-level isn't walkable
                BlockPos dropTarget = findDropTarget(world, sameLevel);
                if (dropTarget != null) {
                    neighbors.add(dropTarget);
                }
            }
        }

        return neighbors;
    }

    /**
     * Checks straight down from a column for the first walkable position,
     * up to MAX_FALL_DISTANCE blocks. Returns null if nothing walkable found in range.
     */
    private static BlockPos findDropTarget(ClientWorld world, BlockPos columnTop) {
        for (int dy = 1; dy <= MAX_FALL_DISTANCE; dy++) {
            BlockPos candidate = columnTop.down(dy);
            if (isWalkable(world, candidate)) {
                return candidate;
            }
            // if we hit a solid block before finding walkable space, no drop possible here
            if (!isPassable(world, candidate)) {
                return null;
            }
        }
        return null;
    }

    /**
     * A position is walkable if there's solid ground beneath it,
     * and both the feet and head space at that position are passable (non-solid).
     */
    private static boolean isWalkable(ClientWorld world, BlockPos pos) {
        BlockPos below = pos.down();
        BlockState belowState = world.getBlockState(below);

        boolean solidGround = !belowState.isAir() && belowState.isSolidBlock(world, below);
        boolean feetClear = isPassable(world, pos);
        boolean headClear = isPassable(world, pos.up());

        return solidGround && feetClear && headClear;
    }

    private static boolean isPassable(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || !state.isSolidBlock(world, pos);
    }

    /**
     * Finds a walkable position adjacent to the target entity's block,
     * checking all 8 horizontal neighbors (and their drop-down variants),
     * without assuming any particular facing direction.
     */
    public static BlockPos findInteractablePositionNear(BlockPos targetPos, int maxDistance) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || targetPos == null) return null;

        List<BlockPos> candidates = new ArrayList<>();

        // Check all positions from 1 to maxDistance blocks away
        for (int distance = 1; distance <= maxDistance; distance++) {
            // Check all 8 directions at this distance
            int[][] offsets = {
                    {distance, 0}, {-distance, 0}, {0, distance}, {0, -distance},
                    {distance, distance}, {distance, -distance}, {-distance, distance}, {-distance, -distance}
            };

            for (int[] offset : offsets) {
                BlockPos sameLevel = targetPos.add(offset[0], 0, offset[1]);
                if (isWalkable(client.world, sameLevel) && isCleanStandingSpot(client.world, sameLevel)) {
                    candidates.add(sameLevel);
                } else {
                    BlockPos dropTarget = findDropTarget(client.world, sameLevel);
                    if (dropTarget != null && isCleanStandingSpot(client.world, dropTarget)) {
                        candidates.add(dropTarget);
                    }
                }
            }
        }

        // Remove positions that are too far (more than maxDistance + 1)
        double maxDistSq = (maxDistance + 1) * (maxDistance + 1);
        candidates.removeIf(pos -> pos == null || pos.getSquaredDistance(targetPos) > maxDistSq);

        if (candidates.isEmpty()) return null;

        // Prefer positions closest to the player
        BlockPos playerPos = client.player.getBlockPos();
        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(playerPos)));

        return candidates.get(0);
    }

    // Helper method to check for brewing stand
    private static boolean isBrewingStand(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof net.minecraft.block.BrewingStandBlock;
    }

    private static boolean isCleanStandingSpot(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir(); // fully empty, not just "passable" — excludes brewing stands, fences, etc.
    }
}
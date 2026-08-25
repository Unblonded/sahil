package unblonded.sahil;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PathFollower {

    private static Runnable onArrive;
    private static List<BlockPos> currentPath = List.of();
    private static int currentIndex = 0;
    private static volatile boolean active = false;

    private static Input realInput;
    private static final DummyInput dummyInput = new DummyInput();

    private static final double REACH_DISTANCE = 0.4;
    private static final float TURN_SPEED = 2.0f; // degrees per yaw-thread cycle, tune down since it runs much faster than ticks
    private static final float YAW_DEADZONE = 1.0f; // degrees — stop correcting once this close, kills micro-jitter
    private static final float SPEED_SMOOTHING = 0.25f; // 0..1, higher = snappier, lower = smoother
    private static float smoothedSpeedFactor = 0.0f;

    private static ScheduledExecutorService yawThread;
    private static final AtomicBoolean yawThreadRunning = new AtomicBoolean(false);

    public static void startPath(List<BlockPos> path, Runnable onArriveCallback) {
        if (path.isEmpty()) {
            System.out.println("Path is empty, nothing to follow");
            active = false;
            return;
        }
        currentPath = path;
        currentIndex = 0;
        active = true;
        onArrive = onArriveCallback;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            realInput = client.player.input;
            client.player.input = dummyInput;
        }

        startYawThread();
        System.out.println("Following path with " + path.size() + " waypoints");
    }

    public static void stop() {
        if (onArrive != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(onArrive);
        }
        active = false;
        stopYawThread();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && realInput != null) {
            client.player.input = realInput;
        }
    }

    public static boolean isActive() {
        return active;
    }

    private static void startYawThread() {
        if (yawThreadRunning.compareAndSet(false, true)) {
            yawThread = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PathFollower-Yaw");
                t.setDaemon(true);
                return t;
            });
            // Runs much faster than the 20/sec tick rate — this is the "fast thread" for yaw only.
            yawThread.scheduleAtFixedRate(PathFollower::yawUpdate, 0, 10, TimeUnit.MILLISECONDS);
        }
    }

    private static void stopYawThread() {
        if (yawThreadRunning.compareAndSet(true, false) && yawThread != null) {
            yawThread.shutdownNow();
            yawThread = null;
        }
    }

    /** Runs on the separate thread — ONLY touches yaw, nothing else. */
    private static void yawUpdate() {
        if (!active) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        if (currentIndex >= currentPath.size()) return;

        BlockPos target = currentPath.get(currentIndex);
        Vec3d targetCenter = new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        Vec3d playerPos = player.getEntityPos();

        double dx = targetCenter.x - playerPos.x;
        double dz = targetCenter.z - playerPos.z;

        float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float currentYaw = player.getYaw();
        float yawDiff = wrapDegrees(targetYaw - currentYaw);
        if (Math.abs(yawDiff) < YAW_DEADZONE) return; // deadzone: don't correct tiny errors, avoids wobble

        float step = Math.min(TURN_SPEED, Math.abs(yawDiff)) * Math.signum(yawDiff);
        player.setYaw(currentYaw + step);
    }

    /** Runs on the normal client tick — handles movement/waypoint logic only. */
    public static void tick(MinecraftClient client) {
        if (!active) return;
        ClientPlayerEntity player = client.player;
        if (player == null) { active = false; return; }

        if (!(player.input instanceof DummyInput dummyInput)) {
            System.out.println("PathFollower: player.input isn't DummyInput — stopping.");
            active = false;
            return;
        }

        if (currentIndex >= currentPath.size()) {
            System.out.println("Reached end of path");
            stop();
            return;
        }

        BlockPos target = currentPath.get(currentIndex);
        Vec3d targetCenter = new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        Vec3d playerPos = player.getEntityPos();

        double dx = targetCenter.x - playerPos.x;
        double dz = targetCenter.z - playerPos.z;
        double horizontalDistSq = dx * dx + dz * dz;

        boolean atOrAboveTargetHeight = player.getBlockPos().getY() >= target.getY();
        if (horizontalDistSq < REACH_DISTANCE * REACH_DISTANCE && atOrAboveTargetHeight) {
            currentIndex++;
            return;
        }

        float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float currentYaw = player.getYaw();
        float yawDiff = wrapDegrees(targetYaw - currentYaw);
        float absYawDiff = Math.abs(yawDiff);

        float rawSpeedFactor = absYawDiff < 90.0f ? (1.0f - absYawDiff / 90.0f) : 0.0f;
        smoothedSpeedFactor += (rawSpeedFactor - smoothedSpeedFactor) * SPEED_SMOOTHING; // ramp instead of snap

        boolean needsJump = target.getY() > player.getBlockPos().getY();
        boolean shouldJump = needsJump && absYawDiff < 45.0f;

        dummyInput.setMovementVector(new Vec2f(0, smoothedSpeedFactor));
        player.input.playerInput = new PlayerInput(smoothedSpeedFactor > 0.05f, false, false, false, shouldJump, false, false);
    }

    private static float wrapDegrees(float degrees) {
        degrees = degrees % 360.0f;
        if (degrees >= 180.0f) degrees -= 360.0f;
        if (degrees < -180.0f) degrees += 360.0f;
        return degrees;
    }
}
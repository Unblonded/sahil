package unblonded.sahil;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RestockDetector {
    public static final Set<Integer> glowingVillagerIds = ConcurrentHashMap.newKeySet();

    public static VillagerEntity findNearestVillager(double x, double y, double z, double maxDistance) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;

        Box searchBox = new Box(
                x - maxDistance, y - maxDistance, z - maxDistance,
                x + maxDistance, y + maxDistance, z + maxDistance
        );

        List<VillagerEntity> nearby = client.world.getEntitiesByClass(
                VillagerEntity.class, searchBox, e -> true
        );

        VillagerEntity closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (VillagerEntity villager : nearby) {
            double distSq = villager.squaredDistanceTo(x, y, z);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = villager;
            }
        }

        return closest;
    }

    public static void applyGlow(VillagerEntity villager) {
        //glowingVillagerIds.add(villager.getId());
    }

    public static void removeGlow(VillagerEntity villager) {
        glowingVillagerIds.remove(villager.getId());
    }
}

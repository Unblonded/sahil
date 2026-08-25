package unblonded.sahil.mixin;

import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unblonded.sahil.RestockDetector;

@Mixin(SoundManager.class)
public class SoundManagerMixin {
    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;", at = @At("HEAD"))
    private void onPlaySound(SoundInstance sound, CallbackInfoReturnable<SoundSystem.PlayResult> cir) {
        Identifier soundId = sound.getId();
        if (soundId.getPath().equals("entity.villager.work_cleric")) {
            VillagerEntity nearest = RestockDetector.findNearestVillager(sound.getX(), sound.getY(), sound.getZ(), 0.2);
            if (nearest != null) {
                RestockDetector.applyGlow(nearest);
            }
        }
    }
}
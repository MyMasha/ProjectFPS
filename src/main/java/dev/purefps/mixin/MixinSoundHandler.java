package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastResourcePackReload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.resources.IResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Évite la destruction et recréation complète d'OpenAL lors d'un changement de Resource Pack
 * lorsque les packs actifs ne modifient aucun fichier audio (.ogg / sounds.json).
 */
@Mixin(SoundHandler.class)
public class MixinSoundHandler {

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"), cancellable = true)
    private void purefps$skipUnchangedAudioReload(IResourceManager resourceManager, CallbackInfo ci) {
        if (!PureFPSConfig.enabled) {
            FastResourcePackReload.resetState();
            return;
        }
        if (FastResourcePackReload.shouldSkipSoundReload(Minecraft.getMinecraft())) {
            ci.cancel();
        }
    }
}

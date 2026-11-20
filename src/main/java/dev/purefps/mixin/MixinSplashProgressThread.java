package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.BlackHoleEmergenceRenderer;
import dev.purefps.core.SeamlessLaunchTransition;
import net.minecraftforge.fml.common.ProgressManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;

/**
 * S'accroche directement au thread OpenGL dédié de Forge (`SplashProgress$3`) qui possède `Display.getDrawable()`
 * pendant que `Minecraft.startGame()` charge les textures/modèles sur `SharedDrawable` :
 * - À 100 FPS constants (zéro freeze CPU/IO !), remplace chaque frame blanche Mojang/Forge dans le backbuffer
 *   par l'émergence temps-réel de la caméra hors du Trou Noir juste avant `Display.update()`.
 */
@Mixin(targets = "net.minecraftforge.fml.client.SplashProgress$3", remap = false)
public class MixinSplashProgressThread {

    @Inject(
            method = "run()V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/Semaphore;acquireUninterruptibly()V",
                    remap = false
            ),
            remap = false
    )
    private void purefps$renderSmooth100FpsBlackHoleBeforeSwap(CallbackInfo ci) {
        if (!PureFPSConfig.enabled && !SeamlessLaunchTransition.isActive()) {
            return;
        }
        float ratio = 0.0F;
        try {
            Iterator<ProgressManager.ProgressBar> it = ProgressManager.barIterator();
            if (it.hasNext()) {
                ProgressManager.ProgressBar top = it.next();
                if (top != null && top.getSteps() > 0) {
                    ratio = (float) top.getStep() / (float) top.getSteps();
                }
            }
        } catch (Exception | LinkageError ignored) {
        }
        BlackHoleEmergenceRenderer.renderSplashThreadFrame(ratio);
    }

    @Inject(
            method = "clearGL()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/Drawable;releaseContext()V",
                    remap = false
            ),
            remap = false
    )
    private void purefps$cleanupSplashShaderBeforeContextRelease(CallbackInfo ci) {
        BlackHoleEmergenceRenderer.onSplashThreadFinishing();
    }
}

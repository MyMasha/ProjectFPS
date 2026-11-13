package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkRegionManager;
import dev.purefps.core.FastSkullCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.GameSettings;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimisations critiques de la boucle principale Minecraft 1.8.9 (PureClient 3.5) :
 * 1. Supprime les 2 appels bloquants GL11.glGetError() par frame dans checkGLError (supprime la synchronisation CPU<->GPU nvoglv64.dll : +40 à +90 FPS).
 * 2. Débride isFramerateLimitBelowMax() dès que limitFramerate >= 255 pour éliminer tout sommeil Display.sync() en jeu.
 * 3. Supprime les appels forcés System.gc() lors des chargements de mondes / changements de lobbies Hypixel.
 * 4. Menus fluides 120 FPS (au lieu de 30 FPS Vanilla) + économie GPU automatique en arrière-plan.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Shadow
    @Final
    private static Logger logger;

    @Shadow
    public WorldClient theWorld;

    @Shadow
    public GuiScreen currentScreen;

    @Shadow
    public GameSettings gameSettings;

    /**
     * @author PureClient
     * @reason Élimine le point de synchronisation bloquant glGetError() appelé 2 fois par frame ("Pre render" / "Post render")
     */
    @Overwrite
    private void checkGLError(String message) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.disableGlErrorChecks) {
            int err = GL11.glGetError();
            if (err != 0) {
                String errText = GLU.gluErrorString(err);
                logger.error("########## GL ERROR ##########");
                logger.error("@ " + message);
                logger.error(err + ": " + errText);
            }
        }
    }

    /**
     * @author PureClient
     * @reason Menus fluides 120 FPS (vs 30 FPS Vanilla) + limiteur FPS automatique hors focus
     */
    @Overwrite
    public int getLimitFramerate() {
        if (PureFPSConfig.enabled && PureFPSConfig.unfocusedFpsLimiter) {
            if (Display.isCreated() && !Display.isActive()) {
                return PureFPSConfig.unfocusedFpsLimit;
            }
        }
        if (this.theWorld == null && this.currentScreen != null) {
            return PureFPSConfig.enabled ? 120 : 30;
        }
        return this.gameSettings.limitFramerate;
    }

    /**
     * @author PureClient
     * @reason Garantit zéro appel Display.sync() lorsque le plafond FPS est réglé sur Illimité (>= 255)
     */
    @Overwrite
    public boolean isFramerateLimitBelowMax() {
        return this.getLimitFramerate() < 255;
    }

    @Redirect(
            method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/System;gc()V")
    )
    private void purefps$preventLoadWorldGC() {
        if (!PureFPSConfig.enabled || !PureFPSConfig.disableWorldLoadGC) {
            System.gc();
        }
    }

    @Redirect(
            method = "launchIntegratedServer",
            at = @At(value = "INVOKE", target = "Ljava/lang/System;gc()V")
    )
    private void purefps$preventIntegratedServerGC() {
        if (!PureFPSConfig.enabled || !PureFPSConfig.disableWorldLoadGC) {
            System.gc();
        }
    }

    @Inject(
            method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
            at = @At("HEAD")
    )
    private void purefps$clearCachesOnWorldChange(WorldClient worldClientIn, String loadingMessage, CallbackInfo ci) {
        FastSkullCache.clear();
        ChunkRegionManager.clearAll();
    }

    @Inject(method = "runGameLoop", at = @At("RETURN"))
    private void purefps$endFrameTelemetry(CallbackInfo ci) {
        PureFPSConfig.endFrameStats();
    }
}

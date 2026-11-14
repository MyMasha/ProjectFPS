package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkRegionManager;
import dev.purefps.core.FastSkullCache;
import dev.purefps.core.SeamlessLaunchTransition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.stream.IStream;
import org.apache.logging.log4j.Logger;
import org.lwjgl.LWJGLException;
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
 * 1. Supprime les 2 appels bloquants GL11.glGetError() par frame dans checkGLError.
 * 2. Supprime Thread.yield() à chaque frame dans runGameLoop (évite de céder le quantum CPU Windows pendant 1 à 6 ms !).
 * 3. Court-circuite renderStreamIndicator + les callbacks Twitch morts (élimine un glClear(GL_DEPTH_BUFFER_BIT) +
 *    new ScaledResolution + glOrtho complet sur le backbuffer à chaque frame !).
 * 4. Débride isFramerateLimitBelowMax() (>= 255) et synchronise VBO/VSync/FPS max dès la fin de startGame().
 * 5. Assure la transition sans couture "Event Horizon" depuis le Launcher (fenêtre sans bordure plein écran #000000).
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @Shadow
    @Final
    private static Logger logger;

    @Shadow
    public WorldClient theWorld;

    @Shadow
    public GuiScreen currentScreen;

    @Shadow
    public GameSettings gameSettings;

    @Shadow
    private IStream stream;

    @Shadow
    public abstract void toggleFullscreen();

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

    @Inject(method = "setInitialDisplayMode", at = @At("HEAD"))
    private void purefps$prepareSeamlessDisplay(CallbackInfo ci) {
        SeamlessLaunchTransition.checkAndPrepareBeforeDisplay((Minecraft) (Object) this);
    }

    @Redirect(
            method = "createDisplay",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setResizable(Z)V", remap = false)
    )
    private void purefps$configureSeamlessBorderless(boolean resizable) throws LWJGLException {
        if (SeamlessLaunchTransition.isActive()) {
            Display.setLocation(0, 0);
            Display.setResizable(false);
        } else {
            Display.setResizable(resizable);
        }
    }

    @Inject(method = "createDisplay", at = @At("RETURN"))
    private void purefps$clearSeamlessBlackOnCreate(CallbackInfo ci) {
        SeamlessLaunchTransition.clearDisplayToPureBlack();
    }

    @Inject(method = "drawSplashScreen", at = @At("HEAD"), cancellable = true)
    private void purefps$suppressWhiteMojangSplash(TextureManager textureManagerInstance, CallbackInfo ci) throws LWJGLException {
        if (SeamlessLaunchTransition.renderBlackSplashIfSeamless()) {
            ci.cancel();
        }
    }

    @Redirect(
            method = "startGame",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;toggleFullscreen()V")
    )
    private void purefps$skipExclusiveModeSwitchOnSeamlessStart(Minecraft mc) {
        if (!SeamlessLaunchTransition.isActive()) {
            this.toggleFullscreen();
        }
    }

    @Inject(method = "toggleFullscreen", at = @At("HEAD"))
    private void purefps$restoreDecorationsOnManualF11(CallbackInfo ci) {
        SeamlessLaunchTransition.onUserToggleFullscreen();
    }

    @Inject(method = "startGame", at = @At("RETURN"))
    private void purefps$syncStartupSettings(CallbackInfo ci) {
        PureFPSConfig.syncStartupGameSettingsIfNeeded();
    }

    @Inject(method = "shutdownMinecraftApplet", at = @At("HEAD"))
    private void purefps$flushDirtyConfigOnShutdown(CallbackInfo ci) {
        PureFPSConfig.saveIfDirty();
    }

    @Redirect(
            method = "runGameLoop",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V")
    )
    private void purefps$skipRenderThreadYield() {
        if (!PureFPSConfig.enabled) {
            Thread.yield();
        }
    }

    @Redirect(
            method = "runGameLoop",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;renderStreamIndicator(F)V"
            )
    )
    private void purefps$skipDeadTwitchIndicator(EntityRenderer entityRenderer, float partialTicks) {
        if (!PureFPSConfig.enabled || (this.stream != null && this.stream.isBroadcasting())) {
            entityRenderer.renderStreamIndicator(partialTicks);
        }
    }

    @Redirect(
            method = "runGameLoop",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/stream/IStream;func_152935_j()V")
    )
    private void purefps$skipTwitchUpdate(IStream twitchStream) {
        if (twitchStream != null && (!PureFPSConfig.enabled || twitchStream.isBroadcasting())) {
            twitchStream.func_152935_j();
        }
    }

    @Redirect(
            method = "runGameLoop",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/stream/IStream;func_152922_k()V")
    )
    private void purefps$skipTwitchSubmit(IStream twitchStream) {
        if (twitchStream != null && (!PureFPSConfig.enabled || twitchStream.isBroadcasting())) {
            twitchStream.func_152922_k();
        }
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
        PureFPSConfig.saveIfDirty();
    }

    @Inject(method = "runGameLoop", at = @At("RETURN"))
    private void purefps$endFrameTelemetry(CallbackInfo ci) {
        PureFPSConfig.endFrameStats();
    }
}

package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.BlackHoleEmergenceRenderer;
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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimisations critiques de la boucle principale Minecraft 1.8.9 (PureClient 3.6) :
 * 1. Supprime les 2 appels bloquants GL11.glGetError() par frame dans checkGLError.
 * 2. Supprime Thread.yield() à chaque frame dans runGameLoop.
 * 3. Court-circuite renderStreamIndicator + les callbacks Twitch morts.
 * 4. Débride isFramerateLimitBelowMax() (>= 255) et synchronise VBO/VSync/FPS max dès la fin de startGame().
 * 5. Remplace intégralement l'écran "Mojang" par la caméra du joueur qui sort du Trou Noir relativiste
 *    et place la fenêtre sans bordure plein écran exactement à la place du rideau du Launcher.
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
    private boolean fullscreen;

    @Shadow
    public abstract void toggleFullscreen();

    @Unique
    private int purefps$startupStepCounter = 0;

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

    @Inject(method = "startGame", at = @At("HEAD"))
    private void purefps$onStartGameHead(CallbackInfo ci) {
        this.purefps$startupStepCounter = 0;
        SeamlessLaunchTransition.onStartGameHead((Minecraft) (Object) this);
    }

    @Inject(method = "setInitialDisplayMode", at = @At("HEAD"), cancellable = true)
    private void purefps$prepareSeamlessDisplay(CallbackInfo ci) {
        if (SeamlessLaunchTransition.applyBorderlessDisplayMode((Minecraft) (Object) this)) {
            this.fullscreen = false;
            ci.cancel();
        }
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
    private void purefps$onDisplayCreated(CallbackInfo ci) {
        SeamlessLaunchTransition.onDisplayCreated((Minecraft) (Object) this);
    }

    @Inject(method = "drawSplashScreen", at = @At("HEAD"), cancellable = true)
    private void purefps$renderBlackHoleEmergenceInsteadOfMojang(TextureManager textureManagerInstance, CallbackInfo ci) {
        if (PureFPSConfig.enabled || SeamlessLaunchTransition.isActive()) {
            BlackHoleEmergenceRenderer.renderStartupCheckpoint((Minecraft) (Object) this, 0.18F, true);
            ci.cancel();
        }
    }

    @Inject(
            method = "startGame",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/IReloadableResourceManager;registerReloadListener(Lnet/minecraft/client/resources/IResourceManagerReloadListener;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void purefps$animateEmergenceOnReloadListeners(CallbackInfo ci) {
        if (PureFPSConfig.enabled || SeamlessLaunchTransition.isActive()) {
            this.purefps$startupStepCounter++;
            float progress = Math.min(0.52F, 0.08F + this.purefps$startupStepCounter * 0.036F);
            BlackHoleEmergenceRenderer.renderStartupCheckpoint((Minecraft) (Object) this, progress, false);
        }
    }

    @Inject(
            method = "startGame",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/fml/common/ProgressManager$ProgressBar;step(Ljava/lang/String;)V",
                    remap = false
            )
    )
    private void purefps$animateEmergenceOnProgressBarStep(CallbackInfo ci) {
        if (PureFPSConfig.enabled || SeamlessLaunchTransition.isActive()) {
            this.purefps$startupStepCounter++;
            float progress = Math.min(0.55F, 0.22F + this.purefps$startupStepCounter * 0.025F);
            BlackHoleEmergenceRenderer.renderStartupCheckpoint((Minecraft) (Object) this, progress, true);
        }
    }

    @Inject(
            method = "startGame",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/fml/client/FMLClientHandler;finishMinecraftLoading()V",
                    remap = false
            )
    )
    private void purefps$animateEmergenceBeforeFinishLoading(CallbackInfo ci) {
        if (PureFPSConfig.enabled || SeamlessLaunchTransition.isActive()) {
            BlackHoleEmergenceRenderer.renderStartupCheckpoint((Minecraft) (Object) this, 0.56F, true);
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
        BlackHoleEmergenceRenderer.releaseMenuResources();
        FastSkullCache.clear();
        ChunkRegionManager.clearAll();
        PureFPSConfig.saveIfDirty();
    }

    @Inject(method = "runGameLoop", at = @At("RETURN"))
    private void purefps$endFrameTelemetry(CallbackInfo ci) {
        PureFPSConfig.endFrameStats();
    }
}

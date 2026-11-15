package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.BlackHoleEmergenceRenderer;
import dev.purefps.core.BorderlessWindowHelper;
import dev.purefps.core.GpuFramePacer;
import dev.purefps.core.MenuCursorRenderer;
import dev.purefps.core.MenuUnloadManager;
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
 * Optimisations critiques de la boucle principale Minecraft 1.8.9 (MashClient 4.4.6) :
 * 1. Supprime les 2 appels bloquants GL11.glGetError() par frame dans checkGLError.
 * 2. Régule la file GPU sans spin (1-frame-in-flight GLSync) et maintient la composition DWM fenêtrée (+1 px)
 *    pour éliminer tout freeze des streams Discord, captures Win+Shift+S et pointeurs Windows personnalisés.
 * 3. Préserve le curseur matériel natif de Windows par défaut (ou un pointeur personnalisé optionnel).
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
    private boolean purefps$wasDisplayActive = false;

    /**
     * @author MashClient
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
     * @author MashClient
     * @reason Émergence 240 FPS -> menu 120 FPS + limiteur hors focus (60 FPS visible sur 2e écran / 30 FPS réduit)
     *         pour ne jamais saturer le GPU pendant l'utilisation de Discord ou d'autres applications
     */
    @Overwrite
    public int getLimitFramerate() {
        if (PureFPSConfig.enabled && PureFPSConfig.unfocusedFpsLimiter) {
            if (Display.isCreated() && !Display.isActive()) {
                if (!Display.isVisible()) {
                    return PureFPSConfig.unfocusedFpsLimit;
                }
                if (this.theWorld == null || this.currentScreen != null) {
                    return Math.max(30, PureFPSConfig.unfocusedFpsLimit);
                }
                return Math.max(60, PureFPSConfig.unfocusedFpsLimit);
            }
        }
        if (this.theWorld == null && this.currentScreen != null) {
            if (!PureFPSConfig.enabled) {
                return 30;
            }
            return BlackHoleEmergenceRenderer.isBootEmergenceComplete() ? 120 : 240;
        }
        return this.gameSettings.limitFramerate;
    }

    /**
     * @author MashClient
     * @reason Garantit zéro appel Display.sync() lorsque le plafond FPS est réglé sur Illimité (>= 255)
     */
    @Overwrite
    public boolean isFramerateLimitBelowMax() {
        return this.getLimitFramerate() < 255;
    }

    @Inject(method = "startGame", at = @At("HEAD"))
    private void purefps$onStartGameHead(CallbackInfo ci) {
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
        if (SeamlessLaunchTransition.isActive() || PureFPSConfig.borderlessFullscreen) {
            BorderlessWindowHelper.applyPreCreateBorderlessLocation();
        } else {
            Display.setResizable(resizable);
        }
    }

    @Inject(method = "createDisplay", at = @At("RETURN"))
    private void purefps$onDisplayCreated(CallbackInfo ci) {
        SeamlessLaunchTransition.onDisplayCreated((Minecraft) (Object) this);
    }

    @Inject(method = "drawSplashScreen", at = @At("HEAD"), cancellable = true)
    private void purefps$renderBlackHoleEmergenceFallback(TextureManager textureManagerInstance, CallbackInfo ci) {
        if (PureFPSConfig.enabled || SeamlessLaunchTransition.isActive()) {
            BlackHoleEmergenceRenderer.renderPureBlackBootFrame((Minecraft) (Object) this);
            ci.cancel();
        }
    }

    @Redirect(
            method = "startGame",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;toggleFullscreen()V")
    )
    private void purefps$skipExclusiveModeSwitchOnSeamlessStart(Minecraft mc) {
        if (!SeamlessLaunchTransition.isActive() && !PureFPSConfig.borderlessFullscreen) {
            this.toggleFullscreen();
        }
    }

    @Inject(method = "toggleFullscreen", at = @At("HEAD"), cancellable = true)
    private void purefps$toggleBorderlessFullscreenOnF11(CallbackInfo ci) {
        SeamlessLaunchTransition.onUserToggleFullscreen();
        if (PureFPSConfig.enabled && PureFPSConfig.borderlessFullscreen) {
            if (BorderlessWindowHelper.toggleBorderlessFullscreen((Minecraft) (Object) this)) {
                this.fullscreen = false;
                if (this.gameSettings != null) {
                    this.gameSettings.fullScreen = false;
                }
                ci.cancel();
            }
        }
    }

    @Inject(method = "startGame", at = @At("RETURN"))
    private void purefps$syncStartupSettings(CallbackInfo ci) {
        if (SeamlessLaunchTransition.isActive()) {
            BlackHoleEmergenceRenderer.renderPureBlackBootFrame((Minecraft) (Object) this);
        }
        PureFPSConfig.syncStartupGameSettingsIfNeeded();
        if (Display.isCreated()) {
            Display.setTitle("MashClient 4.4.6 | Minecraft 1.8.9");
        }
        BorderlessWindowHelper.ensureMenuCursorVisible();
    }

    @Inject(method = "updateDisplay", at = @At("HEAD"))
    private void purefps$renderHardwareIndependentMenuCursor(CallbackInfo ci) {
        MenuCursorRenderer.onBeforeSwapBuffers((Minecraft) (Object) this);
    }

    @Inject(method = "displayGuiScreen", at = @At("RETURN"))
    private void purefps$ensureCursorOnGuiOpen(GuiScreen guiScreenIn, CallbackInfo ci) {
        if (this.currentScreen != null) {
            BorderlessWindowHelper.ensureMenuCursorVisible();
        }
    }

    @Inject(method = "setIngameNotInFocus", at = @At("RETURN"))
    private void purefps$ensureCursorOnUngrab(CallbackInfo ci) {
        BorderlessWindowHelper.ensureMenuCursorVisible();
    }

    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void purefps$restoreCursorOnFocusRegain(CallbackInfo ci) {
        boolean activeNow = Display.isCreated() && Display.isActive();
        if (activeNow && !this.purefps$wasDisplayActive && this.currentScreen != null) {
            BorderlessWindowHelper.ensureMenuCursorVisible();
        }
        this.purefps$wasDisplayActive = activeNow;
    }

    @Inject(method = "shutdownMinecraftApplet", at = @At("HEAD"))
    private void purefps$flushDirtyConfigOnShutdown(CallbackInfo ci) {
        GpuFramePacer.release();
        dev.purefps.module.MashModules.saveNow();
        PureFPSConfig.save();
    }

    @Redirect(
            method = "runGameLoop",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V")
    )
    private void purefps$skipRenderThreadYield() {
        // Lorsque les FPS sont débridés (>= 255, sans Display.sync), conserve Thread.yield()
        // pour éviter d'affamer les threads audio/WebRTC de Discord et de Windows.
        if (!PureFPSConfig.enabled || !this.isFramerateLimitBelowMax()) {
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
        if (worldClientIn != null) {
            dev.purefps.client.audio.MashAudioEngine.updateMenuAmbientLoop(false);
        }
        MenuUnloadManager.onWorldChangeOrUnload();
        dev.purefps.module.MashModules.saveAsyncIfDirty();
        PureFPSConfig.saveIfDirty();
    }

    @Inject(method = "runGameLoop", at = @At("RETURN"))
    private void purefps$endFrameTelemetry(CallbackInfo ci) {
        if (PureFPSConfig.enabled) {
            GpuFramePacer.onFramePresented();
        }
        PureFPSConfig.endFrameStats();
    }
}

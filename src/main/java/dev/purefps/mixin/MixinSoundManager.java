package dev.purefps.mixin;

import dev.purefps.client.audio.LowLatencyAudioConfigurator;
import dev.purefps.client.audio.MashAudioEngine;
import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.audio.SoundEventAccessorComposite;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.SoundManager;
import net.minecraft.client.audio.SoundPoolEntry;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import paulscode.sound.SoundSystem;
import paulscode.sound.SoundSystemConfig;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Pipeline audio temps réel pour Minecraft 1.8.9 (MashClient 4.4.5) :
 * 1. Configure OpenAL Soft (WASAPI mmdevapi, tampons courts ~20 ms au lieu de ~93-180 ms) AVANT AL.create().
 * 2. Monte le plafond Paulscode de 28 à 64 canaux normaux (+ 8 streaming) pour éliminer les coupures/retards en PvP.
 * 3. Élève la priorité du CommandThread Paulscode à MAX_PRIORITY - 1 et pré-décode progressivement les sons
 *    clés de combat/pas/blocs/explosions dans ALBufferMap afin d'éliminer les gels CodecJOrbis au premier impact.
 * 4. Filtre les mises à jour redondantes de listener et plafonne les rafales d'un même son par tick (wld_sound_limiter).
 */
@Mixin(SoundManager.class)
public abstract class MixinSoundManager {

    @Unique
    private static final String[] PUREFPS_CORE_PREWARM_EVENTS = new String[]{
            "random.successful_hit",
            "random.orb",
            "game.player.hurt",
            "game.neutral.hurt",
            "mob.irongolem.hit",
            "random.bow",
            "random.bowhit",
            "random.explode",
            "fire.ignite",
            "random.pop",
            "random.click",
            "note.pling",
            "step.stone",
            "step.wood",
            "step.cloth",
            "step.grass",
            "dig.stone",
            "dig.wood",
            "dig.cloth"
    };

    @Unique
    private static Field purefps$sndSystemField;
    @Unique
    private static boolean purefps$sndSystemFieldResolved = false;

    @Shadow
    @Final
    private SoundHandler sndHandler;

    @Shadow
    private boolean loaded;

    @Shadow
    private int playTime;

    @Shadow
    private static URL getURLForSoundResource(ResourceLocation location) {
        throw new AssertionError();
    }

    @Unique
    private boolean purefps$engineTuned = false;

    @Unique
    private final Deque<ResourceLocation> purefps$prewarmQueue = new ArrayDeque<>();

    @Unique
    private boolean purefps$hasLastListener = false;
    @Unique
    private float purefps$lastListX;
    @Unique
    private float purefps$lastListY;
    @Unique
    private float purefps$lastListZ;
    @Unique
    private float purefps$lastListYaw;
    @Unique
    private float purefps$lastListPitch;

    @Inject(method = "<init>", at = @At("HEAD"))
    private void purefps$configureOpenALBeforeInit(SoundHandler handler, GameSettings settings, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        LowLatencyAudioConfigurator.applyBeforeOpenALInit(mc != null ? mc.mcDataDir : null);
        purefps$applyChannelLimits();
    }

    @Inject(method = "loadSoundSystem", at = @At("HEAD"))
    private void purefps$onLoadSoundSystemHead(CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        LowLatencyAudioConfigurator.applyBeforeOpenALInit(mc != null ? mc.mcDataDir : null);
        purefps$applyChannelLimits();
        this.purefps$engineTuned = false;
        this.purefps$hasLastListener = false;
        this.purefps$prewarmQueue.clear();
    }

    @Inject(method = "unloadSoundSystem", at = @At("HEAD"))
    private void purefps$onUnloadSoundSystemHead(CallbackInfo ci) {
        MashAudioEngine.onOpenALUnloading();
        LowLatencyAudioConfigurator.resetLimiterState();
        this.purefps$engineTuned = false;
        this.purefps$hasLastListener = false;
        this.purefps$prewarmQueue.clear();
    }

    @Inject(method = "updateAllSounds", at = @At("HEAD"))
    private void purefps$onUpdateAllSoundsHead(CallbackInfo ci) {
        LowLatencyAudioConfigurator.onSoundTick(this.playTime + 1);
        if (!this.loaded) {
            return;
        }
        if (!this.purefps$engineTuned) {
            this.purefps$engineTuned = true;
            SoundSystem sys = purefps$resolveSoundSystem();
            purefps$boostPaulscodeThreadPriority(sys);
            MashAudioEngine.migrateToOpenALIfReady();
            if (PureFPSConfig.enabled && sys != null && this.sndHandler != null) {
                purefps$collectPrewarmLocations();
            }
        }
        if (!this.purefps$prewarmQueue.isEmpty() && PureFPSConfig.enabled) {
            SoundSystem sys = purefps$resolveSoundSystem();
            if (sys == null) {
                this.purefps$prewarmQueue.clear();
                return;
            }
            int budget = 3;
            while (budget-- > 0 && !this.purefps$prewarmQueue.isEmpty()) {
                ResourceLocation rl = this.purefps$prewarmQueue.pollFirst();
                if (rl != null) {
                    try {
                        URL url = getURLForSoundResource(rl);
                        if (url != null) {
                            sys.loadSound(url, rl.toString());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    @Inject(method = "playSound", at = @At("HEAD"), cancellable = true)
    private void purefps$rateLimitDuplicateBurstSounds(ISound sound, CallbackInfo ci) {
        if (!this.loaded || sound == null || !PureFPSConfig.enabled) {
            return;
        }
        ResourceLocation loc = sound.getSoundLocation();
        if (loc == null) {
            return;
        }
        boolean repeating = sound.canRepeat() && sound.getRepeatDelay() == 0;
        if (!LowLatencyAudioConfigurator.shouldAllowGameSound(this.playTime, loc.toString(), repeating)) {
            ci.cancel();
        }
    }

    @Inject(method = "getNormalizedVolume", at = @At("RETURN"), cancellable = true)
    private void purefps$applyModuleVolumeAdjustments(
            ISound sound,
            SoundPoolEntry entry,
            SoundCategory category,
            CallbackInfoReturnable<Float> cir
    ) {
        if (!PureFPSConfig.enabled || sound == null || sound.getSoundLocation() == null) {
            return;
        }
        float base = cir.getReturnValueF();
        if (base <= 0.0F) {
            return;
        }
        float mult = LowLatencyAudioConfigurator.computeModuleVolumeScale(sound.getSoundLocation().getResourcePath());
        if (mult != 1.0F) {
            cir.setReturnValue(MathHelper.clamp_float(base * mult, 0.0F, 1.0F));
        }
    }

    @Inject(method = "getNormalizedPitch", at = @At("RETURN"), cancellable = true)
    private void purefps$applyModulePitchAdjustments(
            ISound sound,
            SoundPoolEntry entry,
            CallbackInfoReturnable<Float> cir
    ) {
        if (!PureFPSConfig.enabled || sound == null || sound.getSoundLocation() == null) {
            return;
        }
        float mult = LowLatencyAudioConfigurator.computeModulePitchScale(sound.getSoundLocation().getResourcePath());
        if (mult != 1.0F) {
            cir.setReturnValue(MathHelper.clamp_float(cir.getReturnValueF() * mult, 0.5F, 2.0F));
        }
    }

    @Inject(method = "setListener", at = @At("HEAD"), cancellable = true)
    private void purefps$skipRedundantListenerQueueSpam(EntityPlayer player, float partialTicks, CallbackInfo ci) {
        if (!this.loaded || player == null || !PureFPSConfig.enabled) {
            return;
        }
        float pitch = player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * partialTicks;
        float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
        float x = (float) (player.prevPosX + (player.posX - player.prevPosX) * (double) partialTicks);
        float y = (float) (player.prevPosY + (player.posY - player.prevPosY) * (double) partialTicks + (double) player.getEyeHeight());
        float z = (float) (player.prevPosZ + (player.posZ - player.prevPosZ) * (double) partialTicks);

        if (this.purefps$hasLastListener
                && Math.abs(x - this.purefps$lastListX) < 1.0E-4F
                && Math.abs(y - this.purefps$lastListY) < 1.0E-4F
                && Math.abs(z - this.purefps$lastListZ) < 1.0E-4F
                && Math.abs(yaw - this.purefps$lastListYaw) < 1.0E-3F
                && Math.abs(pitch - this.purefps$lastListPitch) < 1.0E-3F) {
            ci.cancel();
            return;
        }

        this.purefps$hasLastListener = true;
        this.purefps$lastListX = x;
        this.purefps$lastListY = y;
        this.purefps$lastListZ = z;
        this.purefps$lastListYaw = yaw;
        this.purefps$lastListPitch = pitch;
    }

    @Unique
    private static void purefps$applyChannelLimits() {
        try {
            if (SoundSystemConfig.getNumberNormalChannels() < LowLatencyAudioConfigurator.TARGET_NORMAL_CHANNELS) {
                SoundSystemConfig.setNumberNormalChannels(LowLatencyAudioConfigurator.TARGET_NORMAL_CHANNELS);
            }
            if (SoundSystemConfig.getNumberStreamingChannels() < LowLatencyAudioConfigurator.TARGET_STREAMING_CHANNELS) {
                SoundSystemConfig.setNumberStreamingChannels(LowLatencyAudioConfigurator.TARGET_STREAMING_CHANNELS);
            }
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private SoundSystem purefps$resolveSoundSystem() {
        if (!purefps$sndSystemFieldResolved) {
            purefps$sndSystemFieldResolved = true;
            for (Field f : SoundManager.class.getDeclaredFields()) {
                if (SoundSystem.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    purefps$sndSystemField = f;
                    break;
                }
            }
        }
        if (purefps$sndSystemField == null) {
            return null;
        }
        try {
            return (SoundSystem) purefps$sndSystemField.get(this);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private static void purefps$boostPaulscodeThreadPriority(SoundSystem sys) {
        if (sys == null) {
            return;
        }
        try {
            Field cmdField = SoundSystem.class.getDeclaredField("commandThread");
            cmdField.setAccessible(true);
            Object cmdObj = cmdField.get(sys);
            if (cmdObj instanceof Thread) {
                ((Thread) cmdObj).setPriority(Math.min(Thread.MAX_PRIORITY, Thread.MAX_PRIORITY - 1));
            }
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private void purefps$collectPrewarmLocations() {
        Set<ResourceLocation> seen = new HashSet<>(64);
        for (String eventName : PUREFPS_CORE_PREWARM_EVENTS) {
            try {
                SoundEventAccessorComposite comp = this.sndHandler.getSound(new ResourceLocation(eventName));
                if (comp == null) {
                    continue;
                }
                for (int sample = 0; sample < 4; sample++) {
                    SoundPoolEntry entry = comp.cloneEntry();
                    if (entry != null && entry != SoundHandler.missing_sound && !entry.isStreamingSound()) {
                        ResourceLocation rl = entry.getSoundPoolEntryLocation();
                        if (rl != null && seen.add(rl)) {
                            this.purefps$prewarmQueue.addLast(rl);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }
}

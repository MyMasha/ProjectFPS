package dev.purefps.mixin;

import dev.purefps.client.ZoomController;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.module.MashFlags;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemBow;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Modules Caméra, Champ de Vision PvP, Freelook 360°, Météo & Lumière MashClient 4.0 :
 * - Champ de Vision PvP (FOV de base custom + multiplicateurs Speed I/II, Sprint, Arc, Vol, Anti-Lenteur)
 * - Zoom Optique Pro fluide
 * - Freelook 360° purement local (0 mutation de rotationYaw/Pitch côté physique ou réseau !)
 * - HurtCam personnalisable (0 % .. 100 %)
 * - Fullbright à intensité réglable & Weather Changer client
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {

    @Shadow
    private Minecraft mc;

    @Shadow
    private boolean lightmapUpdateNeeded;

    @Unique
    private float purefps$savedGamma = 1.0F;
    @Unique
    private boolean purefps$overrodeGamma = false;
    @Unique
    private boolean purefps$lastFullbrightState = false;
    @Unique
    private float purefps$lastFullbrightStrength = 100.0F;
    @Unique
    private float purefps$smoothedPvpMult = 1.0F;
    @Unique
    private long purefps$lastFovNano = 0L;

    @Unique
    private boolean purefps$swappedFreelookAngles = false;
    @Unique
    private float purefps$realYaw;
    @Unique
    private float purefps$realPrevYaw;
    @Unique
    private float purefps$realPitch;
    @Unique
    private float purefps$realPrevPitch;

    @Unique
    private float purefps$cachedFrameFov = Float.NaN;
    @Unique
    private boolean purefps$cachedFrameFovValid = false;

    @Unique
    private boolean purefps$fullbrightLightmapBaked = false;
    @Unique
    private net.minecraft.world.World purefps$lastLightmapWorld = null;
    @Unique
    private float purefps$lastLightmapStrength = -1.0F;
    @Unique
    private boolean purefps$lastLightmapNightVision = false;
    @Unique
    private float purefps$lastLightmapBossColor = 0.0F;

    @Inject(method = "getFOVModifier", at = @At("RETURN"), cancellable = true)
    private void purefps$applyPvpFovAndSmoothZoom(float partialTicks, boolean useFOVSetting, CallbackInfoReturnable<Float> cir) {
        if (!useFOVSetting || !PureFPSConfig.enabled) {
            return;
        }
        if (this.purefps$cachedFrameFovValid) {
            if (this.purefps$cachedFrameFov != cir.getReturnValueF()) {
                cir.setReturnValue(this.purefps$cachedFrameFov);
            }
            return;
        }
        float fov = cir.getReturnValueF();

        if (MashFlags.pvpFovEnabled && this.mc != null && this.mc.gameSettings != null) {
            float base = MashFlags.pvpFovOverrideBase ? MashFlags.pvpFovBase : this.mc.gameSettings.fovSetting;
            float targetMult = 1.0F;

            if (MashFlags.pvpFovDynamic && this.mc.thePlayer != null) {
                EntityPlayerSP p = this.mc.thePlayer;
                if (p.capabilities.isFlying) {
                    targetMult *= MashFlags.pvpFovFlyMult;
                }
                if (p.isSprinting()) {
                    targetMult += MashFlags.pvpFovSprintBoost;
                }
                PotionEffect speed = p.getActivePotionEffect(Potion.moveSpeed);
                if (speed != null) {
                    targetMult *= (speed.getAmplifier() >= 1) ? MashFlags.pvpFovSpeed2Mult : MashFlags.pvpFovSpeed1Mult;
                }
                if (!MashFlags.pvpFovIgnoreSlowness && p.isPotionActive(Potion.moveSlowdown)) {
                    targetMult *= 0.88F;
                }
                if (p.isUsingItem() && p.getItemInUse() != null && p.getItemInUse().getItem() instanceof ItemBow) {
                    float pull = Math.min(1.0F, p.getItemInUseDuration() / 20.0F);
                    pull = pull * pull;
                    targetMult *= (1.0F - pull * (1.0F - MashFlags.pvpFovBowZoom));
                }
            }

            long now = System.nanoTime();
            float dt = this.purefps$lastFovNano == 0L ? 0.016F : (now - this.purefps$lastFovNano) * 1.0e-9F;
            this.purefps$lastFovNano = now;
            if (dt < 0.0F || dt > 0.1F) dt = 0.016F;
            this.purefps$smoothedPvpMult += (targetMult - this.purefps$smoothedPvpMult) * (1.0F - (float) Math.exp(-16.0F * dt));

            fov = base * (MashFlags.pvpFovDynamic ? this.purefps$smoothedPvpMult : 1.0F);

            Entity view = this.mc.getRenderViewEntity();
            if (view != null && this.mc.theWorld != null) {
                Block block = ActiveRenderInfo.getBlockAtEntityViewpoint(this.mc.theWorld, view, partialTicks);
                if (block.getMaterial() == Material.water) {
                    fov = fov * 60.0F / 70.0F;
                }
            }
        }

        float finalFov = ZoomController.applyFov(this.mc, fov);
        this.purefps$cachedFrameFov = finalFov;
        this.purefps$cachedFrameFovValid = true;
        if (finalFov != cir.getReturnValueF()) {
            cir.setReturnValue(finalFov);
        }
    }

    @Inject(method = "hurtCameraEffect", at = @At("HEAD"), cancellable = true)
    private void purefps$disableHurtCamIfConfigured(float partialTicks, CallbackInfo ci) {
        if (!PureFPSConfig.enabled) {
            return;
        }
        if (PureFPSConfig.noHurtCam || (MashFlags.hurtCamCustom && MashFlags.hurtCamIntensity <= 0.01F)) {
            ci.cancel();
        }
    }

    @Redirect(
            method = "hurtCameraEffect",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;rotate(FFFF)V")
    )
    private void purefps$scaleHurtCamRoll(float angle, float x, float y, float z) {
        if (PureFPSConfig.enabled && MashFlags.hurtCamCustom && z != 0.0F) {
            GlStateManager.rotate(angle * MashFlags.hurtCamIntensity, x, y, z);
        } else {
            GlStateManager.rotate(angle, x, y, z);
        }
    }

    @Redirect(
            method = "updateCameraAndRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/entity/EntityPlayerSP;setAngles(FF)V")
    )
    private void purefps$redirectMouseToFreelookIfActive(EntityPlayerSP player, float yawDelta, float pitchDelta) {
        if (PureFPSConfig.enabled && MashFlags.freelookActive) {
            MashFlags.freelookYaw += yawDelta * 0.15F;
            float sign = MashFlags.freelookInvertY ? 1.0F : -1.0F;
            MashFlags.freelookPitch = MathHelper.clamp_float(MashFlags.freelookPitch + pitchDelta * 0.15F * sign, -90.0F, 90.0F);
        } else {
            player.setAngles(yawDelta, pitchDelta);
        }
    }

    @Inject(method = "orientCamera", at = @At("HEAD"))
    private void purefps$beginFreelookOrient(float partialTicks, CallbackInfo ci) {
        if (PureFPSConfig.enabled && MashFlags.freelookActive && this.mc != null && this.mc.thePlayer != null
                && this.mc.getRenderViewEntity() == this.mc.thePlayer) {
            EntityPlayerSP p = this.mc.thePlayer;
            this.purefps$realYaw = p.rotationYaw;
            this.purefps$realPrevYaw = p.prevRotationYaw;
            this.purefps$realPitch = p.rotationPitch;
            this.purefps$realPrevPitch = p.prevRotationPitch;
            p.rotationYaw = MashFlags.freelookYaw;
            p.prevRotationYaw = MashFlags.freelookYaw;
            p.rotationPitch = MashFlags.freelookPitch;
            p.prevRotationPitch = MashFlags.freelookPitch;
            this.purefps$swappedFreelookAngles = true;
        }
    }

    @Inject(method = "orientCamera", at = @At("RETURN"))
    private void purefps$endFreelookOrient(float partialTicks, CallbackInfo ci) {
        if (this.purefps$swappedFreelookAngles && this.mc != null && this.mc.thePlayer != null) {
            EntityPlayerSP p = this.mc.thePlayer;
            p.rotationYaw = this.purefps$realYaw;
            p.prevRotationYaw = this.purefps$realPrevYaw;
            p.rotationPitch = this.purefps$realPitch;
            p.prevRotationPitch = this.purefps$realPrevPitch;
            this.purefps$swappedFreelookAngles = false;
        }
    }

    @Inject(method = "renderRainSnow", at = @At("HEAD"), cancellable = true)
    private void purefps$skipClearWeatherRainRender(float partialTicks, CallbackInfo ci) {
        if (!PureFPSConfig.enabled) {
            return;
        }
        if (MashFlags.weatherChangerEnabled && MashFlags.weatherMode == 0) {
            ci.cancel();
            return;
        }
        if (!MashFlags.weatherChangerEnabled && (this.mc == null || this.mc.theWorld == null || this.mc.theWorld.getRainStrength(partialTicks) <= 0.0F)) {
            ci.cancel();
        }
    }

    @Inject(method = "addRainParticles", at = @At("HEAD"), cancellable = true)
    private void purefps$skipClearWeatherRainTicks(CallbackInfo ci) {
        if (!PureFPSConfig.enabled) {
            return;
        }
        if (MashFlags.weatherChangerEnabled && MashFlags.weatherMode == 0) {
            ci.cancel();
            return;
        }
        if (!MashFlags.weatherChangerEnabled && (this.mc == null || this.mc.theWorld == null || this.mc.theWorld.getRainStrength(1.0F) <= 0.0F)) {
            ci.cancel();
        }
    }

    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void purefps$syncFullbrightChange(float partialTicks, long nanoTime, CallbackInfo ci) {
        this.purefps$cachedFrameFovValid = false;
        boolean fb = PureFPSConfig.enabled && PureFPSConfig.fullbright;
        float strength = MashFlags.fullbrightStrength;
        if (fb != this.purefps$lastFullbrightState || Math.abs(strength - this.purefps$lastFullbrightStrength) > 0.5F) {
            this.purefps$lastFullbrightState = fb;
            this.purefps$lastFullbrightStrength = strength;
            this.purefps$fullbrightLightmapBaked = false;
            this.lightmapUpdateNeeded = true;
        }
    }

    @Shadow
    private float bossColorModifier;

    @Inject(method = "updateLightmap", at = @At("HEAD"), cancellable = true)
    private void purefps$beginFullbrightLightmap(float partialTicks, CallbackInfo ci) {
        if (!this.lightmapUpdateNeeded) {
            return;
        }
        if (PureFPSConfig.enabled && PureFPSConfig.fullbright && this.mc != null && this.mc.gameSettings != null) {
            float strength = MashFlags.fullbrightStrength;
            boolean nightVision = this.mc.thePlayer != null && this.mc.thePlayer.isPotionActive(Potion.nightVision);
            net.minecraft.world.World world = this.mc.theWorld;
            // En Fullbright maximal (>= 99 %), la table lightmap 16x16 est 100 % saturée à 1.0 -> inutile de ré-uploader
            // glTexSubImage2D 20 fois par seconde tant que le monde, NightVision et bossColorModifier n'ont pas changé !
            if (strength >= 99.0F
                    && this.purefps$fullbrightLightmapBaked
                    && world == this.purefps$lastLightmapWorld
                    && nightVision == this.purefps$lastLightmapNightVision
                    && this.bossColorModifier == this.purefps$lastLightmapBossColor
                    && Math.abs(strength - this.purefps$lastLightmapStrength) <= 0.5F) {
                this.lightmapUpdateNeeded = false;
                ci.cancel();
                return;
            }
            this.purefps$savedGamma = this.mc.gameSettings.gammaSetting;
            this.mc.gameSettings.gammaSetting = Math.max(1.0F, strength * 0.16F);
            this.purefps$overrodeGamma = true;
            this.purefps$lastLightmapWorld = world;
            this.purefps$lastLightmapStrength = strength;
            this.purefps$lastLightmapNightVision = nightVision;
            this.purefps$lastLightmapBossColor = this.bossColorModifier;
            this.purefps$fullbrightLightmapBaked = true;
        } else {
            this.purefps$fullbrightLightmapBaked = false;
        }
    }

    @Inject(method = "updateLightmap", at = @At("RETURN"))
    private void purefps$endFullbrightLightmap(float partialTicks, CallbackInfo ci) {
        if (this.purefps$overrodeGamma && this.mc != null && this.mc.gameSettings != null) {
            this.mc.gameSettings.gammaSetting = this.purefps$savedGamma;
            this.purefps$overrodeGamma = false;
        }
    }

    @Inject(method = "setupCameraTransform", at = @At("HEAD"))
    private void purefps$beginCameraMatrixRecord(float partialTicks, int pass, CallbackInfo ci) {
        if (PureFPSConfig.enabled) {
            if (this.mc != null) {
                dev.purefps.core.FastCameraMatrices.setViewport(0, 0, this.mc.displayWidth, this.mc.displayHeight);
            }
            dev.purefps.core.FastCameraMatrices.beginCameraSetup(dev.purefps.core.GlStateCache.matrixMode);
        }
    }

    @Redirect(
            method = "setupCameraTransform",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V", remap = false)
    )
    private void purefps$recordCameraPerspective(float fovy, float aspect, float zNear, float zFar) {
        if (dev.purefps.core.FastCameraMatrices.recording) {
            dev.purefps.core.FastCameraMatrices.onPerspective(fovy, aspect, zNear, zFar);
        }
        org.lwjgl.util.glu.Project.gluPerspective(fovy, aspect, zNear, zFar);
    }

    @Inject(method = "setupCameraTransform", at = @At("RETURN"))
    private void purefps$endCameraMatrixRecord(float partialTicks, int pass, CallbackInfo ci) {
        if (dev.purefps.core.FastCameraMatrices.recording) {
            dev.purefps.core.FastCameraMatrices.endCameraSetup();
        }
    }
}

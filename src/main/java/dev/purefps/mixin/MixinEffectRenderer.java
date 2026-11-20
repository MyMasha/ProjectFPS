package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.OcclusionCuller;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityDiggingFX;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.crash.CrashReport;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ReportedException;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Optimisations de EffectRenderer sans @Overwrite :
 * 1. Sortie immédiate de renderParticles et renderLitParticles quand les couches sont vides :
 *    zéro changement d'état OpenGL (blend/alpha/depthMask) et zéro sin/cos inutiles.
 * 2. Liaison de texture et Tessellator.draw() paresseux : si toutes les particules d'une couche sont
 *    hors du frustum caméra, zéro appel OpenGL n'est émis pour cette couche.
 * 3. Insertion circulaire O(1) sur addEffect, compaction O(N) en place sur updateEffectAlphaLayer,
 *    et grille 2x2x2 sur addBlockDestroyEffects.
 */
@Mixin(EffectRenderer.class)
public abstract class MixinEffectRenderer {

    @Shadow
    @Final
    private static ResourceLocation particleTextures;

    @Shadow
    protected World worldObj;

    @Shadow
    private List<EntityFX>[][] fxLayers;

    @Shadow
    private TextureManager renderer;

    @Shadow
    private void tickParticle(EntityFX p_178923_1_) {
    }

    @Shadow
    public abstract void addEffect(EntityFX effect);

    @Unique
    private static final EntityDiggingFX.Factory PUREFPS_DIGGING_FACTORY = new EntityDiggingFX.Factory();

    @Unique
    private final int[][] purefps$ringCursors = new int[4][2];

    @Inject(method = "addEffect", at = @At("HEAD"), cancellable = true)
    private void purefps$ringAddEffect(EntityFX effect, CallbackInfo ci) {
        if (!PureFPSConfig.enabled) {
            return;
        }
        ci.cancel();
        if (effect == null) {
            return;
        }
        int layer = effect.getFXLayer();
        int alphaIdx = effect.getAlpha() != 1.0F ? 0 : 1;
        List<EntityFX> list = this.fxLayers[layer][alphaIdx];
        int cap = PureFPSConfig.maxParticlesPerLayer;
        if (cap <= 0) {
            return;
        }
        int size = list.size();
        if (size >= cap) {
            int cursor = this.purefps$ringCursors[layer][alphaIdx];
            if (cursor >= size) {
                cursor = 0;
            }
            list.set(cursor, effect);
            this.purefps$ringCursors[layer][alphaIdx] = (cursor + 1 >= size) ? 0 : (cursor + 1);
            return;
        }
        list.add(effect);
    }

    @Inject(method = "updateEffectAlphaLayer", at = @At("HEAD"), cancellable = true)
    private void purefps$inPlaceCompactLayer(List<EntityFX> list, CallbackInfo ci) {
        if (!PureFPSConfig.enabled) {
            return;
        }
        ci.cancel();
        int size = list.size();
        if (size == 0) {
            return;
        }
        int write = 0;
        for (int i = 0; i < size; i++) {
            EntityFX fx = list.get(i);
            if (fx != null) {
                this.tickParticle(fx);
                if (!fx.isDead) {
                    if (write != i) {
                        list.set(write, fx);
                    }
                    write++;
                }
            }
        }
        // Troncature O(dead) depuis la fin (0 décalage mémoire dans ArrayList)
        for (int i = list.size() - 1; i >= write; i--) {
            list.remove(i);
        }
    }

    @Inject(method = "renderParticles", at = @At("HEAD"), cancellable = true)
    private void purefps$lazyRenderParticles(Entity entityIn, float partialTicks, CallbackInfo ci) {
        if (!PureFPSConfig.enabled) {
            return;
        }
        ci.cancel();

        boolean anyParticles = false;
        for (int i = 0; i < 3 && !anyParticles; i++) {
            if (!this.fxLayers[i][0].isEmpty() || !this.fxLayers[i][1].isEmpty()) {
                anyParticles = true;
            }
        }
        if (!anyParticles) {
            return;
        }

        float rotX = ActiveRenderInfo.getRotationX();
        float rotZ = ActiveRenderInfo.getRotationZ();
        float rotYZ = ActiveRenderInfo.getRotationYZ();
        float rotXY = ActiveRenderInfo.getRotationXY();
        float rotXZ = ActiveRenderInfo.getRotationXZ();
        EntityFX.interpPosX = entityIn.lastTickPosX + (entityIn.posX - entityIn.lastTickPosX) * (double) partialTicks;
        EntityFX.interpPosY = entityIn.lastTickPosY + (entityIn.posY - entityIn.lastTickPosY) * (double) partialTicks;
        EntityFX.interpPosZ = entityIn.lastTickPosZ + (entityIn.posZ - entityIn.lastTickPosZ) * (double) partialTicks;

        boolean cullFrustum = PureFPSConfig.particleFrustumCulling;
        boolean glStateOpened = false;

        for (int layer = 0; layer < 3; ++layer) {
            for (int alphaIdx = 0; alphaIdx < 2; ++alphaIdx) {
                List<EntityFX> list = this.fxLayers[layer][alphaIdx];
                int size = list.size();
                if (size == 0) {
                    continue;
                }

                Tessellator tessellator = null;
                WorldRenderer worldrenderer = null;
                boolean layerStarted = false;

                for (int k = 0; k < size; ++k) {
                    EntityFX entityfx = list.get(k);
                    if (entityfx == null) {
                        continue;
                    }
                    if (cullFrustum && !OcclusionCuller.isParticleInFrustum(entityfx.posX, entityfx.posY, entityfx.posZ, 1.0F)) {
                        PureFPSConfig.frameParticlesAccumulator++;
                        continue;
                    }

                    if (!glStateOpened) {
                        GlStateManager.enableBlend();
                        GlStateManager.blendFunc(770, 771);
                        GlStateManager.alphaFunc(516, 0.003921569F);
                        glStateOpened = true;
                    }
                    if (!layerStarted) {
                        GlStateManager.depthMask(alphaIdx == 1);
                        if (layer == 1) {
                            this.renderer.bindTexture(TextureMap.locationBlocksTexture);
                        } else {
                            this.renderer.bindTexture(particleTextures);
                        }
                        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                        tessellator = Tessellator.getInstance();
                        worldrenderer = tessellator.getWorldRenderer();
                        worldrenderer.begin(7, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
                        layerStarted = true;
                    }

                    try {
                        entityfx.renderParticle(worldrenderer, entityIn, partialTicks, rotX, rotXZ, rotZ, rotYZ, rotXY);
                    } catch (Exception | LinkageError throwable) {
                        CrashReport report = CrashReport.makeCrashReport(throwable, "Rendering Particle");
                        report.makeCategory("Particle being rendered");
                        throw new ReportedException(report);
                    }
                }

                if (layerStarted) {
                    tessellator.draw();
                }
            }
        }

        if (glStateOpened) {
            GlStateManager.depthMask(true);
            GlStateManager.disableBlend();
            GlStateManager.alphaFunc(516, 0.1F);
        }
    }

    @Inject(method = "renderLitParticles", at = @At("HEAD"), cancellable = true)
    private void purefps$skipEmptyLitParticles(Entity entityIn, float partialTick, CallbackInfo ci) {
        if (this.fxLayers[3][0].isEmpty() && this.fxLayers[3][1].isEmpty()) {
            ci.cancel();
        }
    }

    @Inject(method = "addBlockDestroyEffects", at = @At("HEAD"), cancellable = true)
    private void purefps$fastBlockDestroyEffects(BlockPos pos, IBlockState state, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.fastBlockBreakParticles) {
            return;
        }
        ci.cancel();
        Block block = state.getBlock();
        if (!block.isAir(this.worldObj, pos) && !block.addDestroyEffects(this.worldObj, pos, (EffectRenderer) (Object) this)) {
            state = block.getActualState(state, this.worldObj, pos);
            final int grid = 2;
            int bx = pos.getX();
            int by = pos.getY();
            int bz = pos.getZ();
            int stateId = Block.getStateId(state);

            for (int ix = 0; ix < grid; ++ix) {
                for (int iy = 0; iy < grid; ++iy) {
                    for (int iz = 0; iz < grid; ++iz) {
                        double px = (double) bx + ((double) ix + 0.5D) / (double) grid;
                        double py = (double) by + ((double) iy + 0.5D) / (double) grid;
                        double pz = (double) bz + ((double) iz + 0.5D) / (double) grid;
                        EntityDiggingFX fx = (EntityDiggingFX) PUREFPS_DIGGING_FACTORY.getEntityFX(
                                0, this.worldObj,
                                px, py, pz,
                                px - (double) bx - 0.5D,
                                py - (double) by - 0.5D,
                                pz - (double) bz - 0.5D,
                                stateId
                        );
                        if (fx != null) {
                            this.addEffect(fx.setBlockPos(pos));
                        }
                    }
                }
            }
        }
    }
}

package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityBeacon;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Culling de distance zéro-allocation des TileEntities (coffres, pancartes, crânes, bannières)
 * avec calcul unique de getDistanceSq et sans double test Frustum redondant.
 */
@Mixin(TileEntityRendererDispatcher.class)
public abstract class MixinTileEntityRendererDispatcher {

    @Shadow
    public World worldObj;

    @Shadow
    public double entityX;

    @Shadow
    public double entityY;

    @Shadow
    public double entityZ;

    @Shadow
    public static double staticPlayerX;

    @Shadow
    public static double staticPlayerY;

    @Shadow
    public static double staticPlayerZ;

    @Shadow
    public abstract void renderTileEntityAt(TileEntity tileEntityIn, double x, double y, double z, float partialTicks, int destroyStage);

    /**
     * @author PureFPS
     * @reason Culling de distance unifié sans CallbackInfo ni double calcul de distance/frustum
     */
    @Overwrite
    public void renderTileEntity(TileEntity tileentityIn, float partialTicks, int destroyStage) {
        double distSq = tileentityIn.getDistanceSq(this.entityX, this.entityY, this.entityZ);
        if (PureFPSConfig.enabled && destroyStage < 0
                && PureFPSConfig.tileEntityDistanceCulling
                && !(tileentityIn instanceof TileEntityBeacon)) {
            double maxDist = (double) PureFPSConfig.maxTileEntityRenderDistance;
            if (distSq > maxDist * maxDist) {
                PureFPSConfig.frameTileEntitiesAccumulator++;
                return;
            }
        }

        if (distSq < tileentityIn.getMaxRenderDistanceSquared()) {
            BlockPos blockpos = tileentityIn.getPos();
            if (!tileentityIn.hasFastRenderer()) {
                int combined = (PureFPSConfig.enabled && tileentityIn instanceof dev.purefps.core.TileEntityLightCache)
                        ? ((dev.purefps.core.TileEntityLightCache) tileentityIn).purefps$getCachedCombinedLight(this.worldObj, blockpos)
                        : this.worldObj.getCombinedLight(blockpos, 0);
                int lx = combined & 0xFFFF;
                int ly = (combined >>> 16) & 0xFFFF;
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, (float) lx, (float) ly);
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            }
            this.renderTileEntityAt(
                    tileentityIn,
                    (double) blockpos.getX() - staticPlayerX,
                    (double) blockpos.getY() - staticPlayerY,
                    (double) blockpos.getZ() - staticPlayerZ,
                    partialTicks,
                    destroyStage
            );
        }
    }
}

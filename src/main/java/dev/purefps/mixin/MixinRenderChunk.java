package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkRegionManager;
import dev.purefps.core.EnumCaches;
import dev.purefps.core.FastBiomeColors;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumWorldBlockLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

/**
 * Optimisations critiques de RenderChunk (PureClient 3.5) :
 * 1. Élimine le stall synchrone CPU<->GPU GlStateManager.getFloat(GL_MODELVIEW_MATRIX) dans initModelviewMatrix() :
 *    Vanilla exécutait 400 à 528 lectures bloquantes du pilote OpenGL à CHAQUE franchissement de bordure de chunk
 *    (et jusqu'à 17 424 lectures au chargement d'un monde !) juste pour calculer une matrice constante !
 * 2. Élimine les allocations répétées de EnumWorldBlockLayer.values() et EnumFacing.values().
 * 3. Réinitialise le cache de teinte biome ThreadLocal en début de compilation de chunk.
 */
@Mixin(RenderChunk.class)
public class MixinRenderChunk {

    @Shadow
    @Final
    private FloatBuffer modelviewMatrix;

    /**
     * @author PureClient
     * @reason Remplace les 400-528 appels synchrones bloquants glGetFloat(GL_MODELVIEW_MATRIX) par franchissement de chunk
     *         par une copie directe en RAM de la matrice anti-couture pré-calculée (zéro appel JNI OpenGL, zéro freeze).
     */
    @Overwrite
    private void initModelviewMatrix() {
        ChunkRegionManager.fillStaticAntiSeamMatrix(this.modelviewMatrix);
    }

    @Inject(method = "rebuildChunk", at = @At("HEAD"))
    private void purefps$onRebuildChunkStart(float x, float y, float z, ChunkCompileTaskGenerator generator, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastBiomeColors) {
            FastBiomeColors.onChunkRebuildStart();
        }
    }

    @Redirect(
            method = {"rebuildChunk", "resortTransparency", "deleteGlResources"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/EnumWorldBlockLayer;values()[Lnet/minecraft/util/EnumWorldBlockLayer;")
    )
    private EnumWorldBlockLayer[] purefps$cachedBlockLayers() {
        return (PureFPSConfig.enabled && PureFPSConfig.zeroAllocEnumCache)
                ? EnumCaches.BLOCK_LAYERS
                : EnumWorldBlockLayer.values();
    }

    @Redirect(
            method = "setPosition",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/EnumFacing;values()[Lnet/minecraft/util/EnumFacing;")
    )
    private EnumFacing[] purefps$cachedFacings() {
        return (PureFPSConfig.enabled && PureFPSConfig.zeroAllocEnumCache)
                ? EnumCaches.FACINGS
                : EnumFacing.values();
    }
}

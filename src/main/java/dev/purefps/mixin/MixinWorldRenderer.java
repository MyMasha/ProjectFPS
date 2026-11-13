package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.PrimitiveQuadSorter;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Remplace le tri de sommets translucides vanilla (qui alloue Integer[], BitSet, Comparator et IntBuffer.slice())
 * par un tri de primitives 100 % zéro-allocation sur ThreadLocal.
 */
@Mixin(WorldRenderer.class)
public class MixinWorldRenderer {

    @Shadow
    private IntBuffer rawIntBuffer;

    @Shadow
    private FloatBuffer rawFloatBuffer;

    @Shadow
    private int vertexCount;

    @Shadow
    private double xOffset;

    @Shadow
    private double yOffset;

    @Shadow
    private double zOffset;

    @Shadow
    private VertexFormat vertexFormat;

    @Inject(method = "sortVertexData", at = @At("HEAD"), cancellable = true)
    private void purefps$fastSortVertexData(float x, float y, float z, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastWorldVertexSort) {
            float camX = (float) ((double) x + this.xOffset);
            float camY = (float) ((double) y + this.yOffset);
            float camZ = (float) ((double) z + this.zOffset);
            PrimitiveQuadSorter.sortQuads(
                    this.rawIntBuffer,
                    this.rawFloatBuffer,
                    this.vertexCount,
                    this.vertexFormat.getIntegerSize(),
                    camX,
                    camY,
                    camZ
            );
            ci.cancel();
        }
    }
}

package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkRegionManager;
import dev.purefps.core.PrimitiveQuadSorter;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * 1. Tri de sommets translucides 100 % zéro-allocation sur ThreadLocal.
 * 2. Cuisson en place du facteur anti-couture (1.000001f) directement sur les threads ChunkRenderWorker
 *    à la fin de finishDrawing(), supprimant toute copie CPU sur le thread OpenGL principal !
 */
@Mixin(WorldRenderer.class)
public class MixinWorldRenderer {

    @Shadow
    private ByteBuffer byteBuffer;

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

    @Shadow
    private int drawMode;

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

    @Inject(method = "finishDrawing", at = @At("RETURN"))
    private void purefps$convertImmediateQuadsToNativeTriangles(CallbackInfo ci) {
        if (!PureFPSConfig.enabled
                || this.drawMode != 7
                || this.vertexFormat == DefaultVertexFormats.BLOCK
                || this.vertexCount < 4
                || (this.vertexCount & 3) != 0
                || this.rawIntBuffer == null
                || this.byteBuffer == null) {
            return;
        }
        int strideInts = this.vertexFormat.getIntegerSize();
        int strideBytes = this.vertexFormat.getNextOffset();
        if (strideInts <= 0 || strideBytes != (strideInts << 2)) {
            return;
        }
        int quadCount = this.vertexCount >> 2;
        int triVerts = quadCount * 6;
        int requiredBytes = triVerts * strideBytes;
        if (requiredBytes > this.byteBuffer.capacity() || (triVerts * strideInts) > this.rawIntBuffer.capacity()) {
            return;
        }

        IntBuffer ib = this.rawIntBuffer;
        for (int q = quadCount - 1; q >= 0; q--) {
            int v0 = (q << 2) * strideInts;
            int v1 = v0 + strideInts;
            int v2 = v1 + strideInts;
            int v3 = v2 + strideInts;

            int d0 = (q * 6) * strideInts;
            int d1 = d0 + strideInts;
            int d2 = d1 + strideInts;
            int d3 = d2 + strideInts;
            int d4 = d3 + strideInts;
            int d5 = d4 + strideInts;

            for (int k = 0; k < strideInts; k++) {
                ib.put(d5 + k, ib.get(v0 + k));
                ib.put(d4 + k, ib.get(v3 + k));
                ib.put(d3 + k, ib.get(v2 + k));
            }
            if (q > 0) {
                for (int k = 0; k < strideInts; k++) {
                    ib.put(d2 + k, ib.get(v2 + k));
                    ib.put(d1 + k, ib.get(v1 + k));
                    ib.put(d0 + k, ib.get(v0 + k));
                }
            }
        }

        this.drawMode = 4; // GL11.GL_TRIANGLES
        this.vertexCount = triVerts;
        this.byteBuffer.position(0);
        this.byteBuffer.limit(requiredBytes);
    }
}

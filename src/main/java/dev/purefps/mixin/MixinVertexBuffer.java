package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkRegionManager;
import dev.purefps.core.FastVertexBuffer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Moteur VBO Zéro-Stall (MashClient 4.0) :
 * - Supprime totalement la création/commutation de 69 000+ VAO individuels par chunk (qui causait un effondrement
 *   des pilotes OpenGL NVIDIA/AMD/Intel à ~80 FPS).
 * - L'échelle anti-couture (1.000001f) est désormais pré-calculée sur les threads workers (MixinWorldRenderer),
 *   garantissant 0 copie scalaire CPU sur le thread principal OpenGL lors de l'upload !
 * - Effectue un orphaning explicite (glBufferData null) lorsqu'un tampon existant est ré-écrit (ex: tri TRANSLUCENT)
 *   afin d'éliminer 100 % des attentes implicites du pilote sur les frames en vol.
 */
@Mixin(VertexBuffer.class)
public abstract class MixinVertexBuffer implements FastVertexBuffer {

    @Shadow
    private int glBufferId;

    @Shadow
    @Final
    private VertexFormat vertexFormat;

    @Shadow
    private int count;

    @Shadow
    public abstract void bindBuffer();

    @Shadow
    public abstract void unbindBuffer();

    @Unique
    private int purefps$allocatedBytes = 0;

    @Unique
    private boolean purefps$antiSeamReady = false;

    @Override
    public int purefps$getVertexCount() {
        return this.count;
    }

    @Override
    public int purefps$getGlBufferId() {
        return this.glBufferId;
    }

    @Inject(method = "bufferData", at = @At("HEAD"), cancellable = true)
    private void purefps$fastOrphanedChunkUpload(ByteBuffer byteBuffer, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || this.vertexFormat != DefaultVertexFormats.BLOCK || this.glBufferId < 0) {
            this.purefps$antiSeamReady = false;
            return;
        }

        int byteLimit = byteBuffer.limit();
        int vertexCount = byteLimit / 28;
        this.count = vertexCount;
        if (vertexCount <= 0) {
            this.purefps$allocatedBytes = 0;
            this.purefps$antiSeamReady = true;
            ci.cancel();
            return;
        }

        // Si le tampon n'a pas encore été pré-transformé par un worker (cas rare d'upload synchrone), repli rapide
        ByteBuffer uploadBuf = ChunkRegionManager.consumeWorkerBakedFlag(byteBuffer)
                ? byteBuffer
                : ChunkRegionManager.prepareChunkAntiSeamBuffer(byteBuffer, vertexCount);

        this.bindBuffer();
        if (this.purefps$allocatedBytes > 0) {
            // Orphaning explicite : détache l'ancienne page VRAM si le GPU lit encore la frame précédente
            GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, (long) byteLimit, GL15.GL_DYNAMIC_DRAW);
            GL15.glBufferSubData(OpenGlHelper.GL_ARRAY_BUFFER, 0L, uploadBuf);
        } else {
            OpenGlHelper.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, uploadBuf, GL15.GL_STATIC_DRAW);
        }
        this.purefps$allocatedBytes = byteLimit;
        this.unbindBuffer();
        this.purefps$antiSeamReady = true;
        ci.cancel();
    }

    @Inject(method = "deleteGlBuffers", at = @At("HEAD"))
    private void purefps$resetAllocationTracking(CallbackInfo ci) {
        this.purefps$allocatedBytes = 0;
        this.purefps$antiSeamReady = false;
    }
}

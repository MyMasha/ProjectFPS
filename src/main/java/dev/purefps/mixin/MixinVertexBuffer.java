package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkRegionManager;
import dev.purefps.core.FastVertexBuffer;
import dev.purefps.core.QuadIndexBuffer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Moteur VBO Zéro-Stall par section de chunk + VAO matériel OpenGL 3.0 + EBO GL_TRIANGLES :
 * - Remplace les mises à jour bloquantes glBufferSubData sur tampon partagé en vol par un orphaning
 *   glBufferData individuel sans aucune attente pipeline GPU (zéro micro-freeze en mouvement).
 * - Intègre directement le facteur anti-couture (1.000001f autour de 8,8,8) lors de l'upload afin d'éliminer
 *   les ~600 appels multModelviewMatrix() par frame.
 * - Associe un VAO matériel GL30 par VertexBuffer lié au QuadIndexBuffer partagé pour dessiner en
 *   GL_TRIANGLES natifs en 2 appels JNI seulement (glBindVertexArray + glDrawElements).
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
    private int purefps$vaoId = -1;

    @Unique
    private int purefps$boundEboId = -1;

    @Unique
    private boolean purefps$antiSeamBaked = false;

    @Override
    public int purefps$getVertexCount() {
        return this.count;
    }

    @Inject(method = "bufferData", at = @At("HEAD"), cancellable = true)
    private void purefps$fastChunkBufferData(ByteBuffer byteBuffer, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || this.vertexFormat != DefaultVertexFormats.BLOCK || this.glBufferId < 0) {
            this.purefps$antiSeamBaked = false;
            return;
        }
        int byteLimit = byteBuffer.limit();
        int vertexCount = byteLimit / 28;
        this.count = vertexCount;
        if (vertexCount <= 0) {
            this.purefps$antiSeamBaked = true;
            ci.cancel();
            return;
        }

        ByteBuffer staged = ChunkRegionManager.prepareChunkAntiSeamBuffer(byteBuffer, vertexCount);
        this.bindBuffer();
        OpenGlHelper.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, staged, GL15.GL_STATIC_DRAW);
        this.unbindBuffer();
        this.purefps$antiSeamBaked = true;
        ci.cancel();
    }

    @Override
    public boolean purefps$drawChunkTriangles(boolean allowHardwareVao) {
        int vCount = this.count;
        if (vCount <= 0 || this.glBufferId < 0) {
            return false;
        }

        boolean canIndex = QuadIndexBuffer.isSupported() && (vCount & 3) == 0;
        if (!canIndex) {
            this.bindBuffer();
            ChunkRegionManager.setupBlockArrayPointers();
            GL11.glDrawArrays(GL11.GL_QUADS, 0, vCount);
            return false;
        }

        boolean useVao = allowHardwareVao && ChunkRegionManager.hasHardwareVao();
        if (useVao) {
            if (this.purefps$vaoId < 0) {
                GL30.glBindVertexArray(0);
                int targetEbo = QuadIndexBuffer.ensureIndexBufferReady(vCount);
                int vao = GL30.glGenVertexArrays();
                GL30.glBindVertexArray(vao);
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, this.glBufferId);
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glVertexPointer(3, GL11.GL_FLOAT, 28, 0L);
                GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
                GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 28, 12L);
                GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 28, 16L);
                OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
                GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glTexCoordPointer(2, GL11.GL_SHORT, 28, 24L);
                OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, targetEbo);
                this.purefps$vaoId = vao;
                this.purefps$boundEboId = targetEbo;
            } else {
                if (vCount > QuadIndexBuffer.MAX_USHORT_VERTICES) {
                    GL30.glBindVertexArray(0);
                    int targetEbo = QuadIndexBuffer.ensureIndexBufferReady(vCount);
                    GL30.glBindVertexArray(this.purefps$vaoId);
                    if (this.purefps$boundEboId != targetEbo) {
                        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, targetEbo);
                        this.purefps$boundEboId = targetEbo;
                    }
                } else {
                    GL30.glBindVertexArray(this.purefps$vaoId);
                    int ushortEbo = QuadIndexBuffer.ensureIndexBufferReady(QuadIndexBuffer.MAX_USHORT_VERTICES);
                    if (this.purefps$boundEboId != ushortEbo) {
                        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ushortEbo);
                        this.purefps$boundEboId = ushortEbo;
                    }
                }
            }
            int indexCount = QuadIndexBuffer.quadVertexCountToIndexCount(vCount);
            GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, QuadIndexBuffer.resolveIndexType(vCount), 0L);
            return true;
        }

        int targetEbo = QuadIndexBuffer.ensureIndexBufferReady(vCount);
        this.bindBuffer();
        ChunkRegionManager.setupBlockArrayPointers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, targetEbo);
        int indexCount = QuadIndexBuffer.quadVertexCountToIndexCount(vCount);
        GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, QuadIndexBuffer.resolveIndexType(vCount), 0L);
        return false;
    }

    public boolean purefps$hasAntiSeamBaked() {
        return this.purefps$antiSeamBaked;
    }

    @Inject(method = "deleteGlBuffers", at = @At("HEAD"))
    private void purefps$deleteHardwareVao(CallbackInfo ci) {
        if (this.purefps$vaoId >= 0) {
            try {
                GL30.glDeleteVertexArrays(this.purefps$vaoId);
            } catch (Throwable ignored) {
            }
            this.purefps$vaoId = -1;
            this.purefps$boundEboId = -1;
        }
        this.purefps$antiSeamBaked = false;
    }
}

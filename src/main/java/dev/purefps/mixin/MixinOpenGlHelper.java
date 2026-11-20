package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.GlStateCache;
import dev.purefps.core.LightmapStateCache;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ARBMultitexture;
import org.lwjgl.opengl.ARBVertexBufferObject;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Cache d'état OpenGL zéro-allocation sur OpenGlHelper :
 * - Déduplique setLightmapTextureCoords (GL_TEXTURE1), glBindBuffer (GL_ARRAY_BUFFER),
 *   setClientActiveTexture et glBindFramebuffer (GL_FRAMEBUFFER) sans CallbackInfo.
 */
@Mixin(OpenGlHelper.class)
public class MixinOpenGlHelper {

    @Shadow
    private static boolean arbMultitexture;

    @Shadow
    private static boolean arbVbo;

    @Shadow
    public static boolean framebufferSupported;

    @Shadow
    private static int framebufferType;

    @Shadow
    public static int lightmapTexUnit;

    @Shadow
    public static int GL_ARRAY_BUFFER;

    @Shadow
    public static int GL_FRAMEBUFFER;

    @Shadow(remap = false)
    public static float lastBrightnessX;

    @Shadow(remap = false)
    public static float lastBrightnessY;

    /**
     * @author PureFPS
     * @reason Déduplication directe zéro-allocation des coordonnées lightmap OpenGL
     */
    @Overwrite
    public static void setLightmapTextureCoords(int target, float x, float y) {
        if (target == lightmapTexUnit) {
            lastBrightnessX = x;
            lastBrightnessY = y;
            if (PureFPSConfig.enabled && PureFPSConfig.fastLightmapCoords && LightmapStateCache.checkAndUpdate(x, y)) {
                return;
            }
        }
        if (arbMultitexture) {
            ARBMultitexture.glMultiTexCoord2fARB(target, x, y);
        } else {
            GL13.glMultiTexCoord2f(target, x, y);
        }
    }

    /**
     * @author MashClient
     * @reason Déduplication zéro-allocation de glClientActiveTexture
     */
    @Overwrite
    public static void setClientActiveTexture(int texture) {
        if (PureFPSConfig.enabled && GlStateCache.clientActiveTexture == texture) {
            return;
        }
        GlStateCache.clientActiveTexture = texture;
        if (arbMultitexture) {
            ARBMultitexture.glClientActiveTextureARB(texture);
        } else {
            GL13.glClientActiveTexture(texture);
        }
    }

    /**
     * @author MashClient
     * @reason Déduplication zéro-allocation de glBindBuffer(GL_ARRAY_BUFFER)
     */
    @Overwrite
    public static void glBindBuffer(int target, int buffer) {
        if (target == GL_ARRAY_BUFFER) {
            if (PureFPSConfig.enabled && GlStateCache.boundArrayBuffer == buffer) {
                return;
            }
            GlStateCache.boundArrayBuffer = buffer;
        }
        if (arbVbo) {
            ARBVertexBufferObject.glBindBufferARB(target, buffer);
        } else {
            GL15.glBindBuffer(target, buffer);
        }
    }

    /**
     * @author MashClient
     * @reason Invalide le cache GL_ARRAY_BUFFER lors de la destruction d'un tampon VBO
     */
    @Overwrite
    public static void glDeleteBuffers(int buffer) {
        GlStateCache.onBufferDeleted(buffer);
        if (arbVbo) {
            ARBVertexBufferObject.glDeleteBuffersARB(buffer);
        } else {
            GL15.glDeleteBuffers(buffer);
        }
    }

    /**
     * @author MashClient
     * @reason Déduplication zéro-allocation de glBindFramebuffer(GL_FRAMEBUFFER)
     */
    @Overwrite
    public static void glBindFramebuffer(int target, int framebufferIn) {
        if (framebufferSupported) {
            if (target == GL_FRAMEBUFFER) {
                if (PureFPSConfig.enabled && GlStateCache.boundFramebuffer == framebufferIn) {
                    return;
                }
                GlStateCache.boundFramebuffer = framebufferIn;
            } else {
                GlStateCache.boundFramebuffer = -1;
            }
            switch (framebufferType) {
                case 0:
                    GL30.glBindFramebuffer(target, framebufferIn);
                    break;
                case 1:
                    ARBFramebufferObject.glBindFramebuffer(target, framebufferIn);
                    break;
                case 2:
                    EXTFramebufferObject.glBindFramebufferEXT(target, framebufferIn);
                    break;
            }
        }
    }
}

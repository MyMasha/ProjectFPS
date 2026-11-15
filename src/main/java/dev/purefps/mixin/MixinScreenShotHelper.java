package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.AsyncScreenshotManager;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ScreenShotHelper;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.nio.IntBuffer;

/**
 * Capture d'écran F2 asynchrone sans micro-freeze :
 * Lit uniquement le tampon OpenGL sur le thread principal, puis déporte l'inversion verticale,
 * la conversion RGB et la compression PNG sur un thread démon dédié avec boutons [Ouvrir] / [Copier].
 */
@Mixin(ScreenShotHelper.class)
public class MixinScreenShotHelper {

    @Shadow
    @Final
    private static Logger logger;

    @Shadow
    private static IntBuffer pixelBuffer;

    @Inject(
            method = "saveScreenshot(Ljava/io/File;Ljava/lang/String;IILnet/minecraft/client/shader/Framebuffer;)Lnet/minecraft/util/IChatComponent;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void purefps$saveScreenshotAsync(
            File gameDirectory,
            String screenshotName,
            int width,
            int height,
            Framebuffer buffer,
            CallbackInfoReturnable<IChatComponent> cir
    ) {
        if (!PureFPSConfig.enabled) {
            return;
        }
        try {
            boolean fboMode = OpenGlHelper.isFramebufferEnabled() && buffer != null;
            int texW = fboMode ? buffer.framebufferTextureWidth : width;
            int texH = fboMode ? buffer.framebufferTextureHeight : height;
            int viewW = fboMode ? buffer.framebufferWidth : width;
            int viewH = fboMode ? buffer.framebufferHeight : height;

            int totalPixels = texW * texH;
            if (totalPixels <= 0) {
                return;
            }
            if (pixelBuffer == null || pixelBuffer.capacity() < totalPixels) {
                pixelBuffer = BufferUtils.createIntBuffer(totalPixels);
            }

            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            pixelBuffer.clear();

            if (fboMode) {
                GlStateManager.bindTexture(buffer.framebufferTexture);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
            } else {
                GL11.glReadPixels(0, 0, texW, texH, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
            }

            int[] rawCopy = new int[totalPixels];
            pixelBuffer.get(rawCopy);

            File screenshotsDir = new File(gameDirectory, "screenshots");
            File targetFile = AsyncScreenshotManager.allocateScreenshotFile(screenshotsDir, screenshotName);
            IChatComponent msg = AsyncScreenshotManager.submitAsyncEncode(
                    targetFile, rawCopy, texW, texH, viewW, viewH, fboMode
            );
            cir.setReturnValue(msg);
        } catch (Throwable t) {
            logger.warn("Async screenshot capture failed", t);
            cir.setReturnValue(new ChatComponentTranslation("screenshot.failure", t.getMessage()));
        }
    }
}

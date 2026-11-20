package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.GpuUploadBudget;
import dev.purefps.core.OcclusionCuller;
import dev.purefps.core.SkinDownloadPool;
import net.minecraft.client.renderer.IImageBuffer;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Élimine les freezes d'upload OpenGL synchrone sur Hypixel (quand 30 à 60 skins de joueurs
 * terminent leur téléchargement HTTP simultanément) et remplace le spawn illimité de threads OS
 * par le pool borné à 4 threads SkinDownloadPool.
 */
@Mixin(ThreadDownloadImageData.class)
public abstract class MixinThreadDownloadImageData extends SimpleTexture {

    @Shadow
    @Final
    private File cacheFile;

    @Shadow
    @Final
    private String imageUrl;

    @Shadow
    @Final
    private IImageBuffer imageBuffer;

    @Shadow
    private BufferedImage bufferedImage;

    @Shadow
    private Thread imageThread;

    @Shadow
    private boolean textureUploaded;

    protected MixinThreadDownloadImageData(ResourceLocation textureResourceLocation) {
        super(textureResourceLocation);
    }

    /**
     * @author PureFPS
     * @reason Lissage sans allocation (zéro CallbackInfo) des uploads glTexImage2D de skins HTTP à 1 par frame
     */
    @Overwrite
    private void checkTextureUploaded() {
        if (!this.textureUploaded && this.bufferedImage != null) {
            if (PureFPSConfig.enabled && PureFPSConfig.asyncSkinUploadPacing) {
                if (!GpuUploadBudget.tryAcquireSkinUpload(OcclusionCuller.isInWorld())) {
                    return;
                }
            }
            if (this.textureLocation != null) {
                this.deleteGlTexture();
            }
            TextureUtil.uploadTextureImage(super.getGlTextureId(), this.bufferedImage);
            this.textureUploaded = true;
            this.bufferedImage = null;
        }
    }

    /**
     * @author PureFPS
     * @reason Exécute les téléchargements HTTP de skins/capes dans un pool borné à 4 threads daemon
     */
    @Overwrite
    protected void loadTextureFromServer() {
        this.imageThread = SkinDownloadPool.MARKER_THREAD;
        SkinDownloadPool.submitDownload((ThreadDownloadImageData) (Object) this, this.cacheFile, this.imageUrl, this.imageBuffer);
    }
}

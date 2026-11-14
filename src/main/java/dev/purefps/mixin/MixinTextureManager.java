package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Optimisation matérielle de TextureManager.bindTexture (MashClient 4.3) :
 * - Cache MRU d'identité à 2 entrées évitant des centaines de hachages ResourceLocation + recherches HashMap
 *   par frame sur TextureMap.locationBlocksTexture, les skins joueurs et les icônes d'inventaire.
 */
@Mixin(TextureManager.class)
public abstract class MixinTextureManager {

    @Shadow
    @Final
    private Map<ResourceLocation, ITextureObject> mapTextureObjects;

    @Shadow
    public abstract boolean loadTexture(ResourceLocation textureLocation, ITextureObject textureObj);

    @Unique
    private ResourceLocation purefps$mruKey0;
    @Unique
    private ITextureObject purefps$mruTex0;
    @Unique
    private ResourceLocation purefps$mruKey1;
    @Unique
    private ITextureObject purefps$mruTex1;

    /**
     * @author MashClient
     * @reason Court-circuite le hachage HashMap répétitif de bindTexture grâce à un cache MRU d'identité O(1)
     */
    @Overwrite
    public void bindTexture(ResourceLocation resource) {
        if (resource == null) {
            return;
        }
        if (PureFPSConfig.enabled) {
            if (resource == this.purefps$mruKey0 && this.purefps$mruTex0 != null) {
                GlStateManager.bindTexture(this.purefps$mruTex0.getGlTextureId());
                return;
            }
            if (resource == this.purefps$mruKey1 && this.purefps$mruTex1 != null) {
                ResourceLocation k = this.purefps$mruKey1;
                ITextureObject t = this.purefps$mruTex1;
                this.purefps$mruKey1 = this.purefps$mruKey0;
                this.purefps$mruTex1 = this.purefps$mruTex0;
                this.purefps$mruKey0 = k;
                this.purefps$mruTex0 = t;
                GlStateManager.bindTexture(t.getGlTextureId());
                return;
            }
        }

        ITextureObject tex = this.mapTextureObjects.get(resource);
        if (tex == null) {
            tex = new SimpleTexture(resource);
            this.loadTexture(resource, tex);
        }
        this.purefps$mruKey1 = this.purefps$mruKey0;
        this.purefps$mruTex1 = this.purefps$mruTex0;
        this.purefps$mruKey0 = resource;
        this.purefps$mruTex0 = tex;
        GlStateManager.bindTexture(tex.getGlTextureId());
    }

    @Inject(method = "loadTexture", at = @At("HEAD"))
    private void purefps$invalidateMruOnLoad(
            ResourceLocation textureLocation,
            ITextureObject textureObj,
            CallbackInfoReturnable<Boolean> cir
    ) {
        this.purefps$mruKey0 = null;
        this.purefps$mruTex0 = null;
        this.purefps$mruKey1 = null;
        this.purefps$mruTex1 = null;
    }

    @Inject(method = "deleteTexture", at = @At("HEAD"))
    private void purefps$invalidateMruOnDelete(ResourceLocation textureLocation, CallbackInfo ci) {
        this.purefps$mruKey0 = null;
        this.purefps$mruTex0 = null;
        this.purefps$mruKey1 = null;
        this.purefps$mruTex1 = null;
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void purefps$invalidateMruOnReload(IResourceManager resourceManager, CallbackInfo ci) {
        this.purefps$mruKey0 = null;
        this.purefps$mruTex0 = null;
        this.purefps$mruKey1 = null;
        this.purefps$mruTex1 = null;
    }
}

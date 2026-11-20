package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureUtil;
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

import java.util.Arrays;
import java.util.Map;

/**
 * Optimisation matérielle de TextureManager.bindTexture (MashClient 4.5.15) :
 * - Cache d'identité 64 entrées à sondage 2-way O(1) (couvre atlas blocs + 20+ skins joueurs + UI sans thrashing).
 * - Correction du cas loadTexture == false : récupère TextureUtil.missingTexture depuis mapTextureObjects au lieu
 *   de mettre en cache un SimpleTexture non chargé.
 */
@Mixin(TextureManager.class)
public abstract class MixinTextureManager {

    @Shadow
    @Final
    private Map<ResourceLocation, ITextureObject> mapTextureObjects;

    @Shadow
    public abstract boolean loadTexture(ResourceLocation textureLocation, ITextureObject textureObj);

    @Unique
    private static final int PUREFPS_TEX_CACHE_SIZE = 64;
    @Unique
    private static final int PUREFPS_TEX_CACHE_MASK = PUREFPS_TEX_CACHE_SIZE - 1;
    @Unique
    private final ResourceLocation[] purefps$cacheKeys = new ResourceLocation[PUREFPS_TEX_CACHE_SIZE];
    @Unique
    private final ITextureObject[] purefps$cacheVals = new ITextureObject[PUREFPS_TEX_CACHE_SIZE];

    /**
     * @author MashClient
     * @reason Court-circuite le hachage HashMap répétitif de bindTexture via cache d'identité 64 entrées 2-way O(1)
     */
    @Overwrite
    public void bindTexture(ResourceLocation resource) {
        if (resource == null) {
            return;
        }
        int h = System.identityHashCode(resource);
        int s0 = (h ^ (h >>> 7)) & PUREFPS_TEX_CACHE_MASK;
        int s1 = (s0 + 1) & PUREFPS_TEX_CACHE_MASK;

        if (PureFPSConfig.enabled) {
            if (this.purefps$cacheKeys[s0] == resource) {
                ITextureObject t0 = this.purefps$cacheVals[s0];
                if (t0 != null) {
                    GlStateManager.bindTexture(t0.getGlTextureId());
                    return;
                }
            }
            if (this.purefps$cacheKeys[s1] == resource) {
                ITextureObject t1 = this.purefps$cacheVals[s1];
                if (t1 != null) {
                    GlStateManager.bindTexture(t1.getGlTextureId());
                    return;
                }
            }
        }

        ITextureObject tex = this.mapTextureObjects.get(resource);
        if (tex == null) {
            tex = new SimpleTexture(resource);
            if (!this.loadTexture(resource, tex)) {
                ITextureObject fallback = this.mapTextureObjects.get(resource);
                tex = fallback != null ? fallback : TextureUtil.missingTexture;
            }
        }
        if (this.purefps$cacheKeys[s0] != null && this.purefps$cacheKeys[s0] != resource) {
            this.purefps$cacheKeys[s1] = this.purefps$cacheKeys[s0];
            this.purefps$cacheVals[s1] = this.purefps$cacheVals[s0];
        }
        this.purefps$cacheKeys[s0] = resource;
        this.purefps$cacheVals[s0] = tex;
        GlStateManager.bindTexture(tex.getGlTextureId());
    }

    @Unique
    private void purefps$clearIdentityCache() {
        Arrays.fill(this.purefps$cacheKeys, null);
        Arrays.fill(this.purefps$cacheVals, null);
    }

    @Inject(method = "loadTexture", at = @At("HEAD"))
    private void purefps$invalidateMruOnLoad(
            ResourceLocation textureLocation,
            ITextureObject textureObj,
            CallbackInfoReturnable<Boolean> cir
    ) {
        this.purefps$clearIdentityCache();
    }

    @Inject(method = "deleteTexture", at = @At("HEAD"))
    private void purefps$invalidateMruOnDelete(ResourceLocation textureLocation, CallbackInfo ci) {
        this.purefps$clearIdentityCache();
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void purefps$invalidateMruOnReload(IResourceManager resourceManager, CallbackInfo ci) {
        this.purefps$clearIdentityCache();
    }
}

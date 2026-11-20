package dev.purefps.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastSkullCache;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySkullRenderer;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Élimine le goulot massif de SkinManager.loadSkinFromCache + SkinManager.loadSkin (Guava LoadingCache +
 * FilenameUtils.getBaseName + StringBuilder + new ResourceLocation + TextureManager.getTexture) exécuté
 * à chaque frame sur chaque crâne custom sur Hypixel (lobbies, SkyBlock, BedWars, menus).
 * Utilise des @Redirect chirurgicaux zéro-allocation (aucun @Overwrite) + restauration propre de GlStateManager.enableCull().
 */
@Mixin(TileEntitySkullRenderer.class)
public abstract class MixinTileEntitySkullRenderer {

    @Redirect(
            method = "renderSkull",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/SkinManager;loadSkinFromCache(Lcom/mojang/authlib/GameProfile;)Ljava/util/Map;"
            )
    )
    private Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> purefps$redirectSkullCacheLookup(
            SkinManager skinManager, GameProfile profile) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastSkullRendering) {
            return FastSkullCache.beginFastSkullLookup(profile);
        }
        return skinManager.loadSkinFromCache(profile);
    }

    @Redirect(
            method = "renderSkull",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/SkinManager;loadSkin(Lcom/mojang/authlib/minecraft/MinecraftProfileTexture;Lcom/mojang/authlib/minecraft/MinecraftProfileTexture$Type;)Lnet/minecraft/util/ResourceLocation;"
            )
    )
    private ResourceLocation purefps$redirectSkullLoadSkin(
            SkinManager skinManager, MinecraftProfileTexture texture, MinecraftProfileTexture.Type type) {
        if (texture == FastSkullCache.SENTINEL_TEXTURE) {
            return FastSkullCache.consumeFastSkullTexture();
        }
        return skinManager.loadSkin(texture, type);
    }

    @Inject(
            method = "renderSkull",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;popMatrix()V",
                    ordinal = 0
            )
    )
    private void purefps$restoreCullAfterSkull(
            float x,
            float y,
            float z,
            EnumFacing facing,
            float rotationIn,
            int skullType,
            GameProfile profile,
            int destroyStage,
            CallbackInfo ci
    ) {
        GlStateManager.enableCull();
    }
}

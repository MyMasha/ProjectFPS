package dev.purefps.mixin;

import com.mojang.authlib.GameProfile;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastSkullCache;
import net.minecraft.client.renderer.entity.layers.LayerCustomHead;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Élimine le décodage NBTUtil.readGameProfileFromNBT (UUID.fromString + LinkedHashMultimap + new GameProfile)
 * exécuté à chaque frame sur chaque tête portée par un joueur, un PNJ ou un ArmorStand (pets/minions Hypixel).
 */
@Mixin(LayerCustomHead.class)
public class MixinLayerCustomHead {

    @Redirect(
            method = "doRenderLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/nbt/NBTUtil;readGameProfileFromNBT(Lnet/minecraft/nbt/NBTTagCompound;)Lcom/mojang/authlib/GameProfile;"
            )
    )
    private GameProfile purefps$readCachedHeadProfile(NBTTagCompound compound) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastSkullRendering) {
            return FastSkullCache.readCachedProfile(compound);
        }
        return NBTUtil.readGameProfileFromNBT(compound);
    }
}

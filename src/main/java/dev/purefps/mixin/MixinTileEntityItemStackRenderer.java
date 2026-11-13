package dev.purefps.mixin;

import com.mojang.authlib.GameProfile;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastSkullCache;
import net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Élimine le décodage NBTUtil.readGameProfileFromNBT exécuté à chaque frame sur chaque icône de crâne custom
 * dans les menus Chest GUI Hypixel, la barre d'inventaire et les mains des joueurs.
 */
@Mixin(TileEntityItemStackRenderer.class)
public class MixinTileEntityItemStackRenderer {

    @Redirect(
            method = "renderByItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/nbt/NBTUtil;readGameProfileFromNBT(Lnet/minecraft/nbt/NBTTagCompound;)Lcom/mojang/authlib/GameProfile;"
            )
    )
    private GameProfile purefps$readCachedItemSkullProfile(NBTTagCompound compound) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastSkullRendering) {
            return FastSkullCache.readCachedProfile(compound);
        }
        return NBTUtil.readGameProfileFromNBT(compound);
    }
}

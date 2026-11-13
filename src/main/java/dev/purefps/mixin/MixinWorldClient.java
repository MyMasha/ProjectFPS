package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldSettings;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Élimine la boucle inutile de 1 000 itérations aléatoires (BlockPos + getBlockState + randomDisplayTick)
 * exécutée chaque tick dans WorldClient.doVoidFogParticles lorsque le joueur n'est pas en mode Créatif
 * avec un bloc Barrière en main.
 */
@Mixin(WorldClient.class)
public class MixinWorldClient {

    @Shadow
    @Final
    private Minecraft mc;

    @Inject(method = "doVoidFogParticles", at = @At("HEAD"), cancellable = true)
    private void purefps$skipUselessVoidBarrierLoop(int posX, int posY, int posZ, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.skipVoidFogLoop) {
            return;
        }
        if (this.mc == null || this.mc.playerController == null || this.mc.thePlayer == null) {
            ci.cancel();
            return;
        }
        if (this.mc.playerController.getCurrentGameType() != WorldSettings.GameType.CREATIVE) {
            // En Survie/Aventure (99.9% de Hypixel PvP), seules les randomDisplayTick de torches/lave proches ont un sens :
            // en profil FPS, on coupe totalement la boucle de 1000 lookups aléatoires.
            if (PureFPSConfig.activeProfile == dev.purefps.config.PureFPSProfile.FPS) {
                ci.cancel();
            }
            return;
        }
        ItemStack held = this.mc.thePlayer.getHeldItem();
        if (held == null || held.getItem() != Item.getItemFromBlock(Blocks.barrier)) {
            if (PureFPSConfig.activeProfile == dev.purefps.config.PureFPSProfile.FPS) {
                ci.cancel();
            }
        }
    }
}

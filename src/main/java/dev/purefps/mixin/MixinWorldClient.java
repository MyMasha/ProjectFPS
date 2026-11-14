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
 * Élimine dans TOUS les profils (FPS, Équilibré, Personnalisé) la boucle inutile de 1 000 itérations aléatoires
 * exécutée chaque tick dans WorldClient.doVoidFogParticles lorsque le joueur ne tient pas un bloc Barrière en Créatif.
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
            ci.cancel();
            return;
        }
        ItemStack held = this.mc.thePlayer.getHeldItem();
        if (held == null || held.getItem() != Item.getItemFromBlock(Blocks.barrier)) {
            ci.cancel();
        }
    }
}

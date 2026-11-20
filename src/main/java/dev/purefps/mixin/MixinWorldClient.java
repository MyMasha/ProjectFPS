package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.SmartChunkUpdateScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.projectile.EntityFireball;
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
 * 1. Élimine la boucle inutile de 1 000 itérations aléatoires dans WorldClient.doVoidFogParticles.
 * 2. Détecte l'apparition de projectiles/explosifs (Fireball, TNT, FallingBlock, Dragon, Wither visible)
 *    pour dégeler instantanément les chunks concernés dans SmartChunkUpdateScheduler.
 *    (Les changements de blocs S23/S22 sont déjà interceptés une seule fois dans MixinWorld.setBlockState.)
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

    @Inject(method = "addEntityToWorld", at = @At("HEAD"))
    private void purefps$markExplosiveOrDestructiveEntityUrgent(int entityID, Entity entityToSpawn, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.smartChunkUpdates && entityToSpawn != null) {
            if (entityToSpawn instanceof EntityWither && entityToSpawn.isInvisible()) {
                return;
            }
            if (entityToSpawn instanceof EntityFireball
                    || entityToSpawn instanceof EntityTNTPrimed
                    || entityToSpawn instanceof EntityFallingBlock
                    || entityToSpawn instanceof EntityDragon
                    || entityToSpawn instanceof EntityWither) {
                SmartChunkUpdateScheduler.markDestructiveEntityActivity(
                        entityToSpawn.posX,
                        entityToSpawn.posY,
                        entityToSpawn.posZ
                );
            }
        }
    }
}

package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.SmartChunkUpdateScheduler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
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
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldSettings;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1. Élimine la boucle inutile de 1 000 itérations aléatoires dans WorldClient.doVoidFogParticles.
 * 2. Détecte en temps réel (0 ms) les vraies modifications serveur de blocs (S23/S22), les lits (BlockBed)
 *    ainsi que l'apparition de projectiles/explosifs (Fireball, TNT, FallingBlock, Dragon, Wither)
 *    pour dégeler instantanément les chunks concernés dans SmartChunkUpdateScheduler.
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

    @Inject(method = "invalidateRegionAndSetBlock", at = @At("HEAD"))
    private void purefps$markPlayerOrServerBlockEditUrgent(
            BlockPos pos,
            IBlockState state,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (PureFPSConfig.enabled && PureFPSConfig.smartChunkUpdates && pos != null) {
            WorldClient self = (WorldClient) (Object) this;
            Block newBlock = state != null ? state.getBlock() : null;
            boolean isBed = (newBlock instanceof BlockBed);
            if (self.isBlockLoaded(pos)) {
                IBlockState prevState = self.getBlockState(pos);
                if (prevState != null && prevState.equals(state)) {
                    return;
                }
                if (!isBed && prevState != null) {
                    isBed = (prevState.getBlock() instanceof BlockBed);
                }
            }
            SmartChunkUpdateScheduler.markDynamicBlockChange(pos.getX(), pos.getY(), pos.getZ(), isBed);
        }
    }

    @Inject(method = "addEntityToWorld", at = @At("HEAD"))
    private void purefps$markExplosiveOrDestructiveEntityUrgent(int entityID, Entity entityToSpawn, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.smartChunkUpdates && entityToSpawn != null) {
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

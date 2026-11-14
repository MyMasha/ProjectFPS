package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.SmartChunkUpdateScheduler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityItem;
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
 * 2. Détecte en temps réel (0 ms) les blocs posés/cassés par les joueurs, les lits (BlockBed) et les
 *    générateurs BedWars/SkyWars (apparition de minerais EntityItem et hologrammes EntityArmorStand)
 *    pour leur garantir une actualisation prioritaire immédiate dans SmartChunkUpdateScheduler.
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
            Block newBlock = state != null ? state.getBlock() : null;
            boolean isBed = (newBlock instanceof BlockBed);
            if (!isBed) {
                WorldClient self = (WorldClient) (Object) this;
                if (self.isBlockLoaded(pos)) {
                    Block prevBlock = self.getBlockState(pos).getBlock();
                    isBed = (prevBlock instanceof BlockBed);
                }
            }
            SmartChunkUpdateScheduler.markDynamicBlockChange(pos.getX(), pos.getY(), pos.getZ(), isBed);
        }
    }

    @Inject(method = "addEntityToWorld", at = @At("HEAD"))
    private void purefps$markResourceGeneratorUrgent(int entityID, Entity entityToSpawn, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.smartChunkUpdates && entityToSpawn != null) {
            if (entityToSpawn instanceof EntityItem || entityToSpawn instanceof EntityArmorStand) {
                SmartChunkUpdateScheduler.markGeneratorActivity(
                        entityToSpawn.posX,
                        entityToSpawn.posY,
                        entityToSpawn.posZ
                );
            }
        }
    }
}

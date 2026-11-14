package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.SmartChunkUpdateScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.play.server.S0CPacketSpawnPlayer;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;
import net.minecraft.network.play.server.S27PacketExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * 1. Empêche le NullPointerException de NetHandlerPlayClient.handleSpawnPlayer sur Hypixel
 *    lorsqu'un paquet S0CPacketSpawnPlayer (NPC / Watchdog éphémère) référence un UUID absent de playerInfoMap.
 * 2. Signale à SmartChunkUpdateScheduler l'arrivée initiale des chunks réseau (S21 / S26) et les
 *    explosions distantes (S27PacketExplosion : Fireball, TNT, Creeper, Lit) afin de dégeler
 *    instantanément les cratères même loin des joueurs.
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class MixinNetHandlerPlayClient {

    @Shadow
    private Minecraft gameController;

    @Shadow
    private WorldClient clientWorldController;

    @Shadow
    public abstract NetworkPlayerInfo getPlayerInfo(UUID uniqueId);

    @Inject(
            method = "handleSpawnPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/PacketThreadUtil;checkThreadAndEnqueue(Lnet/minecraft/network/Packet;Lnet/minecraft/network/INetHandler;Lnet/minecraft/util/IThreadListener;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void purefps$guardMissingPlayerInfoOnSpawn(S0CPacketSpawnPlayer packetIn, CallbackInfo ci) {
        if (packetIn == null || this.clientWorldController == null || this.getPlayerInfo(packetIn.getPlayer()) == null) {
            ci.cancel();
        }
    }

    @Inject(method = "handleChunkData", at = @At("RETURN"))
    private void purefps$onServerChunkLoaded(S21PacketChunkData packetIn, CallbackInfo ci) {
        if (packetIn == null || this.clientWorldController == null) {
            return;
        }
        if (this.gameController != null && !this.gameController.isCallingFromMinecraftThread()) {
            return;
        }
        if (packetIn.func_149274_i() && packetIn.getExtractedSize() == 0) {
            return;
        }
        SmartChunkUpdateScheduler.markChunkColumnLoaded(packetIn.getChunkX(), packetIn.getChunkZ());
    }

    @Inject(method = "handleMapChunkBulk", at = @At("RETURN"))
    private void purefps$onServerChunkBulkLoaded(S26PacketMapChunkBulk packetIn, CallbackInfo ci) {
        if (packetIn == null || this.clientWorldController == null) {
            return;
        }
        if (this.gameController != null && !this.gameController.isCallingFromMinecraftThread()) {
            return;
        }
        int count = packetIn.getChunkCount();
        for (int i = 0; i < count; i++) {
            SmartChunkUpdateScheduler.markChunkColumnLoaded(packetIn.getChunkX(i), packetIn.getChunkZ(i));
        }
    }

    @Inject(
            method = "handleExplosion",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/PacketThreadUtil;checkThreadAndEnqueue(Lnet/minecraft/network/Packet;Lnet/minecraft/network/INetHandler;Lnet/minecraft/util/IThreadListener;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void purefps$unfreezeChunksOnExplosion(S27PacketExplosion packetIn, CallbackInfo ci) {
        if (packetIn != null && PureFPSConfig.enabled && PureFPSConfig.smartChunkUpdates) {
            SmartChunkUpdateScheduler.markExplosion(
                    packetIn.getX(),
                    packetIn.getY(),
                    packetIn.getZ(),
                    packetIn.getStrength(),
                    packetIn.getAffectedBlockPositions()
            );
        }
    }
}

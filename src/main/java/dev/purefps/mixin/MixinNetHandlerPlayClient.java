package dev.purefps.mixin;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.play.server.S0CPacketSpawnPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Empêche le NullPointerException de NetHandlerPlayClient.handleSpawnPlayer (ligne 484) sur Hypixel
 * lorsqu'un paquet S0CPacketSpawnPlayer (NPC / Watchdog éphémère) référence un UUID absent de playerInfoMap.
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class MixinNetHandlerPlayClient {

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
}

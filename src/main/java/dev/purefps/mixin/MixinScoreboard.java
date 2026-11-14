package dev.purefps.mixin;

import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Élimine la tempête de NullPointerException / IllegalArgumentException FATAL de Log4j sur Hypixel
 * (24+ exceptions/seconde lors des paquets S3EPacketTeams BungeeCord en lobby / BedWars / SkyWars).
 */
@Mixin(Scoreboard.class)
public abstract class MixinScoreboard {

    @Shadow
    public abstract ScorePlayerTeam getTeam(String name);

    @Inject(method = "removeTeam", at = @At("HEAD"), cancellable = true)
    private void purefps$guardNullRemoveTeam(ScorePlayerTeam team, CallbackInfo ci) {
        if (team == null) {
            ci.cancel();
        }
    }

    @Inject(method = "removePlayerFromTeam", at = @At("HEAD"), cancellable = true)
    private void purefps$guardNullPlayerTeam(String username, ScorePlayerTeam playerTeam, CallbackInfo ci) {
        if (playerTeam == null || username == null) {
            ci.cancel();
        }
    }

    @Inject(method = "createTeam", at = @At("HEAD"), cancellable = true)
    private void purefps$reuseExistingTeamOnDuplicatePacket(String name, CallbackInfoReturnable<ScorePlayerTeam> cir) {
        if (name != null && name.length() <= 16) {
            ScorePlayerTeam existing = this.getTeam(name);
            if (existing != null) {
                cir.setReturnValue(existing);
            }
        }
    }
}

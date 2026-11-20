package dev.purefps.mixin;

import dev.purefps.core.FastScoreboardRenderer;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1. Élimine la tempête de NullPointerException / IllegalArgumentException FATAL de Log4j sur Hypixel
 *    lors des paquets S3EPacketTeams et S3BPacketScoreboardObjective.
 * 2. Incrémente le compteur de révision de FastScoreboardRenderer à chaque mutation réelle du Scoreboard
 *    pour un rendu HUD Scoreboard à zéro allocation sur >98 % des frames.
 */
@Mixin(Scoreboard.class)
public abstract class MixinScoreboard {

    @Shadow
    public abstract ScorePlayerTeam getTeam(String name);

    @Inject(method = "removeObjective", at = @At("HEAD"), cancellable = true)
    private void purefps$guardNullRemoveObjective(ScoreObjective objective, CallbackInfo ci) {
        if (objective == null) {
            ci.cancel();
        }
    }

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

    @Inject(method = "func_96536_a", at = @At("RETURN"))
    private void purefps$onScoreUpdated(Score scoreIn, CallbackInfo ci) {
        FastScoreboardRenderer.notifyScoreboardMutated();
    }

    @Inject(method = "func_96516_a", at = @At("RETURN"))
    private void purefps$onScoreRemoved(String scoreName, CallbackInfo ci) {
        FastScoreboardRenderer.notifyScoreboardMutated();
    }

    @Inject(method = "func_178820_a", at = @At("RETURN"))
    private void purefps$onScoreObjectiveRemovedForEntity(String scoreName, ScoreObjective objective, CallbackInfo ci) {
        FastScoreboardRenderer.notifyScoreboardMutated();
    }

    @Inject(method = "onObjectiveDisplayNameChanged", at = @At("RETURN"))
    private void purefps$onObjectiveDisplayNameChanged(ScoreObjective objective, CallbackInfo ci) {
        FastScoreboardRenderer.notifyScoreboardMutated();
    }

    @Inject(method = "onScoreObjectiveRemoved", at = @At("RETURN"))
    private void purefps$onObjectiveRemoved(ScoreObjective objective, CallbackInfo ci) {
        FastScoreboardRenderer.notifyScoreboardMutated();
    }

    @Inject(method = "setObjectiveInDisplaySlot", at = @At("RETURN"))
    private void purefps$onDisplaySlotChanged(int slot, ScoreObjective objective, CallbackInfo ci) {
        FastScoreboardRenderer.notifyScoreboardMutated();
    }

    @Inject(method = "sendTeamUpdate", at = @At("RETURN"))
    private void purefps$onTeamUpdated(ScorePlayerTeam team, CallbackInfo ci) {
        FastScoreboardRenderer.notifyScoreboardMutated();
    }

    @Inject(method = "func_96513_c", at = @At("RETURN"))
    private void purefps$onTeamRemoved(ScorePlayerTeam team, CallbackInfo ci) {
        FastScoreboardRenderer.notifyScoreboardMutated();
    }

    @Inject(method = "addPlayerToTeam", at = @At("RETURN"))
    private void purefps$onPlayerAddedToTeam(String player, String newTeam, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            FastScoreboardRenderer.notifyScoreboardMutated();
        }
    }

    @Inject(method = "removePlayerFromTeam", at = @At("RETURN"))
    private void purefps$onPlayerRemovedFromTeam(String username, ScorePlayerTeam playerTeam, CallbackInfo ci) {
        FastScoreboardRenderer.notifyScoreboardMutated();
    }
}

package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastTrig;
import net.minecraft.util.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * SÉCURITÉ ANTI-CHEAT 100 % (Hypixel Watchdog / NCP / Grim) :
 * - MathHelper.sin(float) et MathHelper.cos(float) restent 100 % Vanilla (SIN_TABLE[65536]) :
 *   aucun décalage de vecteur de mouvement (Entity.moveFlying / jump), zéro rollback et zéro allocation CallbackInfoReturnable.
 * - Seul atan2(double, double) (utilisé uniquement pour l'orientation visuelle) est remplacé en @Overwrite zéro-allocation.
 */
@Mixin(MathHelper.class)
public class MixinMathHelper {

    /**
     * @author PureFPS
     * @reason Remplacement polynomial rapide sans table de 256 Ko ni allocation CallbackInfoReturnable
     */
    @Overwrite
    public static double atan2(double y, double x) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastMath) {
            return FastTrig.fastAtan2(y, x);
        }
        return Math.atan2(y, x);
    }
}

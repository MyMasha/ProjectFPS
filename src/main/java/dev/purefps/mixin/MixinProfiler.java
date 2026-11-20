package dev.purefps.mixin;

import net.minecraft.profiler.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Court-circuite directement {@link Profiler#endStartSection(String)} lorsque le profiler F3+Shift est inactif,
 * évitant les deux dispatches redondants {@code endSection()} + {@code startSection(name)} sur chaque étape du rendu.
 * (Vanilla {@code startSection} et {@code endSection} vérifient déjà {@code profilingEnabled} dès leur première instruction.)
 */
@Mixin(Profiler.class)
public abstract class MixinProfiler {

    @Shadow
    public boolean profilingEnabled;

    @Inject(method = "endStartSection", at = @At("HEAD"), cancellable = true)
    private void purefps$skipInactiveEndStart(String name, CallbackInfo ci) {
        if (!this.profilingEnabled) {
            ci.cancel();
        }
    }
}

package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastVisGraphBFS;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.BitSet;
import java.util.Set;

/**
 * Remplace le flood-fill BFS sur LinkedList<Integer> de VisGraph par un ring-buffer int[4096] primitif.
 * Réduit drastiquement les micro-lags lors du chargement ou du minage rapide de chunks.
 */
@Mixin(VisGraph.class)
public class MixinVisGraph {

    @Shadow
    @Final
    private BitSet field_178612_d;

    @Inject(method = "func_178604_a", at = @At("HEAD"), cancellable = true)
    private void purefps$fastFloodFill(int startIndex, CallbackInfoReturnable<Set<EnumFacing>> cir) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastChunkVisGraph) {
            cir.setReturnValue(FastVisGraphBFS.floodFill(this.field_178612_d, startIndex));
        }
    }
}

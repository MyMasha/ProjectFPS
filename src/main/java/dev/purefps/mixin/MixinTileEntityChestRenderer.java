package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.model.ModelChest;
import net.minecraft.client.renderer.tileentity.TileEntityChestRenderer;
import net.minecraft.tileentity.TileEntityChest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LOD du loquet de coffre (TileEntityChestRenderer) :
 * Au-delà de 24 blocs, le petit loquet argenté 2x4 pixels (chestKnob) mesure moins de 0.5 pixel à l'écran :
 * le masquer économise 33 % des appels glCallList de chaque coffre distant dans les salles des coffres.
 */
@Mixin(TileEntityChestRenderer.class)
public class MixinTileEntityChestRenderer {

    @Shadow
    private ModelChest simpleChest;

    @Shadow
    private ModelChest largeChest;

    @Inject(method = "renderTileEntityAt", at = @At("HEAD"))
    private void purefps$chestKnobLodHead(
            TileEntityChest te,
            double x,
            double y,
            double z,
            float partialTicks,
            int destroyStage,
            CallbackInfo ci
    ) {
        boolean showKnob = !PureFPSConfig.enabled || (x * x + y * y + z * z <= 576.0D);
        if (this.simpleChest != null && this.simpleChest.chestKnob != null) {
            this.simpleChest.chestKnob.showModel = showKnob;
        }
        if (this.largeChest != null && this.largeChest.chestKnob != null) {
            this.largeChest.chestKnob.showModel = showKnob;
        }
    }

    @org.spongepowered.asm.mixin.injection.Redirect(
            method = "renderTileEntityAt(Lnet/minecraft/tileentity/TileEntityChest;DDDFI)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/ModelChest;renderAll()V")
    )
    private void purefps$batchChestRenderAll(ModelChest model) {
        if (PureFPSConfig.enabled && dev.purefps.core.EntityModelBatcher.isSupported()) {
            dev.purefps.core.EntityModelBatcher.beginBatch();
            try {
                model.renderAll();
            } finally {
                dev.purefps.core.EntityModelBatcher.endBatch();
            }
            return;
        }
        model.renderAll();
    }

    @Inject(method = "renderTileEntityAt", at = @At("RETURN"))
    private void purefps$chestKnobLodReturn(
            TileEntityChest te,
            double x,
            double y,
            double z,
            float partialTicks,
            int destroyStage,
            CallbackInfo ci
    ) {
        if (this.simpleChest != null && this.simpleChest.chestKnob != null) {
            this.simpleChest.chestKnob.showModel = true;
        }
        if (this.largeChest != null && this.largeChest.chestKnob != null) {
            this.largeChest.chestKnob.showModel = true;
        }
    }
}

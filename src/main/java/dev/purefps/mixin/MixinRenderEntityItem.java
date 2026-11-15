package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.RenderItemContext;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LOD zéro-allocation pour les objets au sol (EntityItem) :
 * - Au-delà de 12 blocs : 1 seul modèle 3D par pile au lieu de 5 (-80 % de draw calls).
 * - Au-delà de 16 blocs : coupe l'oscillation, la rotation OpenGL et le reflet d'enchantement 2-passes.
 */
@Mixin(RenderEntityItem.class)
public class MixinRenderEntityItem {

    @Unique
    private double purefps$currentDistSq = 0.0D;

    @Unique
    private boolean purefps$pushedUhcScale = false;

    @Inject(
            method = "doRender(Lnet/minecraft/entity/item/EntityItem;DDDFF)V",
            at = @At("HEAD")
    )
    private void purefps$captureItemDistance(EntityItem entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo ci) {
        double distSq = x * x + y * y + z * z;
        this.purefps$currentDistSq = distSq;
        RenderItemContext.skipGlintForCurrentItem = distSq >= 256.0D;
        this.purefps$pushedUhcScale = false;
        if (PureFPSConfig.enabled && dev.purefps.module.MashFlags.uhcGoldScale && entity != null) {
            ItemStack st = entity.getEntityItem();
            if (st != null && st.getItem() != null) {
                net.minecraft.item.Item it = st.getItem();
                if (it == net.minecraft.init.Items.golden_apple
                        || it == net.minecraft.init.Items.gold_ingot
                        || it == net.minecraft.init.Items.gold_nugget) {
                    float sc = dev.purefps.module.MashFlags.uhcGoldScaleFactor;
                    if (Math.abs(sc - 1.0F) > 0.01F) {
                        GlStateManager.pushMatrix();
                        GlStateManager.translate(x, y, z);
                        GlStateManager.scale(sc, sc, sc);
                        GlStateManager.translate(-x, -y, -z);
                        this.purefps$pushedUhcScale = true;
                    }
                }
            }
        }
    }

    @Inject(
            method = "doRender(Lnet/minecraft/entity/item/EntityItem;DDDFF)V",
            at = @At("RETURN")
    )
    private void purefps$resetItemDistanceContext(EntityItem entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo ci) {
        if (this.purefps$pushedUhcScale) {
            GlStateManager.popMatrix();
            this.purefps$pushedUhcScale = false;
        }
        RenderItemContext.skipGlintForCurrentItem = false;
    }

    /**
     * @author PureFPS
     * @reason LOD du nombre de modèles par pile d'items sans CallbackInfoReturnable ni boxing Integer
     */
    @Overwrite
    protected int func_177078_a(ItemStack stack) {
        if (stack == null) {
            return 0;
        }
        if (PureFPSConfig.enabled && PureFPSConfig.fastDroppedItems) {
            if (this.purefps$currentDistSq >= 144.0D) {
                return 1;
            }
            return stack.stackSize > 1 ? 2 : 1;
        }
        int count = 1;
        if (stack.stackSize > 48) {
            count = 5;
        } else if (stack.stackSize > 32) {
            count = 4;
        } else if (stack.stackSize > 16) {
            count = 3;
        } else if (stack.stackSize > 1) {
            count = 2;
        }
        return count;
    }

    /**
     * @author PureFPS
     * @reason Désactive le bobbing distant sans CallbackInfoReturnable (méthode Forge non-obfusquée)
     */
    @Overwrite(remap = false)
    public boolean shouldBob() {
        return !(PureFPSConfig.enabled && PureFPSConfig.fastDroppedItems && this.purefps$currentDistSq >= 256.0D);
    }

    @Redirect(
            method = "func_177077_a",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;rotate(FFFF)V")
    )
    private void purefps$skipDistantRotation(float angle, float x, float y, float z) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.fastDroppedItems || this.purefps$currentDistSq < 256.0D) {
            float spinMult = (PureFPSConfig.enabled && dev.purefps.module.MashFlags.itemSpinCustom)
                    ? dev.purefps.module.MashFlags.itemSpinSpeed
                    : 1.0F;
            GlStateManager.rotate(angle * spinMult, x, y, z);
        }
    }
}

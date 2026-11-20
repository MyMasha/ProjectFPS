package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.OcclusionCuller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.ArmorStandRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Court-circuite 100 % des ArmorStands marqueurs vides (sans nom ni équipement)
 * et 95 % du pipeline Modèle/Layers pour les hologrammes de texte Hypixel.
 */
@Mixin(ArmorStandRenderer.class)
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class MixinArmorStandRenderer extends RendererLivingEntity {

    protected MixinArmorStandRenderer(RenderManager renderManagerIn, ModelBase modelBaseIn, float shadowSizeIn) {
        super(renderManagerIn, modelBaseIn, shadowSizeIn);
    }

    @Override
    public void doRender(EntityLivingBase entity, double x, double y, double z, float entityYaw, float partialTicks) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastArmorStands
                && entity.isInvisible() && !this.renderOutlines
                && entity instanceof EntityArmorStand
                && OcclusionCuller.hasNoEquipment((EntityArmorStand) entity)) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || entity.isInvisibleToPlayer(mc.thePlayer)) {
                // Marqueur invisible sans nom personnalisé : 0 pixel à dessiner -> retour O(1) immédiat !
                if (!entity.hasCustomName()) {
                    return;
                }
                if (!PureFPSConfig.nametagDistanceCulling
                        || (x * x + y * y + z * z) <= (double) PureFPSConfig.maxNametagDistance * (double) PureFPSConfig.maxNametagDistance) {
                    this.renderName(entity, x, y, z);
                }
                return;
            }
        }
        super.doRender(entity, x, y, z, entityYaw, partialTicks);
    }
}

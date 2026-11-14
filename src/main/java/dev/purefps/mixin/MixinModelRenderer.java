package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * Optimisation universelle de ModelRenderer.render (joueurs, armures, coffres, panneaux, crânes, mobs) :
 * 1. Vanilla appelle GlStateManager.translate(this.offsetX, this.offsetY, this.offsetZ) ET son inverse
 *    à CHAQUE membre (soit 48 appels JNI GL11.glTranslatef(0, 0, 0) inutiles par joueur équipé et par frame !).
 * 2. Court-circuite immédiatement les ModelRenderer vides (cubeList vide et sans enfants) sans compiler
 *    de Display List vide ni manipuler la pile matricielle OpenGL.
 */
@Mixin(ModelRenderer.class)
public abstract class MixinModelRenderer {

    @Shadow
    public float rotationPointX;
    @Shadow
    public float rotationPointY;
    @Shadow
    public float rotationPointZ;

    @Shadow
    public float rotateAngleX;
    @Shadow
    public float rotateAngleY;
    @Shadow
    public float rotateAngleZ;

    @Shadow
    private boolean compiled;

    @Shadow
    private int displayList;

    @Shadow
    public boolean showModel;

    @Shadow
    public boolean isHidden;

    @Shadow
    public List<ModelBox> cubeList;

    @Shadow
    public List<ModelRenderer> childModels;

    @Shadow
    public float offsetX;
    @Shadow
    public float offsetY;
    @Shadow
    public float offsetZ;

    @Shadow
    private void compileDisplayList(float scale) {
    }

    /**
     * @author PureClient
     * @reason Supprime les 2 appels JNI glTranslatef(0,0,0) par membre et ignore les boîtes vides
     */
    @Overwrite
    public void render(float scale) {
        if (this.isHidden || !this.showModel) {
            return;
        }

        boolean hasCubes = this.cubeList != null && !this.cubeList.isEmpty();
        boolean hasChildren = this.childModels != null && !this.childModels.isEmpty();
        if (PureFPSConfig.enabled && !hasCubes && !hasChildren) {
            return;
        }

        if (!this.compiled) {
            this.compileDisplayList(scale);
        }

        boolean hasOffset = !PureFPSConfig.enabled
                || this.offsetX != 0.0F || this.offsetY != 0.0F || this.offsetZ != 0.0F;
        if (hasOffset) {
            GlStateManager.translate(this.offsetX, this.offsetY, this.offsetZ);
        }

        if (this.rotateAngleX == 0.0F && this.rotateAngleY == 0.0F && this.rotateAngleZ == 0.0F) {
            if (this.rotationPointX == 0.0F && this.rotationPointY == 0.0F && this.rotationPointZ == 0.0F) {
                if (hasCubes || !PureFPSConfig.enabled) {
                    GlStateManager.callList(this.displayList);
                }
                if (hasChildren) {
                    for (int k = 0, sz = this.childModels.size(); k < sz; ++k) {
                        this.childModels.get(k).render(scale);
                    }
                }
            } else {
                float tx = this.rotationPointX * scale;
                float ty = this.rotationPointY * scale;
                float tz = this.rotationPointZ * scale;
                GlStateManager.translate(tx, ty, tz);
                if (hasCubes || !PureFPSConfig.enabled) {
                    GlStateManager.callList(this.displayList);
                }
                if (hasChildren) {
                    for (int j = 0, sz = this.childModels.size(); j < sz; ++j) {
                        this.childModels.get(j).render(scale);
                    }
                }
                GlStateManager.translate(-tx, -ty, -tz);
            }
        } else {
            GlStateManager.pushMatrix();
            if (this.rotationPointX != 0.0F || this.rotationPointY != 0.0F || this.rotationPointZ != 0.0F) {
                GlStateManager.translate(this.rotationPointX * scale, this.rotationPointY * scale, this.rotationPointZ * scale);
            }
            if (this.rotateAngleZ != 0.0F) {
                GlStateManager.rotate(this.rotateAngleZ * 57.295776F, 0.0F, 0.0F, 1.0F);
            }
            if (this.rotateAngleY != 0.0F) {
                GlStateManager.rotate(this.rotateAngleY * 57.295776F, 0.0F, 1.0F, 0.0F);
            }
            if (this.rotateAngleX != 0.0F) {
                GlStateManager.rotate(this.rotateAngleX * 57.295776F, 1.0F, 0.0F, 0.0F);
            }
            if (hasCubes || !PureFPSConfig.enabled) {
                GlStateManager.callList(this.displayList);
            }
            if (hasChildren) {
                for (int i = 0, sz = this.childModels.size(); i < sz; ++i) {
                    this.childModels.get(i).render(scale);
                }
            }
            GlStateManager.popMatrix();
        }

        if (hasOffset) {
            GlStateManager.translate(-this.offsetX, -this.offsetY, -this.offsetZ);
        }
    }
}

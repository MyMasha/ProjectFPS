package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.EntityModelBatcher;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * Optimisation universelle de ModelRenderer.render (joueurs, armures, coffres, panneaux, crânes, mobs) :
 * 1. Quand EntityModelBatcher est actif : applique toutes les transformations sur la pile matricielle CPU
 *    et fusionne tous les membres dans un seul glDrawArrays(GL_TRIANGLES) (0 glCallList, 0 GL_QUADS).
 * 2. Hors batch : rend les ModelBox standards sur le Ring VBO GL_TRIANGLES + VAO GL30 (élimine les
 *    Display Lists GL_QUADS sous OpenGL et Vulkan/Zink).
 * 3. Repli sécurisé avec correction `if (hasCubes && !this.compiled)` pour les ModelBox moddés.
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
     * @reason Batching VBO GL_TRIANGLES + VAO GL30, suppression des Display Lists GL_QUADS et des glTranslatef(0,0,0)
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

        // Chemin rapide 1 : batch actif (EntityModelBatcher fusionne tous les membres en 1 glDrawArrays GL_TRIANGLES)
        if (EntityModelBatcher.isBatching()) {
            if (!hasCubes || EntityModelBatcher.areAllStandardModelBoxes(this.cubeList)) {
                EntityModelBatcher.onPushMatrix();
                if (this.offsetX != 0.0F || this.offsetY != 0.0F || this.offsetZ != 0.0F) {
                    EntityModelBatcher.onTranslate(this.offsetX, this.offsetY, this.offsetZ);
                }
                if (this.rotationPointX != 0.0F || this.rotationPointY != 0.0F || this.rotationPointZ != 0.0F) {
                    EntityModelBatcher.onTranslate(
                            this.rotationPointX * scale,
                            this.rotationPointY * scale,
                            this.rotationPointZ * scale
                    );
                }
                if (this.rotateAngleZ != 0.0F) {
                    EntityModelBatcher.rotateRadiansZ(this.rotateAngleZ);
                }
                if (this.rotateAngleY != 0.0F) {
                    EntityModelBatcher.rotateRadiansY(this.rotateAngleY);
                }
                if (this.rotateAngleX != 0.0F) {
                    EntityModelBatcher.rotateRadiansX(this.rotateAngleX);
                }
                if (hasCubes) {
                    EntityModelBatcher.emitCubesTransformed(this.cubeList, scale);
                }
                if (hasChildren) {
                    for (int i = 0, sz = this.childModels.size(); i < sz; ++i) {
                        this.childModels.get(i).render(scale);
                    }
                }
                EntityModelBatcher.onPopMatrix();
                return;
            }
            // Si un cube custom moddé apparaît pendant un batch, vide le batch proprement et rend en Vanilla
            EntityModelBatcher.flush();
        }

        boolean useImmediateVbo = PureFPSConfig.enabled
                && hasCubes
                && EntityModelBatcher.isSupported()
                && EntityModelBatcher.areAllStandardModelBoxes(this.cubeList);

        if (!useImmediateVbo && (hasCubes || !PureFPSConfig.enabled) && !this.compiled) {
            this.compileDisplayList(scale);
        }

        boolean hasOffset = !PureFPSConfig.enabled
                || this.offsetX != 0.0F || this.offsetY != 0.0F || this.offsetZ != 0.0F;
        if (hasOffset) {
            GlStateManager.translate(this.offsetX, this.offsetY, this.offsetZ);
        }

        if (this.rotateAngleX == 0.0F && this.rotateAngleY == 0.0F && this.rotateAngleZ == 0.0F) {
            if (this.rotationPointX == 0.0F && this.rotationPointY == 0.0F && this.rotationPointZ == 0.0F) {
                if (useImmediateVbo) {
                    EntityModelBatcher.drawCubesImmediate(this.cubeList, scale);
                } else if (hasCubes || !PureFPSConfig.enabled) {
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
                if (useImmediateVbo) {
                    EntityModelBatcher.drawCubesImmediate(this.cubeList, scale);
                } else if (hasCubes || !PureFPSConfig.enabled) {
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
            if (useImmediateVbo) {
                EntityModelBatcher.drawCubesImmediate(this.cubeList, scale);
            } else if (hasCubes || !PureFPSConfig.enabled) {
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

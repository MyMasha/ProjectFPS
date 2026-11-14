package dev.purefps.mixin;

import com.mojang.authlib.GameProfile;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastSkullCache;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelSkeletonHead;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySkullRenderer;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Élimine le goulot massif de SkinManager.loadSkinFromCache + SkinManager.loadSkin (Guava LoadingCache +
 * FilenameUtils.getBaseName + StringBuilder + new ResourceLocation + TextureManager.getTexture) exécuté
 * à chaque frame sur chaque crâne custom sur Hypixel (lobbies, SkyBlock, BedWars, menus).
 * Utilise exclusivement if/else sur EnumFacing pour ne générer aucune classe interne synthétique SwitchMap.
 */
@Mixin(TileEntitySkullRenderer.class)
public abstract class MixinTileEntitySkullRenderer extends TileEntitySpecialRenderer<TileEntitySkull> {

    @Shadow
    @Final
    private static ResourceLocation SKELETON_TEXTURES;

    @Shadow
    @Final
    private static ResourceLocation WITHER_SKELETON_TEXTURES;

    @Shadow
    @Final
    private static ResourceLocation ZOMBIE_TEXTURES;

    @Shadow
    @Final
    private static ResourceLocation CREEPER_TEXTURES;

    @Shadow
    @Final
    private ModelSkeletonHead skeletonHead;

    @Shadow
    @Final
    private ModelSkeletonHead humanoidHead;

    /**
     * @author PureFPS
     * @reason Résolution O(1) zéro-allocation des textures de crânes custom via FastSkullCache
     */
    @Overwrite
    public void renderSkull(float x, float y, float z, EnumFacing facing, float rotationIn, int skullType, GameProfile profile, int destroyStage) {
        ModelBase modelbase = this.skeletonHead;

        if (destroyStage >= 0) {
            this.bindTexture(DESTROY_STAGES[destroyStage]);
            GlStateManager.matrixMode(5890);
            GlStateManager.pushMatrix();
            GlStateManager.scale(4.0F, 2.0F, 1.0F);
            GlStateManager.translate(0.0625F, 0.0625F, 0.0625F);
            GlStateManager.matrixMode(5888);
        } else {
            switch (skullType) {
                case 0:
                default:
                    this.bindTexture(SKELETON_TEXTURES);
                    break;
                case 1:
                    this.bindTexture(WITHER_SKELETON_TEXTURES);
                    break;
                case 2:
                    this.bindTexture(ZOMBIE_TEXTURES);
                    modelbase = this.humanoidHead;
                    break;
                case 3:
                    modelbase = this.humanoidHead;
                    ResourceLocation skin = (PureFPSConfig.enabled && PureFPSConfig.fastSkullRendering)
                            ? FastSkullCache.resolveSkullTexture(profile)
                            : FastSkullCache.resolveVanillaSkullTexture(profile);
                    this.bindTexture(skin != null ? skin : SKELETON_TEXTURES);
                    break;
                case 4:
                    this.bindTexture(CREEPER_TEXTURES);
                    break;
            }
        }

        GlStateManager.pushMatrix();
        GlStateManager.disableCull();

        if (facing != EnumFacing.UP && facing != null) {
            if (facing == EnumFacing.NORTH) {
                GlStateManager.translate(x + 0.5F, y + 0.25F, z + 0.74F);
            } else if (facing == EnumFacing.SOUTH) {
                GlStateManager.translate(x + 0.5F, y + 0.25F, z + 0.26F);
                rotationIn = 180.0F;
            } else if (facing == EnumFacing.WEST) {
                GlStateManager.translate(x + 0.74F, y + 0.25F, z + 0.5F);
                rotationIn = 270.0F;
            } else {
                GlStateManager.translate(x + 0.26F, y + 0.25F, z + 0.5F);
                rotationIn = 90.0F;
            }
        } else {
            GlStateManager.translate(x + 0.5F, y, z + 0.5F);
        }

        float scale = 0.0625F;
        GlStateManager.enableRescaleNormal();
        GlStateManager.scale(-1.0F, -1.0F, 1.0F);
        GlStateManager.enableAlpha();
        modelbase.render(null, 0.0F, 0.0F, 0.0F, rotationIn, 0.0F, scale);
        GlStateManager.popMatrix();

        if (destroyStage >= 0) {
            GlStateManager.matrixMode(5890);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(5888);
        }
    }
}

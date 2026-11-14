package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastItemFrameRenderer;
import dev.purefps.core.RenderItemContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureCompass;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.RenderItemFrame;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemMap;
import net.minecraft.item.ItemSkull;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.storage.MapData;
import net.minecraftforge.client.event.RenderItemInFrameEvent;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Refonte complète haute performance des Item Frames (RenderItemFrame) :
 * 1. Compile le cadre en bois (26 draw calls en Vanilla !) en UNE SEULE Display List GPU (1 glCallList).
 * 2. Supprime l'allocation inutile `new EntityItem(world, 0, 0, 0, stack)` (UUID.randomUUID + DataWatcher + Lock)
 *    exécutée par Vanilla à CHAQUE frame pour chaque Item Frame affiché !
 * 3. Supprime le stall sévère `GlStateManager.pushAttrib()` / `popAttrib()` (GL11.glPushAttrib(8256)) par cadre.
 * 4. Culling intelligent de distance (coupe les cadres vides dès 28m et les cadres d'items au-delà de maxItemFrameDistance,
 *    tout en préservant les murs de cartes MapData jusqu'à 64m+).
 * 5. Coupe la mise à jour glTexSubImage2D des boussoles et le reflet enchanté des cadres distants (> 16m).
 */
@Mixin(RenderItemFrame.class)
public abstract class MixinRenderItemFrame extends Render<EntityItemFrame> {

    @Shadow
    @Final
    private static ResourceLocation mapBackgroundTextures;

    @Shadow
    @Final
    private Minecraft mc;

    @Shadow
    @Final
    private ModelResourceLocation itemFrameModel;

    @Shadow
    @Final
    private ModelResourceLocation mapModel;

    @Shadow
    private RenderItem itemRenderer;

    @Shadow
    private void renderItem(EntityItemFrame itemFrame) {
    }

    protected MixinRenderItemFrame(RenderManager renderManager) {
        super(renderManager);
    }

    /**
     * @author PureClient
     * @reason Optimisation extrême des Item Frames (Display List GPU + zéro new EntityItem + zéro glPushAttrib + LOD distance)
     */
    @Overwrite
    public void doRender(EntityItemFrame entity, double x, double y, double z, float entityYaw, float partialTicks) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.fastItemFrames) {
            this.purefps$vanillaDoRender(entity, x, y, z);
            return;
        }

        double distSq = x * x + y * y + z * z;
        ItemStack displayed = entity.getDisplayedItem();
        boolean isFilledMap = displayed != null && displayed.getItem() == Items.filled_map;
        boolean isAnyMap = isFilledMap || (displayed != null && displayed.getItem() instanceof ItemMap);

        int maxDist = PureFPSConfig.maxItemFrameDistance;
        double maxDistSq;
        if (displayed == null) {
            int emptyCap = Math.min(maxDist, 28);
            maxDistSq = (double) (emptyCap * emptyCap);
        } else if (isAnyMap) {
            int mapCap = Math.max(maxDist, 64);
            maxDistSq = (double) (mapCap * mapCap);
        } else {
            maxDistSq = (double) (maxDist * maxDist);
        }

        if (distSq > maxDistSq) {
            PureFPSConfig.frameEntitiesAccumulator++;
            return;
        }

        GlStateManager.pushMatrix();
        BlockPos blockpos = entity.getHangingPosition();
        double d0 = (double) blockpos.getX() - entity.posX + x;
        double d1 = (double) blockpos.getY() - entity.posY + y;
        double d2 = (double) blockpos.getZ() - entity.posZ + z;
        GlStateManager.translate(d0 + 0.5D, d1 + 0.5D, d2 + 0.5D);
        GlStateManager.rotate(180.0F - entity.rotationYaw, 0.0F, 1.0F, 0.0F);
        this.renderManager.renderEngine.bindTexture(TextureMap.locationBlocksTexture);

        BlockRendererDispatcher brd = this.mc.getBlockRendererDispatcher();
        ModelManager modelManager = brd.getBlockModelShapes().getModelManager();
        IBakedModel frameBakedModel = isFilledMap
                ? modelManager.getModel(this.mapModel)
                : modelManager.getModel(this.itemFrameModel);

        GlStateManager.pushMatrix();
        GlStateManager.translate(-0.5F, -0.5F, -0.5F);
        FastItemFrameRenderer.renderFrameBorderCached(brd.getBlockModelRenderer(), frameBakedModel, isFilledMap);
        GlStateManager.popMatrix();

        if (displayed != null) {
            GlStateManager.translate(0.0F, 0.0F, 0.4375F);
            this.purefps$renderDisplayedItemFast(entity, displayed, distSq);
        }

        GlStateManager.popMatrix();

        if (this.renderManager.pointedEntity == entity && displayed != null && displayed.hasDisplayName()) {
            this.renderName(
                    entity,
                    x + (double) ((float) entity.facingDirection.getFrontOffsetX() * 0.3F),
                    y - 0.25D,
                    z + (double) ((float) entity.facingDirection.getFrontOffsetZ() * 0.3F)
            );
        }
    }

    @Unique
    private void purefps$renderDisplayedItemFast(EntityItemFrame itemFrame, ItemStack stack, double distSq) {
        Item item = stack.getItem();
        if (item == null) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.disableLighting();

        int rot = itemFrame.getRotation();
        boolean isMap = item instanceof ItemMap;
        if (isMap) {
            rot = (rot % 4) * 2;
        }
        if (rot != 0) {
            GlStateManager.rotate((float) rot * 45.0F, 0.0F, 0.0F, 1.0F);
        }

        RenderItemInFrameEvent event = new RenderItemInFrameEvent(itemFrame, (RenderItemFrame) (Object) this);
        if (!MinecraftForge.EVENT_BUS.post(event)) {
            if (isMap) {
                this.renderManager.renderEngine.bindTexture(mapBackgroundTextures);
                GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
                float f = 0.0078125F;
                GlStateManager.scale(f, f, f);
                GlStateManager.translate(-64.0F, -64.0F, -1.0F);
                MapData mapdata = Items.filled_map.getMapData(stack, itemFrame.worldObj);
                if (mapdata != null) {
                    this.mc.entityRenderer.getMapItemRenderer().renderMap(mapdata, true);
                }
            } else if (distSq <= 1296.0D) {
                // Rend l'objet 3D dans le cadre jusqu'à 36 blocs (au-delà, l'icône fait < 2px sur le cadre)
                TextureAtlasSprite compassSprite = null;
                if (item == Items.compass && distSq <= 256.0D) {
                    compassSprite = this.mc.getTextureMapBlocks().getAtlasSprite(TextureCompass.locationSprite);
                    this.mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
                    if (compassSprite instanceof TextureCompass) {
                        TextureCompass tc = (TextureCompass) compassSprite;
                        double prevAngle = tc.currentAngle;
                        double prevDelta = tc.angleDelta;
                        tc.currentAngle = 0.0D;
                        tc.angleDelta = 0.0D;
                        tc.updateCompass(
                                itemFrame.worldObj,
                                itemFrame.posX,
                                itemFrame.posZ,
                                MathHelper.wrapAngleTo180_float(180 + itemFrame.facingDirection.getHorizontalIndex() * 90),
                                false,
                                true
                        );
                        tc.currentAngle = prevAngle;
                        tc.angleDelta = prevDelta;
                    } else {
                        compassSprite = null;
                    }
                }

                GlStateManager.scale(0.5F, 0.5F, 0.5F);
                if (!this.itemRenderer.shouldRenderItemIn3D(stack) || item instanceof ItemSkull) {
                    GlStateManager.rotate(180.0F, 0.0F, 1.0F, 0.0F);
                }

                boolean prevSkipGlint = RenderItemContext.skipGlintForCurrentItem;
                if (distSq > 256.0D && PureFPSConfig.limitDistantGlint) {
                    RenderItemContext.skipGlintForCurrentItem = true;
                }
                RenderHelper.enableStandardItemLighting();
                try {
                    this.itemRenderer.renderItem(stack, ItemCameraTransforms.TransformType.FIXED);
                } finally {
                    RenderHelper.disableStandardItemLighting();
                    RenderItemContext.skipGlintForCurrentItem = prevSkipGlint;
                }

                if (compassSprite != null && compassSprite.getFrameCount() > 0) {
                    compassSprite.updateAnimation();
                }
            }
        }

        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    @Unique
    private void purefps$vanillaDoRender(EntityItemFrame entity, double x, double y, double z) {
        GlStateManager.pushMatrix();
        BlockPos blockpos = entity.getHangingPosition();
        double d0 = (double) blockpos.getX() - entity.posX + x;
        double d1 = (double) blockpos.getY() - entity.posY + y;
        double d2 = (double) blockpos.getZ() - entity.posZ + z;
        GlStateManager.translate(d0 + 0.5D, d1 + 0.5D, d2 + 0.5D);
        GlStateManager.rotate(180.0F - entity.rotationYaw, 0.0F, 1.0F, 0.0F);
        this.renderManager.renderEngine.bindTexture(TextureMap.locationBlocksTexture);
        BlockRendererDispatcher brd = this.mc.getBlockRendererDispatcher();
        ModelManager modelManager = brd.getBlockModelShapes().getModelManager();
        IBakedModel model = entity.getDisplayedItem() != null && entity.getDisplayedItem().getItem() == Items.filled_map
                ? modelManager.getModel(this.mapModel)
                : modelManager.getModel(this.itemFrameModel);
        GlStateManager.pushMatrix();
        GlStateManager.translate(-0.5F, -0.5F, -0.5F);
        brd.getBlockModelRenderer().renderModelBrightnessColor(model, 1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
        GlStateManager.translate(0.0F, 0.0F, 0.4375F);
        this.renderItem(entity);
        GlStateManager.popMatrix();
        this.renderName(
                entity,
                x + (double) ((float) entity.facingDirection.getFrontOffsetX() * 0.3F),
                y - 0.25D,
                z + (double) ((float) entity.facingDirection.getFrontOffsetZ() * 0.3F)
        );
    }
}

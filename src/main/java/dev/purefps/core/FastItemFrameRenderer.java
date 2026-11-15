package dev.purefps.core;

import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3i;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Accélérateur dédié aux Cadres d'Items (EntityItemFrame / RenderItemFrame) et aux modèles BlockModelRenderer :
 * 1. Dans Vanilla 1.8.9, BlockModelRenderer.renderModelBrightnessColorQuads exécute un couple
 *    worldrenderer.begin() + tessellator.draw() PAR QUAD INDIVIDUEL, soit 22 à 26 draw calls OpenGL
 *    juste pour le cadre en bois d'UN SEUL Item Frame !
 * 2. Ici, tous les quads d'un modèle sont fusionnés en UN SEUL Tessellator.draw(), et les deux modèles
 *    de cadres (normal + carte) sont compilés en Display Lists GPU statiques (1 seul glCallList par cadre).
 */
public final class FastItemFrameRenderer {

    private static IBakedModel lastNormalModel;
    private static int normalFrameDisplayList = -1;

    private static IBakedModel lastMapModel;
    private static int mapFrameDisplayList = -1;

    private FastItemFrameRenderer() {
    }

    public static void renderFrameBorderCached(BlockModelRenderer bmr, IBakedModel model, boolean isMapFrame) {
        if (model == null) {
            return;
        }
        if (isMapFrame) {
            if (model == lastMapModel && mapFrameDisplayList >= 0) {
                GlStateManager.callList(mapFrameDisplayList);
                GlStateManager.resetColor();
                return;
            }
            if (mapFrameDisplayList >= 0) {
                GLAllocation.deleteDisplayLists(mapFrameDisplayList);
            }
            mapFrameDisplayList = GLAllocation.generateDisplayLists(1);
            lastMapModel = model;
            net.minecraft.client.renderer.OpenGlHelper.glBindBuffer(net.minecraft.client.renderer.OpenGlHelper.GL_ARRAY_BUFFER, 0);
            GL11.glNewList(mapFrameDisplayList, GL11.GL_COMPILE_AND_EXECUTE);
            renderModelBrightnessColorSingleDraw(model, 1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glEndList();
            GlStateManager.resetColor();
        } else {
            if (model == lastNormalModel && normalFrameDisplayList >= 0) {
                GlStateManager.callList(normalFrameDisplayList);
                GlStateManager.resetColor();
                return;
            }
            if (normalFrameDisplayList >= 0) {
                GLAllocation.deleteDisplayLists(normalFrameDisplayList);
            }
            normalFrameDisplayList = GLAllocation.generateDisplayLists(1);
            lastNormalModel = model;
            net.minecraft.client.renderer.OpenGlHelper.glBindBuffer(net.minecraft.client.renderer.OpenGlHelper.GL_ARRAY_BUFFER, 0);
            GL11.glNewList(normalFrameDisplayList, GL11.GL_COMPILE_AND_EXECUTE);
            renderModelBrightnessColorSingleDraw(model, 1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glEndList();
            GlStateManager.resetColor();
        }
    }

    /**
     * Remplace les 26 appels begin()+draw() de Vanilla BlockModelRenderer.renderModelBrightnessColor
     * par UN SEUL begin() + draw() regroupant toutes les faces !
     */
    public static void renderModelBrightnessColorSingleDraw(
            IBakedModel bakedModel,
            float brightness,
            float red,
            float green,
            float blue
    ) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.ITEM);

        float tintedR = red * brightness;
        float tintedG = green * brightness;
        float tintedB = blue * brightness;

        EnumFacing[] facings = EnumCaches.FACINGS;
        for (int i = 0; i < facings.length; i++) {
            appendBrightnessQuads(wr, bakedModel.getFaceQuads(facings[i]), brightness, tintedR, tintedG, tintedB);
        }
        appendBrightnessQuads(wr, bakedModel.getGeneralQuads(), brightness, tintedR, tintedG, tintedB);

        tess.draw();
    }

    private static void appendBrightnessQuads(
            WorldRenderer wr,
            List<BakedQuad> quads,
            float brightness,
            float tintedR,
            float tintedG,
            float tintedB
    ) {
        if (quads == null || quads.isEmpty()) {
            return;
        }
        for (int i = 0, sz = quads.size(); i < sz; i++) {
            BakedQuad quad = quads.get(i);
            wr.addVertexData(quad.getVertexData());
            if (quad.hasTintIndex()) {
                wr.putColorRGB_F4(tintedR, tintedG, tintedB);
            } else {
                wr.putColorRGB_F4(brightness, brightness, brightness);
            }
            EnumFacing face = quad.getFace();
            if (face != null) {
                Vec3i norm = face.getDirectionVec();
                wr.putNormal((float) norm.getX(), (float) norm.getY(), (float) norm.getZ());
            }
        }
    }

    public static void clear() {
        if (normalFrameDisplayList >= 0) {
            try {
                GLAllocation.deleteDisplayLists(normalFrameDisplayList);
            } catch (Throwable ignored) {
            }
            normalFrameDisplayList = -1;
        }
        if (mapFrameDisplayList >= 0) {
            try {
                GLAllocation.deleteDisplayLists(mapFrameDisplayList);
            } catch (Throwable ignored) {
            }
            mapFrameDisplayList = -1;
        }
        lastNormalModel = null;
        lastMapModel = null;
    }
}

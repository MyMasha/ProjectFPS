package dev.purefps.core;

import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.model.ISmartBlockModel;
import net.minecraftforge.client.model.ISmartItemModel;
import net.minecraftforge.client.model.pipeline.LightUtil;
import org.lwjgl.opengl.GL11;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * Cache GPU de Display Lists compilées pour les IBakedModel statiques non teintés (PureClient 3.5) :
 * - Dans Forge 1.8.9, RenderItem.renderModel exécute LightUtil.renderQuadColor sur chaque BakedQuad
 *   (64 à 256 quads par épée/outil PvP 32x/64x, multiplié par 3 passes si l'item est enchanté !).
 * - Compiler ces modèles statiques en Display List VRAM transforme ~200 transformations CPU LightUtil
 *   + transfert Tessellator en UN SEUL GlStateManager.callList(listId).
 * - Purgé proprement à chaque rechargement de pack de ressources (RenderItem.onResourceManagerReload).
 */
public final class FastItemModelCache {

    public static final int GLINT_COLOR = -8372020;
    private static final int MAX_CACHED_MODELS = 1024;

    public static final ModelResourceLocation CAST_ROD_LOC = new ModelResourceLocation("fishing_rod_cast", "inventory");
    public static final ModelResourceLocation BOW_PULL_0_LOC = new ModelResourceLocation("bow_pulling_0", "inventory");
    public static final ModelResourceLocation BOW_PULL_1_LOC = new ModelResourceLocation("bow_pulling_1", "inventory");
    public static final ModelResourceLocation BOW_PULL_2_LOC = new ModelResourceLocation("bow_pulling_2", "inventory");

    private static final IdentityHashMap<IBakedModel, CachedLists> CACHE = new IdentityHashMap<IBakedModel, CachedLists>(512);
    private static int allocatedListsCount = 0;

    private static final class CachedLists {
        byte eligibility; // 0 = non inspecté, 1 = statique sans tint, 2 = statique avec tint (seul glint est cacheable), -1 = dynamique/exclu
        int baseWhiteList = -1;
        int glintList = -1;
    }

    private FastItemModelCache() {
    }

    public static boolean tryRenderCached(IBakedModel model, int color) {
        if (model == null || (color != -1 && color != GLINT_COLOR)) {
            return false;
        }

        CachedLists entry = CACHE.get(model);
        if (entry == null) {
            if (CACHE.size() >= MAX_CACHED_MODELS) {
                return false;
            }
            entry = inspectModel(model);
            CACHE.put(model, entry);
        }

        if (entry.eligibility < 0) {
            return false;
        }
        if (color == -1 && entry.eligibility != 1) {
            return false;
        }

        int listId = (color == -1) ? entry.baseWhiteList : entry.glintList;
        if (listId >= 0) {
            GlStateManager.callList(listId);
            GlStateManager.resetColor();
            return true;
        }

        if (allocatedListsCount >= MAX_CACHED_MODELS * 2) {
            return false;
        }

        int newList = GLAllocation.generateDisplayLists(1);
        if (newList <= 0) {
            return false;
        }
        allocatedListsCount++;

        GL11.glNewList(newList, GL11.GL_COMPILE_AND_EXECUTE);
        emitStaticModelGeometry(model, color);
        GL11.glEndList();
        GlStateManager.resetColor();

        if (color == -1) {
            entry.baseWhiteList = newList;
        } else {
            entry.glintList = newList;
        }
        return true;
    }

    private static CachedLists inspectModel(IBakedModel model) {
        CachedLists entry = new CachedLists();
        if (model.isBuiltInRenderer()
                || model instanceof WeightedBakedModel
                || model instanceof ISmartItemModel
                || model instanceof ISmartBlockModel) {
            entry.eligibility = -1;
            return entry;
        }

        Class<?> cls = model.getClass();
        String clsName = cls.getName();
        boolean knownStaticClass = (model instanceof SimpleBakedModel)
                || clsName.startsWith("net.minecraftforge.client.model.");
        if (!knownStaticClass) {
            entry.eligibility = -1;
            return entry;
        }

        boolean hasTint = false;
        EnumFacing[] facings = EnumCaches.FACINGS;
        for (int i = 0; i < facings.length; i++) {
            List<BakedQuad> fq = model.getFaceQuads(facings[i]);
            if (fq != null) {
                for (int q = 0, sz = fq.size(); q < sz; q++) {
                    BakedQuad quad = fq.get(q);
                    if (quad == null) {
                        entry.eligibility = -1;
                        return entry;
                    }
                    if (quad.hasTintIndex()) {
                        hasTint = true;
                    }
                }
            }
        }
        List<BakedQuad> gq = model.getGeneralQuads();
        if (gq != null) {
            for (int q = 0, sz = gq.size(); q < sz; q++) {
                BakedQuad quad = gq.get(q);
                if (quad == null) {
                    entry.eligibility = -1;
                    return entry;
                }
                if (quad.hasTintIndex()) {
                    hasTint = true;
                }
            }
        }

        entry.eligibility = (byte) (hasTint ? 2 : 1);
        return entry;
    }

    private static void emitStaticModelGeometry(IBakedModel model, int color) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.ITEM);
        EnumFacing[] facings = EnumCaches.FACINGS;
        for (int i = 0; i < facings.length; i++) {
            List<BakedQuad> fq = model.getFaceQuads(facings[i]);
            if (fq != null) {
                for (int q = 0, sz = fq.size(); q < sz; q++) {
                    LightUtil.renderQuadColor(wr, fq.get(q), color);
                }
            }
        }
        List<BakedQuad> gq = model.getGeneralQuads();
        if (gq != null) {
            for (int q = 0, sz = gq.size(); q < sz; q++) {
                LightUtil.renderQuadColor(wr, gq.get(q), color);
            }
        }
        tess.draw();
    }

    public static void clear() {
        if (CACHE.isEmpty()) {
            return;
        }
        for (CachedLists entry : CACHE.values()) {
            if (entry.baseWhiteList >= 0) {
                try {
                    GLAllocation.deleteDisplayLists(entry.baseWhiteList);
                } catch (Throwable ignored) {
                }
            }
            if (entry.glintList >= 0) {
                try {
                    GLAllocation.deleteDisplayLists(entry.glintList);
                } catch (Throwable ignored) {
                }
            }
        }
        CACHE.clear();
        allocatedListsCount = 0;
    }
}

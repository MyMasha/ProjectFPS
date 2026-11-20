package dev.purefps.core;

import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3i;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapData;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * Accélérateur dédié aux Cadres d'Items (EntityItemFrame / RenderItemFrame) et aux modèles BlockModelRenderer :
 * 1. Remplace les 2 Display Lists GL_QUADS par 2 VBO statiques GL_TRIANGLES + VAO GL30 (zéro glCallList).
 * 2. Fusionne les 22-26 appels begin()+draw() de BlockModelRenderer.renderModelBrightnessColor en 1 seul draw.
 * 3. Cache O(1) des MapData par identifiant entier meta (zéro allocation `"map_" + meta` par frame sur les murs de cartes).
 */
public final class FastItemFrameRenderer {

    private static final int VERTEX_STRIDE = 28; // DefaultVertexFormats.ITEM
    private static final int MAP_CACHE_SIZE = 256;
    private static final int MAP_CACHE_MASK = MAP_CACHE_SIZE - 1;

    private static boolean capsChecked = false;
    private static boolean vboSupported = false;
    private static boolean vaoSupported = false;

    private static IBakedModel lastNormalModel;
    private static int normalVboId = -1;
    private static int normalVaoId = -1;
    private static int normalVertCount = 0;

    private static IBakedModel lastMapModel;
    private static int mapVboId = -1;
    private static int mapVaoId = -1;
    private static int mapVertCount = 0;

    private static int reloadGeneration = 1;

    // Cache ouvert MapData (clé : int meta, validé par identité du World)
    private static int mapCacheWorldId = 0;
    private static final int[] MAP_KEYS = new int[MAP_CACHE_SIZE];
    private static final MapData[] MAP_VALUES = new MapData[MAP_CACHE_SIZE];

    static {
        Arrays.fill(MAP_KEYS, Integer.MIN_VALUE);
    }

    private FastItemFrameRenderer() {
    }

    public static int getReloadGeneration() {
        return reloadGeneration;
    }

    private static boolean isGlReady() {
        if (!capsChecked) {
            capsChecked = true;
            try {
                ContextCapabilities caps = GLContext.getCapabilities();
                vboSupported = caps != null && caps.OpenGL15;
                vaoSupported = caps != null && caps.OpenGL30;
            } catch (Exception | LinkageError ignored) {
                vboSupported = false;
                vaoSupported = false;
            }
        }
        return vboSupported;
    }

    public static MapData getCachedMapData(World world, ItemStack stack) {
        if (world == null || stack == null) {
            return null;
        }
        int worldId = System.identityHashCode(world);
        if (worldId != mapCacheWorldId) {
            Arrays.fill(MAP_KEYS, Integer.MIN_VALUE);
            Arrays.fill(MAP_VALUES, null);
            mapCacheWorldId = worldId;
        }
        int meta = stack.getMetadata();
        int slot = (meta * 0x9E3779B9) & MAP_CACHE_MASK;
        for (int p = 0; p < 4; p++) {
            int idx = (slot + p) & MAP_CACHE_MASK;
            int k = MAP_KEYS[idx];
            if (k == meta) {
                MapData hit = MAP_VALUES[idx];
                if (hit != null) {
                    return hit;
                }
                break;
            }
            if (k == Integer.MIN_VALUE) {
                break;
            }
        }
        MapData fetched = Items.filled_map.getMapData(stack, world);
        if (fetched != null) {
            int targetIdx = slot;
            for (int p = 0; p < 4; p++) {
                int idx = (slot + p) & MAP_CACHE_MASK;
                if (MAP_KEYS[idx] == Integer.MIN_VALUE || MAP_KEYS[idx] == meta) {
                    targetIdx = idx;
                    break;
                }
            }
            MAP_KEYS[targetIdx] = meta;
            MAP_VALUES[targetIdx] = fetched;
        }
        return fetched;
    }

    public static void clearMapCache() {
        Arrays.fill(MAP_KEYS, Integer.MIN_VALUE);
        Arrays.fill(MAP_VALUES, null);
        mapCacheWorldId = 0;
    }

    public static void renderFrameBorderCached(BlockModelRenderer bmr, IBakedModel model, boolean isMapFrame) {
        if (model == null) {
            return;
        }
        if (!isGlReady()) {
            renderModelBrightnessColorSingleDraw(model, 1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.resetColor();
            return;
        }

        if (isMapFrame) {
            if (model != lastMapModel || mapVboId <= 0) {
                releaseMapVbo();
                int[] built = buildStaticTriVbo(model);
                lastMapModel = model;
                mapVboId = built[0];
                mapVaoId = built[1];
                mapVertCount = built[2];
            }
            if (mapVboId > 0 && mapVertCount > 0) {
                drawStaticVbo(mapVboId, mapVaoId, mapVertCount);
            }
            GlStateManager.resetColor();
        } else {
            if (model != lastNormalModel || normalVboId <= 0) {
                releaseNormalVbo();
                int[] built = buildStaticTriVbo(model);
                lastNormalModel = model;
                normalVboId = built[0];
                normalVaoId = built[1];
                normalVertCount = built[2];
            }
            if (normalVboId > 0 && normalVertCount > 0) {
                drawStaticVbo(normalVboId, normalVaoId, normalVertCount);
            }
            GlStateManager.resetColor();
        }
    }

    private static int[] buildStaticTriVbo(IBakedModel model) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.ITEM);
        EnumFacing[] facings = EnumCaches.FACINGS;
        for (int i = 0; i < facings.length; i++) {
            appendBrightnessQuads(wr, model.getFaceQuads(facings[i]), 1.0F, 1.0F, 1.0F, 1.0F);
        }
        appendBrightnessQuads(wr, model.getGeneralQuads(), 1.0F, 1.0F, 1.0F, 1.0F);
        wr.finishDrawing();

        int quadVerts = wr.getVertexCount() & ~3;
        if (quadVerts <= 0) {
            wr.reset();
            return new int[] { -1, -1, 0 };
        }
        int quadCount = quadVerts >> 2;
        int triVerts = quadCount * 6;
        ByteBuffer triBuf = ByteBuffer.allocateDirect(triVerts * VERTEX_STRIDE).order(ByteOrder.nativeOrder());
        FastItemModelCache.expandQuads28ToTriangles(wr.getByteBuffer(), quadCount, triBuf);
        wr.reset();

        int vbo = OpenGlHelper.glGenBuffers();
        if (vbo <= 0) {
            return new int[] { -1, -1, 0 };
        }
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, triBuf, GL15.GL_STATIC_DRAW);

        int vao = -1;
        if (vaoSupported) {
            vao = GL30.glGenVertexArrays();
            if (vao > 0) {
                GL30.glBindVertexArray(vao);
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, vbo);
                OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
                GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
                GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_STRIDE, 0L);
                GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERTEX_STRIDE, 12L);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_STRIDE, 16L);
                GL11.glNormalPointer(GL11.GL_BYTE, VERTEX_STRIDE, 24L);
                GL30.glBindVertexArray(0);
            }
        }
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        return new int[] { vbo, vao, triVerts };
    }

    private static void drawStaticVbo(int vbo, int vao, int vertCount) {
        if (vao > 0) {
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertCount);
            GL30.glBindVertexArray(0);
            return;
        }
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, vbo);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_STRIDE, 0L);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERTEX_STRIDE, 12L);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_STRIDE, 16L);
        GL11.glNormalPointer(GL11.GL_BYTE, VERTEX_STRIDE, 24L);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertCount);
        GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
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

    private static void releaseNormalVbo() {
        if (normalVaoId > 0) {
            try {
                GL30.glDeleteVertexArrays(normalVaoId);
            } catch (Exception | LinkageError ignored) {
            }
            normalVaoId = -1;
        }
        if (normalVboId > 0) {
            try {
                OpenGlHelper.glDeleteBuffers(normalVboId);
            } catch (Exception | LinkageError ignored) {
            }
            normalVboId = -1;
        }
        normalVertCount = 0;
    }

    private static void releaseMapVbo() {
        if (mapVaoId > 0) {
            try {
                GL30.glDeleteVertexArrays(mapVaoId);
            } catch (Exception | LinkageError ignored) {
            }
            mapVaoId = -1;
        }
        if (mapVboId > 0) {
            try {
                OpenGlHelper.glDeleteBuffers(mapVboId);
            } catch (Exception | LinkageError ignored) {
            }
            mapVboId = -1;
        }
        mapVertCount = 0;
    }

    public static void clear() {
        reloadGeneration++;
        releaseNormalVbo();
        releaseMapVbo();
        lastNormalModel = null;
        lastMapModel = null;
        clearMapCache();
    }
}

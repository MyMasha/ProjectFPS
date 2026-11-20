package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
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
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Arène VBO statique GL_TRIANGLES + VAO GL30 partagée pour les IBakedModel d'items (MashClient 4.5.15) :
 * - Remplace intégralement les Display Lists GL_QUADS (tueuses de FPS sous Mesa/Zink et drivers modernes)
 *   par UN SEUL VBO GL_STATIC_DRAW + UN SEUL VAO GL30 partagé ((firstVertex, vertexCount) par modèle).
 * - N'insère JAMAIS les modèles dynamiques / inéligibles (eligibility == -1) dans CACHE.
 * - Capture et rejoue le bitmask SmartAnimations (animMask0/1) pour que les épées/items animés restent fluides.
 * - Purgé proprement à chaque rechargement de pack de ressources (RenderItem.onResourceManagerReload).
 */
public final class FastItemModelCache {

    public static final int GLINT_COLOR = -8372020;
    private static final int MAX_CACHED_MODELS = 1024;
    private static final int VERTEX_STRIDE = 28; // DefaultVertexFormats.ITEM : XYZ(12) + RGBA(4) + UV(8) + Normal3B+Pad1B(4)
    private static final int INITIAL_ARENA_VERTS = 32768;
    private static final int MAX_ARENA_VERTS = 524288; // ~14 Mo max VRAM

    public static final ModelResourceLocation CAST_ROD_LOC = new ModelResourceLocation("fishing_rod_cast", "inventory");
    public static final ModelResourceLocation BOW_PULL_0_LOC = new ModelResourceLocation("bow_pulling_0", "inventory");
    public static final ModelResourceLocation BOW_PULL_1_LOC = new ModelResourceLocation("bow_pulling_1", "inventory");
    public static final ModelResourceLocation BOW_PULL_2_LOC = new ModelResourceLocation("bow_pulling_2", "inventory");

    private static final IdentityHashMap<IBakedModel, CachedVboSlices> CACHE =
            new IdentityHashMap<IBakedModel, CachedVboSlices>(512);

    private static final long[] TEMP_ANIM_MASKS = new long[2];

    private static boolean capsChecked = false;
    private static boolean vboSupported = false;
    private static boolean vaoSupported = false;
    private static boolean glFailed = false;

    private static int arenaVboId = -1;
    private static int arenaVaoId = -1;
    private static int arenaCapacityVerts = 0;
    private static int arenaUsedVerts = 0;
    private static ByteBuffer arenaShadow = null;
    private static ByteBuffer uploadScratch = null;

    private static final class CachedVboSlices {
        byte eligibility; // 1 = statique sans tint, 2 = statique avec tint (seul glint est cacheable)
        int baseFirst = -1;
        int baseCount = 0;
        int glintFirst = -1;
        int glintCount = 0;
        long animMask0 = 0L;
        long animMask1 = 0L;
    }

    private FastItemModelCache() {
    }

    private static boolean isGlReady() {
        if (glFailed) {
            return false;
        }
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

    public static boolean tryRenderCached(IBakedModel model, int color) {
        if (model == null || (color != -1 && color != GLINT_COLOR) || !isGlReady()) {
            return false;
        }

        // Rejet rapide hors cache des modèles dynamiques / moddés (ne pollue JAMAIS CACHE)
        if (model.isBuiltInRenderer()
                || model instanceof WeightedBakedModel
                || model instanceof ISmartItemModel
                || model instanceof ISmartBlockModel) {
            return false;
        }

        CachedVboSlices entry = CACHE.get(model);
        if (entry == null) {
            if (CACHE.size() >= MAX_CACHED_MODELS) {
                return false;
            }
            entry = inspectStaticModel(model);
            if (entry == null) {
                return false;
            }
            CACHE.put(model, entry);
        }

        if (color == -1 && entry.eligibility != 1) {
            return false;
        }

        if (PureFPSConfig.smartAnimations && (entry.animMask0 | entry.animMask1) != 0L) {
            SmartAnimations.recordVisibleMasks(entry.animMask0, entry.animMask1);
        }

        int first = (color == -1) ? entry.baseFirst : entry.glintFirst;
        int count = (color == -1) ? entry.baseCount : entry.glintCount;
        if (first >= 0) {
            if (count > 0) {
                drawArenaSlice(first, count);
            }
            GlStateManager.resetColor();
            return true;
        }

        int[] slice = compileModelIntoArena(model, color);
        if (slice == null) {
            return false;
        }
        if (color == -1) {
            entry.baseFirst = slice[0];
            entry.baseCount = slice[1];
        } else {
            entry.glintFirst = slice[0];
            entry.glintCount = slice[1];
        }
        if (slice[1] > 0) {
            drawArenaSlice(slice[0], slice[1]);
        }
        GlStateManager.resetColor();
        return true;
    }

    private static CachedVboSlices inspectStaticModel(IBakedModel model) {
        Class<?> cls = model.getClass();
        String clsName = cls.getName();
        boolean knownStaticClass = (model instanceof SimpleBakedModel)
                || clsName.startsWith("net.minecraftforge.client.model.");
        if (!knownStaticClass) {
            return null;
        }

        boolean hasTint = false;
        TEMP_ANIM_MASKS[0] = 0L;
        TEMP_ANIM_MASKS[1] = 0L;
        EnumFacing[] facings = EnumCaches.FACINGS;
        for (int i = 0; i < facings.length; i++) {
            List<BakedQuad> fq = model.getFaceQuads(facings[i]);
            if (fq != null && !fq.isEmpty()) {
                for (int q = 0, sz = fq.size(); q < sz; q++) {
                    BakedQuad quad = fq.get(q);
                    if (quad == null) {
                        return null;
                    }
                    if (quad.hasTintIndex()) {
                        hasTint = true;
                    }
                }
                SmartAnimations.collectQuadsMask(fq, TEMP_ANIM_MASKS);
            }
        }
        List<BakedQuad> gq = model.getGeneralQuads();
        if (gq != null && !gq.isEmpty()) {
            for (int q = 0, sz = gq.size(); q < sz; q++) {
                BakedQuad quad = gq.get(q);
                if (quad == null) {
                    return null;
                }
                if (quad.hasTintIndex()) {
                    hasTint = true;
                }
            }
            SmartAnimations.collectQuadsMask(gq, TEMP_ANIM_MASKS);
        }

        CachedVboSlices entry = new CachedVboSlices();
        entry.eligibility = (byte) (hasTint ? 2 : 1);
        entry.animMask0 = TEMP_ANIM_MASKS[0];
        entry.animMask1 = TEMP_ANIM_MASKS[1];
        return entry;
    }

    private static int[] compileModelIntoArena(IBakedModel model, int color) {
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
        wr.finishDrawing();

        int quadVerts = wr.getVertexCount() & ~3;
        if (quadVerts <= 0) {
            wr.reset();
            return new int[] { 0, 0 };
        }

        int triVerts = (quadVerts >> 2) * 6;
        if (arenaUsedVerts + triVerts > MAX_ARENA_VERTS) {
            wr.reset();
            return null;
        }

        ByteBuffer src = wr.getByteBuffer();
        int firstVert = appendQuadsAsTriangles(src, quadVerts);
        wr.reset();
        if (firstVert < 0) {
            return null;
        }
        return new int[] { firstVert, triVerts };
    }

    private static int appendQuadsAsTriangles(ByteBuffer srcQuadBytes, int quadVerts) {
        int quadCount = quadVerts >> 2;
        int triVerts = quadCount * 6;
        int triBytes = triVerts * VERTEX_STRIDE;

        if (uploadScratch == null || uploadScratch.capacity() < triBytes) {
            int newCap = Math.max(triBytes, 16384);
            uploadScratch = ByteBuffer.allocateDirect(newCap).order(ByteOrder.nativeOrder());
        }
        expandQuads28ToTriangles(srcQuadBytes, quadCount, uploadScratch);

        int firstVert = arenaUsedVerts;
        int neededVerts = firstVert + triVerts;
        if (!ensureArenaCapacity(neededVerts)) {
            return -1;
        }

        int byteOffset = firstVert * VERTEX_STRIDE;
        arenaShadow.limit(byteOffset + triBytes);
        arenaShadow.position(byteOffset);
        uploadScratch.position(0);
        uploadScratch.limit(triBytes);
        arenaShadow.put(uploadScratch);
        arenaShadow.clear();

        uploadScratch.position(0);
        uploadScratch.limit(triBytes);
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arenaVboId);
        GL15.glBufferSubData(OpenGlHelper.GL_ARRAY_BUFFER, (long) byteOffset, uploadScratch);
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        uploadScratch.clear();

        arenaUsedVerts = neededVerts;
        return firstVert;
    }

    /**
     * Déplie N quads au format 28 octets/sommet (DefaultVertexFormats.ITEM) en 6*N sommets GL_TRIANGLES (0,1,2, 2,3,0).
     */
    static void expandQuads28ToTriangles(ByteBuffer src, int quadCount, ByteBuffer dst) {
        dst.clear();
        int sOff = 0;
        int dOff = 0;
        for (int q = 0; q < quadCount; q++) {
            copyVertex28(src, sOff,      dst, dOff);       // v0
            copyVertex28(src, sOff + 28, dst, dOff + 28);  // v1
            copyVertex28(src, sOff + 56, dst, dOff + 56);  // v2
            copyVertex28(src, sOff + 56, dst, dOff + 84);  // v2
            copyVertex28(src, sOff + 84, dst, dOff + 112); // v3
            copyVertex28(src, sOff,      dst, dOff + 140); // v0
            sOff += 112;
            dOff += 168;
        }
        dst.position(0);
        dst.limit(dOff);
    }

    private static void copyVertex28(ByteBuffer src, int sOff, ByteBuffer dst, int dOff) {
        dst.putLong(dOff, src.getLong(sOff));
        dst.putLong(dOff + 8, src.getLong(sOff + 8));
        dst.putLong(dOff + 16, src.getLong(sOff + 16));
        dst.putInt(dOff + 24, src.getInt(sOff + 24));
    }

    private static boolean ensureArenaCapacity(int neededVerts) {
        if (arenaVboId > 0 && neededVerts <= arenaCapacityVerts) {
            return true;
        }
        int newCap = Math.max(INITIAL_ARENA_VERTS, arenaCapacityVerts << 1);
        while (newCap < neededVerts) {
            newCap <<= 1;
        }
        if (newCap > MAX_ARENA_VERTS) {
            newCap = Math.max(neededVerts, MAX_ARENA_VERTS);
        }

        if (arenaVboId <= 0) {
            arenaVboId = OpenGlHelper.glGenBuffers();
            if (arenaVboId <= 0) {
                glFailed = true;
                return false;
            }
        }

        ByteBuffer nextShadow = ByteBuffer.allocateDirect(newCap * VERTEX_STRIDE).order(ByteOrder.nativeOrder());
        if (arenaShadow != null && arenaUsedVerts > 0) {
            int usedBytes = arenaUsedVerts * VERTEX_STRIDE;
            arenaShadow.position(0);
            arenaShadow.limit(usedBytes);
            nextShadow.position(0);
            nextShadow.put(arenaShadow);
        }
        arenaShadow = nextShadow;
        arenaCapacityVerts = newCap;

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arenaVboId);
        if (arenaUsedVerts > 0) {
            arenaShadow.position(0);
            arenaShadow.limit(arenaCapacityVerts * VERTEX_STRIDE);
            GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, arenaShadow, GL15.GL_STATIC_DRAW);
        } else {
            GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, (long) arenaCapacityVerts * VERTEX_STRIDE, GL15.GL_STATIC_DRAW);
        }

        if (vaoSupported && arenaVaoId <= 0) {
            arenaVaoId = GL30.glGenVertexArrays();
            if (arenaVaoId > 0) {
                GL30.glBindVertexArray(arenaVaoId);
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arenaVboId);
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
        return true;
    }

    private static void drawArenaSlice(int firstVert, int vertCount) {
        if (arenaVaoId > 0) {
            GL30.glBindVertexArray(arenaVaoId);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, firstVert, vertCount);
            GL30.glBindVertexArray(0);
            return;
        }
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arenaVboId);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_STRIDE, 0L);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERTEX_STRIDE, 12L);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_STRIDE, 16L);
        GL11.glNormalPointer(GL11.GL_BYTE, VERTEX_STRIDE, 24L);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, firstVert, vertCount);
        GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
    }

    public static void clear() {
        CACHE.clear();
        arenaUsedVerts = 0;
        arenaCapacityVerts = 0;
        arenaShadow = null;
        if (arenaVaoId > 0) {
            try {
                GL30.glDeleteVertexArrays(arenaVaoId);
            } catch (Exception | LinkageError ignored) {
            }
            arenaVaoId = -1;
        }
        if (arenaVboId > 0) {
            try {
                OpenGlHelper.glDeleteBuffers(arenaVboId);
            } catch (Exception | LinkageError ignored) {
            }
            arenaVboId = -1;
        }
    }
}

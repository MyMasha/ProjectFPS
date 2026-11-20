package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.mixin.MixinModelBoxAccessor;
import dev.purefps.mixin.MixinTexturedQuadAccessor;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelSkeleton;
import net.minecraft.client.model.ModelZombie;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Batcher VBO streaming GL_TRIANGLES + VAO GL30 pour les modèles d'entités (joueurs, armures, coffres, mobs) :
 * - Remplace 100 % des Display Lists GL_QUADS de ModelRenderer par un Ring VBO GL_TRIANGLES (zéro émulation
 *   logicielle GL_QUADS sous Vulkan/Zink, zéro glCallList).
 * - Fusionne tous les membres d'un joueur (12 ModelRenderer), d'une armure ou d'un coffre (3 ModelRenderer)
 *   en UN SEUL glDrawArrays(GL_TRIANGLES) grâce à une pile matricielle CPU 4x4 sans allocation.
 */
public final class EntityModelBatcher {

    // Format sommet (32 octets alignés) : XYZ (12B) + UV (8B) + Normal 3F (12B)
    public static final int VERTEX_STRIDE = 32;
    private static final int MAX_BATCH_VERTS = 16384; // 512 Ko CPU scratch
    private static final int RING_CAPACITY_VERTS = 65536; // 2 Mo Ring VBO GPU
    private static final int RING_CAPACITY_BYTES = RING_CAPACITY_VERTS * VERTEX_STRIDE;
    private static final int MAP_FLAGS = GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT;

    private static final int MAX_STACK_DEPTH = 24;
    private static final float DEG_TO_RAD = 0.017453292519943295F;

    /**
     * Vrai uniquement pendant qu'un batch ModelBase/ModelChest capture les transformations GlStateManager.
     */
    public static boolean capturing = false;
    private static int batchDepth = 0;

    // Pile matricielle 4x4 colonne-majeure (même convention qu'OpenGL)
    private static final float[] STACK = new float[MAX_STACK_DEPTH * 16];
    private static int stackTop = 0;
    private static final float[] TMP_MAT = new float[16];

    private static final ByteBuffer CPU_BUF = ByteBuffer.allocateDirect(MAX_BATCH_VERTS * VERTEX_STRIDE)
            .order(ByteOrder.nativeOrder());
    private static int queuedVerts = 0;
    private static int writeByteOffset = 0;

    private static boolean capsChecked = false;
    private static boolean vboSupported = false;
    private static boolean vaoSupported = false;
    private static boolean glFailed = false;

    private static int ringVboId = -1;
    private static int ringVaoId = -1;
    private static int ringWriteVert = 0;
    private static ByteBuffer mappedScratch = null;

    static {
        setIdentity(0);
    }

    private EntityModelBatcher() {
    }

    public static boolean isBatchableLivingModel(ModelBase model) {
        if (model == null) {
            return false;
        }
        Class<?> cls = model.getClass();
        return cls == ModelPlayer.class
                || cls == ModelBiped.class
                || cls == ModelZombie.class
                || cls == ModelSkeleton.class;
    }

    public static boolean isSupported() {
        if (!PureFPSConfig.enabled || glFailed) {
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

    public static boolean isBatching() {
        return batchDepth > 0;
    }

    public static void beginBatch() {
        if (!isSupported()) {
            return;
        }
        if (batchDepth == 0) {
            stackTop = 0;
            setIdentity(0);
            queuedVerts = 0;
            writeByteOffset = 0;
            capturing = true;
        }
        batchDepth++;
    }

    public static void endBatch() {
        if (batchDepth <= 0) {
            return;
        }
        batchDepth--;
        if (batchDepth == 0) {
            capturing = false;
            flush();
            stackTop = 0;
            setIdentity(0);
        }
    }

    public static void abortBatch() {
        capturing = false;
        batchDepth = 0;
        queuedVerts = 0;
        writeByteOffset = 0;
        stackTop = 0;
        setIdentity(0);
    }

    // ---- Pile matricielle CPU (compatible OpenGL column-major) ----

    public static void onPushMatrix() {
        if (stackTop + 1 >= MAX_STACK_DEPTH) {
            return;
        }
        int src = stackTop * 16;
        stackTop++;
        System.arraycopy(STACK, src, STACK, stackTop * 16, 16);
    }

    public static void onPopMatrix() {
        if (stackTop > 0) {
            stackTop--;
        }
    }

    public static void onTranslate(float x, float y, float z) {
        if (x == 0.0F && y == 0.0F && z == 0.0F) {
            return;
        }
        int o = stackTop * 16;
        STACK[o + 12] += STACK[o] * x + STACK[o + 4] * y + STACK[o + 8] * z;
        STACK[o + 13] += STACK[o + 1] * x + STACK[o + 5] * y + STACK[o + 9] * z;
        STACK[o + 14] += STACK[o + 2] * x + STACK[o + 6] * y + STACK[o + 10] * z;
        STACK[o + 15] += STACK[o + 3] * x + STACK[o + 7] * y + STACK[o + 11] * z;
    }

    public static void onScale(float x, float y, float z) {
        int o = stackTop * 16;
        STACK[o] *= x;     STACK[o + 1] *= x;  STACK[o + 2] *= x;  STACK[o + 3] *= x;
        STACK[o + 4] *= y; STACK[o + 5] *= y;  STACK[o + 6] *= y;  STACK[o + 7] *= y;
        STACK[o + 8] *= z; STACK[o + 9] *= z;  STACK[o + 10] *= z; STACK[o + 11] *= z;
    }

    public static void onRotateDegrees(float angleDeg, float ax, float ay, float az) {
        if (angleDeg == 0.0F) {
            return;
        }
        float rad = angleDeg * DEG_TO_RAD;
        if (ax == 0.0F && ay == 0.0F && az == 1.0F) {
            rotateRadiansZ(rad);
            return;
        }
        if (ax == 0.0F && ay == 1.0F && az == 0.0F) {
            rotateRadiansY(rad);
            return;
        }
        if (ax == 1.0F && ay == 0.0F && az == 0.0F) {
            rotateRadiansX(rad);
            return;
        }
        float lenSq = ax * ax + ay * ay + az * az;
        if (lenSq < 1e-10F) {
            return;
        }
        float invLen = 1.0F / (float) Math.sqrt(lenSq);
        float x = ax * invLen;
        float y = ay * invLen;
        float z = az * invLen;
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        float t = 1.0F - c;

        float r00 = t * x * x + c;
        float r01 = t * x * y - s * z;
        float r02 = t * x * z + s * y;
        float r10 = t * x * y + s * z;
        float r11 = t * y * y + c;
        float r12 = t * y * z - s * x;
        float r20 = t * x * z - s * y;
        float r21 = t * y * z + s * x;
        float r22 = t * z * z + c;

        multiplyUpper3x3(r00, r10, r20, r01, r11, r21, r02, r12, r22);
    }

    public static void rotateRadiansX(float rad) {
        if (rad == 0.0F) {
            return;
        }
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        int o = stackTop * 16;
        for (int row = 0; row < 4; row++) {
            float m1 = STACK[o + 4 + row];
            float m2 = STACK[o + 8 + row];
            STACK[o + 4 + row] = m1 * c + m2 * s;
            STACK[o + 8 + row] = m2 * c - m1 * s;
        }
    }

    public static void rotateRadiansY(float rad) {
        if (rad == 0.0F) {
            return;
        }
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        int o = stackTop * 16;
        for (int row = 0; row < 4; row++) {
            float m0 = STACK[o + row];
            float m2 = STACK[o + 8 + row];
            STACK[o + row] = m0 * c - m2 * s;
            STACK[o + 8 + row] = m0 * s + m2 * c;
        }
    }

    public static void rotateRadiansZ(float rad) {
        if (rad == 0.0F) {
            return;
        }
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        int o = stackTop * 16;
        for (int row = 0; row < 4; row++) {
            float m0 = STACK[o + row];
            float m1 = STACK[o + 4 + row];
            STACK[o + row] = m0 * c + m1 * s;
            STACK[o + 4 + row] = m1 * c - m0 * s;
        }
    }

    private static void multiplyUpper3x3(
            float c0r0, float c0r1, float c0r2,
            float c1r0, float c1r1, float c1r2,
            float c2r0, float c2r1, float c2r2
    ) {
        int o = stackTop * 16;
        for (int row = 0; row < 4; row++) {
            float a0 = STACK[o + row];
            float a1 = STACK[o + 4 + row];
            float a2 = STACK[o + 8 + row];
            TMP_MAT[row] = a0 * c0r0 + a1 * c0r1 + a2 * c0r2;
            TMP_MAT[4 + row] = a0 * c1r0 + a1 * c1r1 + a2 * c1r2;
            TMP_MAT[8 + row] = a0 * c2r0 + a1 * c2r1 + a2 * c2r2;
        }
        System.arraycopy(TMP_MAT, 0, STACK, o, 12);
    }

    private static void setIdentity(int baseOffset) {
        for (int i = 0; i < 16; i++) {
            STACK[baseOffset + i] = 0.0F;
        }
        STACK[baseOffset] = 1.0F;
        STACK[baseOffset + 5] = 1.0F;
        STACK[baseOffset + 10] = 1.0F;
        STACK[baseOffset + 15] = 1.0F;
    }

    // ---- Émission de géométrie ModelBox (GL_TRIANGLES) ----

    public static boolean areAllStandardModelBoxes(List<ModelBox> cubes) {
        if (cubes == null || cubes.isEmpty()) {
            return true;
        }
        for (int i = 0, sz = cubes.size(); i < sz; i++) {
            ModelBox box = cubes.get(i);
            if (box == null || box.getClass() != ModelBox.class) {
                return false;
            }
        }
        return true;
    }

    /**
     * Émet tous les quads d'une cubeList dans le batch courant (transformés par la matrice CPU au sommet de pile).
     */
    public static void emitCubesTransformed(List<ModelBox> cubes, float scale) {
        if (cubes == null || cubes.isEmpty()) {
            return;
        }
        int o = stackTop * 16;
        float m00 = STACK[o],     m10 = STACK[o + 1],  m20 = STACK[o + 2];
        float m01 = STACK[o + 4], m11 = STACK[o + 5],  m21 = STACK[o + 6];
        float m02 = STACK[o + 8], m12 = STACK[o + 9],  m22 = STACK[o + 10];
        float m03 = STACK[o + 12], m13 = STACK[o + 13], m23 = STACK[o + 14];

        for (int c = 0, cCount = cubes.size(); c < cCount; c++) {
            TexturedQuad[] quads = ((MixinModelBoxAccessor) cubes.get(c)).purefps$getQuadList();
            if (quads == null) {
                continue;
            }
            for (int q = 0; q < quads.length; q++) {
                TexturedQuad quad = quads[q];
                if (quad == null || quad.vertexPositions == null || quad.vertexPositions.length < 4) {
                    continue;
                }
                if (queuedVerts + 6 > MAX_BATCH_VERTS) {
                    flush();
                }

                PositionTextureVertex pv0 = quad.vertexPositions[0];
                PositionTextureVertex pv1 = quad.vertexPositions[1];
                PositionTextureVertex pv2 = quad.vertexPositions[2];
                PositionTextureVertex pv3 = quad.vertexPositions[3];

                Vec3 v0 = pv0.vector3D;
                Vec3 v1 = pv1.vector3D;
                Vec3 v2 = pv2.vector3D;
                Vec3 v3 = pv3.vector3D;

                // Normale locale exacte de Vanilla TexturedQuad.draw : (v2 - v1) x (v0 - v1)
                float e1x = (float) (v2.xCoord - v1.xCoord);
                float e1y = (float) (v2.yCoord - v1.yCoord);
                float e1z = (float) (v2.zCoord - v1.zCoord);
                float e2x = (float) (v0.xCoord - v1.xCoord);
                float e2y = (float) (v0.yCoord - v1.yCoord);
                float e2z = (float) (v0.zCoord - v1.zCoord);
                float lnx = e1y * e2z - e1z * e2y;
                float lny = e1z * e2x - e1x * e2z;
                float lnz = e1x * e2y - e1y * e2x;
                if (((MixinTexturedQuadAccessor) quad).purefps$isInvertNormal()) {
                    lnx = -lnx;
                    lny = -lny;
                    lnz = -lnz;
                }

                // Transformation de la normale par la sous-matrice 3x3 et normalisation
                float nx = m00 * lnx + m01 * lny + m02 * lnz;
                float ny = m10 * lnx + m11 * lny + m12 * lnz;
                float nz = m20 * lnx + m21 * lny + m22 * lnz;
                float nLenSq = nx * nx + ny * ny + nz * nz;
                if (nLenSq > 1e-12F) {
                    float invN = 1.0F / (float) Math.sqrt(nLenSq);
                    nx *= invN;
                    ny *= invN;
                    nz *= invN;
                }

                float lx0 = (float) v0.xCoord * scale, ly0 = (float) v0.yCoord * scale, lz0 = (float) v0.zCoord * scale;
                float lx1 = (float) v1.xCoord * scale, ly1 = (float) v1.yCoord * scale, lz1 = (float) v1.zCoord * scale;
                float lx2 = (float) v2.xCoord * scale, ly2 = (float) v2.yCoord * scale, lz2 = (float) v2.zCoord * scale;
                float lx3 = (float) v3.xCoord * scale, ly3 = (float) v3.yCoord * scale, lz3 = (float) v3.zCoord * scale;

                float x0 = m00 * lx0 + m01 * ly0 + m02 * lz0 + m03;
                float y0 = m10 * lx0 + m11 * ly0 + m12 * lz0 + m13;
                float z0 = m20 * lx0 + m21 * ly0 + m22 * lz0 + m23;

                float x1 = m00 * lx1 + m01 * ly1 + m02 * lz1 + m03;
                float y1 = m10 * lx1 + m11 * ly1 + m12 * lz1 + m13;
                float z1 = m20 * lx1 + m21 * ly1 + m22 * lz1 + m23;

                float x2 = m00 * lx2 + m01 * ly2 + m02 * lz2 + m03;
                float y2 = m10 * lx2 + m11 * ly2 + m12 * lz2 + m13;
                float z2 = m20 * lx2 + m21 * ly2 + m22 * lz2 + m23;

                float x3 = m00 * lx3 + m01 * ly3 + m02 * lz3 + m03;
                float y3 = m10 * lx3 + m11 * ly3 + m12 * lz3 + m13;
                float z3 = m20 * lx3 + m21 * ly3 + m22 * lz3 + m23;

                putTriQuad(
                        x0, y0, z0, pv0.texturePositionX, pv0.texturePositionY,
                        x1, y1, z1, pv1.texturePositionX, pv1.texturePositionY,
                        x2, y2, z2, pv2.texturePositionX, pv2.texturePositionY,
                        x3, y3, z3, pv3.texturePositionX, pv3.texturePositionY,
                        nx, ny, nz
                );
            }
        }
    }

    /**
     * Rend immédiatement une cubeList (hors batch) via le VBO GL_TRIANGLES + VAO (0 Display List GL_QUADS).
     */
    public static boolean drawCubesImmediate(List<ModelBox> cubes, float scale) {
        if (!isSupported() || !areAllStandardModelBoxes(cubes)) {
            return false;
        }
        queuedVerts = 0;
        writeByteOffset = 0;

        for (int c = 0, cCount = cubes.size(); c < cCount; c++) {
            TexturedQuad[] quads = ((MixinModelBoxAccessor) cubes.get(c)).purefps$getQuadList();
            if (quads == null) {
                continue;
            }
            for (int q = 0; q < quads.length; q++) {
                TexturedQuad quad = quads[q];
                if (quad == null || quad.vertexPositions == null || quad.vertexPositions.length < 4) {
                    continue;
                }
                if (queuedVerts + 6 > MAX_BATCH_VERTS) {
                    flush();
                }

                PositionTextureVertex pv0 = quad.vertexPositions[0];
                PositionTextureVertex pv1 = quad.vertexPositions[1];
                PositionTextureVertex pv2 = quad.vertexPositions[2];
                PositionTextureVertex pv3 = quad.vertexPositions[3];

                Vec3 v0 = pv0.vector3D;
                Vec3 v1 = pv1.vector3D;
                Vec3 v2 = pv2.vector3D;
                Vec3 v3 = pv3.vector3D;

                float e1x = (float) (v2.xCoord - v1.xCoord);
                float e1y = (float) (v2.yCoord - v1.yCoord);
                float e1z = (float) (v2.zCoord - v1.zCoord);
                float e2x = (float) (v0.xCoord - v1.xCoord);
                float e2y = (float) (v0.yCoord - v1.yCoord);
                float e2z = (float) (v0.zCoord - v1.zCoord);
                float nx = e1y * e2z - e1z * e2y;
                float ny = e1z * e2x - e1x * e2z;
                float nz = e1x * e2y - e1y * e2x;
                if (((MixinTexturedQuadAccessor) quad).purefps$isInvertNormal()) {
                    nx = -nx;
                    ny = -ny;
                    nz = -nz;
                }
                float nLenSq = nx * nx + ny * ny + nz * nz;
                if (nLenSq > 1e-12F) {
                    float invN = 1.0F / (float) Math.sqrt(nLenSq);
                    nx *= invN;
                    ny *= invN;
                    nz *= invN;
                }

                putTriQuad(
                        (float) v0.xCoord * scale, (float) v0.yCoord * scale, (float) v0.zCoord * scale, pv0.texturePositionX, pv0.texturePositionY,
                        (float) v1.xCoord * scale, (float) v1.yCoord * scale, (float) v1.zCoord * scale, pv1.texturePositionX, pv1.texturePositionY,
                        (float) v2.xCoord * scale, (float) v2.yCoord * scale, (float) v2.zCoord * scale, pv2.texturePositionX, pv2.texturePositionY,
                        (float) v3.xCoord * scale, (float) v3.yCoord * scale, (float) v3.zCoord * scale, pv3.texturePositionX, pv3.texturePositionY,
                        nx, ny, nz
                );
            }
        }

        flush();
        return true;
    }

    private static void putTriQuad(
            float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3,
            float nx, float ny, float nz
    ) {
        int off = writeByteOffset;
        ByteBuffer b = CPU_BUF;
        // Triangle 1 : 0, 1, 2
        putVert(b, off,       x0, y0, z0, u0, v0, nx, ny, nz);
        putVert(b, off + 32,  x1, y1, z1, u1, v1, nx, ny, nz);
        putVert(b, off + 64,  x2, y2, z2, u2, v2, nx, ny, nz);
        // Triangle 2 : 2, 3, 0
        putVert(b, off + 96,  x2, y2, z2, u2, v2, nx, ny, nz);
        putVert(b, off + 128, x3, y3, z3, u3, v3, nx, ny, nz);
        putVert(b, off + 160, x0, y0, z0, u0, v0, nx, ny, nz);

        writeByteOffset = off + 192;
        queuedVerts += 6;
    }

    private static void putVert(
            ByteBuffer b, int off,
            float x, float y, float z,
            float u, float v,
            float nx, float ny, float nz
    ) {
        b.putFloat(off, x);
        b.putFloat(off + 4, y);
        b.putFloat(off + 8, z);
        b.putFloat(off + 12, u);
        b.putFloat(off + 16, v);
        b.putFloat(off + 20, nx);
        b.putFloat(off + 24, ny);
        b.putFloat(off + 28, nz);
    }

    private static boolean ensureRingReady() {
        if (ringVboId > 0) {
            return true;
        }
        ringVboId = OpenGlHelper.glGenBuffers();
        if (ringVboId <= 0) {
            glFailed = true;
            return false;
        }
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, ringVboId);
        GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, RING_CAPACITY_BYTES, GL15.GL_STREAM_DRAW);
        ringWriteVert = 0;

        if (vaoSupported) {
            ringVaoId = GL30.glGenVertexArrays();
            if (ringVaoId > 0) {
                GL30.glBindVertexArray(ringVaoId);
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, ringVboId);
                OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
                GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_STRIDE, 0L);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_STRIDE, 12L);
                GL11.glNormalPointer(GL11.GL_FLOAT, VERTEX_STRIDE, 20L);
                GL30.glBindVertexArray(0);
            }
        }
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        return true;
    }

    public static void flush() {
        int count = queuedVerts;
        if (count <= 0) {
            return;
        }
        int byteLen = writeByteOffset;
        queuedVerts = 0;
        writeByteOffset = 0;

        if (!ensureRingReady()) {
            return;
        }

        CPU_BUF.position(0);
        CPU_BUF.limit(byteLen);

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, ringVboId);
        if (ringWriteVert + count > RING_CAPACITY_VERTS) {
            GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, RING_CAPACITY_BYTES, GL15.GL_STREAM_DRAW);
            ringWriteVert = 0;
        }
        int firstVert = ringWriteVert;
        long byteOff = (long) firstVert * VERTEX_STRIDE;
        ringWriteVert += count;

        boolean uploaded = false;
        if (vaoSupported) {
            ByteBuffer mapped = GL30.glMapBufferRange(
                    OpenGlHelper.GL_ARRAY_BUFFER, byteOff, byteLen, MAP_FLAGS, mappedScratch
            );
            if (mapped != null) {
                mappedScratch = mapped;
                mapped.clear();
                mapped.put(CPU_BUF);
                GL15.glUnmapBuffer(OpenGlHelper.GL_ARRAY_BUFFER);
                uploaded = true;
            }
        }
        if (!uploaded) {
            GL15.glBufferSubData(OpenGlHelper.GL_ARRAY_BUFFER, byteOff, CPU_BUF);
        }

        if (ringVaoId > 0) {
            GL30.glBindVertexArray(ringVaoId);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, firstVert, count);
            GL30.glBindVertexArray(0);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        } else {
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_STRIDE, 0L);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_STRIDE, 12L);
            GL11.glNormalPointer(GL11.GL_FLOAT, VERTEX_STRIDE, 20L);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, firstVert, count);
            GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        }
    }

    public static void cleanup() {
        abortBatch();
        if (ringVaoId > 0) {
            try {
                GL30.glDeleteVertexArrays(ringVaoId);
            } catch (Exception | LinkageError ignored) {
            }
            ringVaoId = -1;
        }
        if (ringVboId > 0) {
            try {
                OpenGlHelper.glDeleteBuffers(ringVboId);
            } catch (Exception | LinkageError ignored) {
            }
            ringVboId = -1;
        }
        ringWriteVert = 0;
        mappedScratch = null;
    }
}

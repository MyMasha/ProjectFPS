package dev.purefps.core;

import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Miroir Java pur des matrices caméra (PROJECTION, MODELVIEW) et du VIEWPORT capturé uniquement
 * pendant EntityRenderer.setupCameraTransform() :
 * - Élimine les 5 appels synchrones bloquants glGetFloat/glGetInteger par frame dans ActiveRenderInfo
 *   et ClippingHelperImpl (qui vidaient la file mesa_glthread=true 5 fois par frame sous Vulkan/Zink et OpenGL !).
 * - Coût hors setupCameraTransform : 1 simple test de champ statique booléen (false 99,99 % de la frame).
 */
public final class FastCameraMatrices {

    public static boolean recording = false;
    private static boolean valid = false;
    private static boolean sawProjectionInit = false;
    private static boolean sawModelviewInit = false;

    private static int currentMode = GL11.GL_MODELVIEW;

    private static final float[] PROJECTION = new float[16];
    private static final float[] MODELVIEW = new float[16];
    private static final float[] SCRATCH = new float[16];
    private static final float[] TMP_MUL = new float[16];

    // Mini pile push/pop (profondeur 8, amplement suffisante durant setupCameraTransform)
    private static final int STACK_DEPTH = 8;
    private static final float[][] PROJ_STACK = new float[STACK_DEPTH][16];
    private static final float[][] MV_STACK = new float[STACK_DEPTH][16];
    private static int projSp = 0;
    private static int mvSp = 0;

    private static int vpX = 0;
    private static int vpY = 0;
    private static int vpW = 1920;
    private static int vpH = 1080;

    static {
        setIdentity(PROJECTION);
        setIdentity(MODELVIEW);
    }

    private FastCameraMatrices() {
    }

    public static void setViewport(int x, int y, int width, int height) {
        vpX = x;
        vpY = y;
        vpW = width;
        vpH = height;
    }

    public static void beginCameraSetup(int initialMatrixMode) {
        currentMode = initialMatrixMode;
        projSp = 0;
        mvSp = 0;
        sawProjectionInit = false;
        sawModelviewInit = false;
        valid = false;
        recording = true;
    }

    public static void endCameraSetup() {
        recording = false;
        valid = sawProjectionInit && sawModelviewInit;
    }

    public static boolean isValid() {
        return valid;
    }

    public static void invalidate() {
        recording = false;
        valid = false;
    }

    public static void onMatrixMode(int mode) {
        currentMode = mode;
    }

    private static float[] activeMatrix() {
        if (currentMode == GL11.GL_PROJECTION) {
            return PROJECTION;
        }
        if (currentMode == GL11.GL_MODELVIEW) {
            return MODELVIEW;
        }
        return null;
    }

    public static void onLoadIdentity() {
        if (currentMode == GL11.GL_PROJECTION) {
            setIdentity(PROJECTION);
            sawProjectionInit = true;
        } else if (currentMode == GL11.GL_MODELVIEW) {
            setIdentity(MODELVIEW);
            sawModelviewInit = true;
        }
    }

    public static void onPushMatrix() {
        if (currentMode == GL11.GL_PROJECTION) {
            if (projSp < STACK_DEPTH) {
                System.arraycopy(PROJECTION, 0, PROJ_STACK[projSp++], 0, 16);
            } else {
                valid = false;
                sawProjectionInit = false;
            }
        } else if (currentMode == GL11.GL_MODELVIEW) {
            if (mvSp < STACK_DEPTH) {
                System.arraycopy(MODELVIEW, 0, MV_STACK[mvSp++], 0, 16);
            } else {
                valid = false;
                sawModelviewInit = false;
            }
        }
    }

    public static void onPopMatrix() {
        if (currentMode == GL11.GL_PROJECTION) {
            if (projSp > 0) {
                System.arraycopy(PROJ_STACK[--projSp], 0, PROJECTION, 0, 16);
            }
        } else if (currentMode == GL11.GL_MODELVIEW) {
            if (mvSp > 0) {
                System.arraycopy(MV_STACK[--mvSp], 0, MODELVIEW, 0, 16);
            }
        }
    }

    public static void onTranslate(float x, float y, float z) {
        float[] m = activeMatrix();
        if (m == null) {
            return;
        }
        m[12] += m[0] * x + m[4] * y + m[8] * z;
        m[13] += m[1] * x + m[5] * y + m[9] * z;
        m[14] += m[2] * x + m[6] * y + m[10] * z;
        m[15] += m[3] * x + m[7] * y + m[11] * z;
    }

    public static void onScale(float x, float y, float z) {
        float[] m = activeMatrix();
        if (m == null) {
            return;
        }
        m[0] *= x; m[1] *= x; m[2] *= x; m[3] *= x;
        m[4] *= y; m[5] *= y; m[6] *= y; m[7] *= y;
        m[8] *= z; m[9] *= z; m[10] *= z; m[11] *= z;
    }

    public static void onRotate(float angleDeg, float x, float y, float z) {
        float[] m = activeMatrix();
        if (m == null || angleDeg == 0.0F) {
            return;
        }
        float lenSq = x * x + y * y + z * z;
        if (lenSq <= 1.0E-12F) {
            return;
        }
        float invLen = (lenSq == 1.0F) ? 1.0F : (float) (1.0D / Math.sqrt(lenSq));
        float ux = x * invLen;
        float uy = y * invLen;
        float uz = z * invLen;

        double rad = Math.toRadians(angleDeg);
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        float omc = 1.0F - c;

        float[] r = SCRATCH;
        r[0] = ux * ux * omc + c;
        r[1] = uy * ux * omc + uz * s;
        r[2] = uz * ux * omc - uy * s;
        r[3] = 0.0F;

        r[4] = ux * uy * omc - uz * s;
        r[5] = uy * uy * omc + c;
        r[6] = uz * uy * omc + ux * s;
        r[7] = 0.0F;

        r[8] = ux * uz * omc + uy * s;
        r[9] = uy * uz * omc - ux * s;
        r[10] = uz * uz * omc + c;
        r[11] = 0.0F;

        r[12] = 0.0F;
        r[13] = 0.0F;
        r[14] = 0.0F;
        r[15] = 1.0F;

        multiplyInPlace(m, r);
    }

    public static void onPerspective(float fovy, float aspect, float zNear, float zFar) {
        float[] m = activeMatrix();
        if (m == null) {
            return;
        }
        float radians = (float) Math.toRadians(fovy * 0.5F);
        float deltaZ = zFar - zNear;
        float sine = (float) Math.sin(radians);
        if (deltaZ == 0.0F || sine == 0.0F || aspect == 0.0F) {
            return;
        }
        float cotangent = (float) Math.cos(radians) / sine;
        float[] p = SCRATCH;
        for (int i = 0; i < 16; i++) {
            p[i] = 0.0F;
        }
        p[0] = cotangent / aspect;
        p[5] = cotangent;
        p[10] = -(zFar + zNear) / deltaZ;
        p[11] = -1.0F;
        p[14] = -2.0F * zNear * zFar / deltaZ;
        multiplyInPlace(m, p);
    }

    public static void onMultMatrix(FloatBuffer buf) {
        float[] m = activeMatrix();
        if (m == null || buf == null) {
            return;
        }
        int pos = buf.position();
        if (buf.remaining() < 16) {
            return;
        }
        float[] r = SCRATCH;
        for (int i = 0; i < 16; i++) {
            r[i] = buf.get(pos + i);
        }
        multiplyInPlace(m, r);
    }

    public static void onOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        float[] m = activeMatrix();
        if (m == null) {
            return;
        }
        double rl = right - left;
        double tb = top - bottom;
        double fn = zFar - zNear;
        if (rl == 0.0D || tb == 0.0D || fn == 0.0D) {
            return;
        }
        float[] o = SCRATCH;
        for (int i = 0; i < 16; i++) {
            o[i] = 0.0F;
        }
        o[0] = (float) (2.0D / rl);
        o[5] = (float) (2.0D / tb);
        o[10] = (float) (-2.0D / fn);
        o[12] = (float) (-(right + left) / rl);
        o[13] = (float) (-(top + bottom) / tb);
        o[14] = (float) (-(zFar + zNear) / fn);
        o[15] = 1.0F;
        multiplyInPlace(m, o);
    }

    public static void copyModelviewTo(FloatBuffer dst) {
        int pos = dst.position();
        for (int i = 0; i < 16; i++) {
            dst.put(pos + i, MODELVIEW[i]);
        }
    }

    public static void copyProjectionTo(FloatBuffer dst) {
        int pos = dst.position();
        for (int i = 0; i < 16; i++) {
            dst.put(pos + i, PROJECTION[i]);
        }
    }

    public static void copyViewportTo(IntBuffer dst) {
        int pos = dst.position();
        dst.put(pos, vpX);
        dst.put(pos + 1, vpY);
        dst.put(pos + 2, vpW);
        dst.put(pos + 3, vpH);
    }

    public static float[] getProjectionArrayForTest() {
        return PROJECTION.clone();
    }

    public static float[] getModelviewArrayForTest() {
        return MODELVIEW.clone();
    }

    private static void setIdentity(float[] m) {
        for (int i = 0; i < 16; i++) {
            m[i] = 0.0F;
        }
        m[0] = 1.0F;
        m[5] = 1.0F;
        m[10] = 1.0F;
        m[15] = 1.0F;
    }

    private static void multiplyInPlace(float[] a, float[] b) {
        float[] out = TMP_MUL;
        for (int col = 0; col < 4; col++) {
            int c4 = col << 2;
            float b0 = b[c4];
            float b1 = b[c4 + 1];
            float b2 = b[c4 + 2];
            float b3 = b[c4 + 3];
            out[c4] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3;
            out[c4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3;
            out[c4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3;
            out[c4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3;
        }
        System.arraycopy(out, 0, a, 0, 16);
    }
}

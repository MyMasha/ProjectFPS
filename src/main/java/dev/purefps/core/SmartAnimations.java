package dev.purefps.core;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.opengl.Display;

import java.util.List;

/**
 * Smart Animations v5.0 — Piloté par bitmask 128 bits par CompiledChunk :
 * - Attribue un indice séquentiel (0..127) à chaque sprite animé de TextureMap lors du chargement de l'atlas.
 * - Accumule en ThreadLocal le bitmask exact des textures animées présentes dans chaque section lors du meshing
 *   (compatible AO / BALANCED, Standard / FPS, et BlockFluidRenderer eau/lave).
 * - Agrège à chaque frame par simple OR binaire (~1 ns/chunk) uniquement sur les chunks réellement rendus :
 *   aucune expiration après 16 s même immobile, aucun String.contains par tick, et court-circuit avant bindTexture
 *   si aucun sprite animé n'est visible à l'écran !
 */
public final class SmartAnimations {

    private static final int UV_GRID_DIM = 128;
    private static final int UV_GRID_MASK = UV_GRID_DIM - 1;
    private static final int MAX_BITS = 128;
    private static final int OVERFLOW_BIT = 127;

    private static final class BuildState {
        boolean building;
        long mask0;
        long mask1;
    }

    private static final ThreadLocal<BuildState> BUILD_STATE = new ThreadLocal<BuildState>() {
        @Override
        protected BuildState initialValue() {
            return new BuildState();
        }
    };

    private static volatile byte[] uvAnimGrid = new byte[UV_GRID_DIM * UV_GRID_DIM];
    private static volatile long waterMask0 = 0L;
    private static volatile long waterMask1 = 0L;
    private static volatile long lavaMask0 = 0L;
    private static volatile long lavaMask1 = 0L;
    private static volatile boolean hasSpecialDynamicSprites = false;

    private static int indexedCount = -1;
    private static TextureAtlasSprite indexedFirst = null;

    // État mis à jour sur le thread de rendu / tick client
    private static long accumulatedMask0 = 0L;
    private static long accumulatedMask1 = 0L;
    private static boolean hasFreshFrameData = false;

    private static long activeMask0 = 0L;
    private static long activeMask1 = 0L;
    private static int animationTick = 0;
    private static boolean skipAllThisTick = false;

    private SmartAnimations() {
    }

    public static void syncAnimatedSprites(List<TextureAtlasSprite> sprites) {
        if (sprites == null || sprites.isEmpty()) {
            return;
        }
        TextureAtlasSprite first = sprites.get(0);
        if (sprites.size() == indexedCount && first == indexedFirst) {
            return;
        }

        byte[] grid = new byte[UV_GRID_DIM * UV_GRID_DIM];
        long wMask0 = 0L;
        long wMask1 = 0L;
        long lMask0 = 0L;
        long lMask1 = 0L;
        boolean special = false;
        int nextIdx = 0;

        for (int i = 0, n = sprites.size(); i < n; i++) {
            TextureAtlasSprite sp = sprites.get(i);
            if (sp == null) {
                continue;
            }
            if (sp.getClass() != TextureAtlasSprite.class) {
                special = true;
                if (sp instanceof AnimatedSpriteAccess) {
                    ((AnimatedSpriteAccess) sp).purefps$setAnimIndex(-1);
                }
                continue;
            }
            int bitIdx = (nextIdx < OVERFLOW_BIT) ? nextIdx++ : OVERFLOW_BIT;
            if (sp instanceof AnimatedSpriteAccess) {
                ((AnimatedSpriteAccess) sp).purefps$setAnimIndex(bitIdx);
            }

            String name = sp.getIconName();
            if (name != null) {
                if (name.contains("water_")) {
                    if (bitIdx < 64) {
                        wMask0 |= (1L << bitIdx);
                    } else {
                        wMask1 |= (1L << (bitIdx - 64));
                    }
                } else if (name.contains("lava_")) {
                    if (bitIdx < 64) {
                        lMask0 |= (1L << bitIdx);
                    } else {
                        lMask1 |= (1L << (bitIdx - 64));
                    }
                }
            }

            byte encoded = (byte) (bitIdx + 1); // 1..128 (interprété non signé & 0xFF)
            int x0 = clampGrid((int) Math.floor(sp.getMinU() * UV_GRID_DIM));
            int x1 = clampGrid((int) Math.floor(Math.max(sp.getMinU(), sp.getMaxU() - 1e-5F) * UV_GRID_DIM));
            int y0 = clampGrid((int) Math.floor(sp.getMinV() * UV_GRID_DIM));
            int y1 = clampGrid((int) Math.floor(Math.max(sp.getMinV(), sp.getMaxV() - 1e-5F) * UV_GRID_DIM));
            for (int gy = y0; gy <= y1; gy++) {
                int row = gy << 7;
                for (int gx = x0; gx <= x1; gx++) {
                    grid[row | gx] = encoded;
                }
            }
        }

        uvAnimGrid = grid;
        waterMask0 = wMask0;
        waterMask1 = wMask1;
        lavaMask0 = lMask0;
        lavaMask1 = lMask1;
        hasSpecialDynamicSprites = special;
        indexedCount = sprites.size();
        indexedFirst = first;
    }

    private static int clampGrid(int v) {
        return v < 0 ? 0 : (v > UV_GRID_MASK ? UV_GRID_MASK : v);
    }

    public static void beginChunkBuild() {
        BuildState st = BUILD_STATE.get();
        st.building = true;
        st.mask0 = 0L;
        st.mask1 = 0L;
    }

    public static void finishChunkBuild(CompiledChunk compiled) {
        BuildState st = BUILD_STATE.get();
        long m0 = st.mask0;
        long m1 = st.mask1;
        st.building = false;
        st.mask0 = 0L;
        st.mask1 = 0L;
        if (compiled instanceof CompiledChunkAnimAccess) {
            ((CompiledChunkAnimAccess) compiled).purefps$setAnimMasks(m0, m1);
        }
    }

    public static void abortChunkBuild() {
        BuildState st = BUILD_STATE.get();
        st.building = false;
        st.mask0 = 0L;
        st.mask1 = 0L;
    }

    public static void markSpriteVisible(TextureAtlasSprite sprite) {
        if (sprite == null || !(sprite instanceof AnimatedSpriteAccess)) {
            return;
        }
        int idx = ((AnimatedSpriteAccess) sprite).purefps$getAnimIndex();
        if (idx >= 0 && idx < MAX_BITS) {
            recordBitIndex(idx);
        }
    }

    public static void markLiquidBlock(Block block) {
        if (block == null) {
            return;
        }
        Material mat = block.getMaterial();
        long m0;
        long m1;
        if (mat == Material.water) {
            m0 = waterMask0;
            m1 = waterMask1;
        } else if (mat == Material.lava) {
            m0 = lavaMask0;
            m1 = lavaMask1;
        } else {
            return;
        }
        if ((m0 | m1) == 0L) {
            return;
        }
        BuildState st = BUILD_STATE.get();
        if (st.building) {
            st.mask0 |= m0;
            st.mask1 |= m1;
        } else {
            accumulatedMask0 |= m0;
            accumulatedMask1 |= m1;
            hasFreshFrameData = true;
        }
    }

    public static void markQuadsVisible(List<BakedQuad> quads) {
        if (quads == null || quads.isEmpty()) {
            return;
        }
        byte[] grid = uvAnimGrid;
        long m0 = 0L;
        long m1 = 0L;
        for (int i = 0, n = quads.size(); i < n; i++) {
            BakedQuad q = quads.get(i);
            if (q == null) {
                continue;
            }
            int[] vd = q.getVertexData();
            if (vd != null && vd.length >= 26) {
                float u = 0.5F * (Float.intBitsToFloat(vd[4]) + Float.intBitsToFloat(vd[18]));
                float v = 0.5F * (Float.intBitsToFloat(vd[5]) + Float.intBitsToFloat(vd[19]));
                int gx = ((int) (u * UV_GRID_DIM)) & UV_GRID_MASK;
                int gy = ((int) (v * UV_GRID_DIM)) & UV_GRID_MASK;
                int encoded = grid[(gy << 7) | gx] & 0xFF;
                if (encoded != 0) {
                    int bitIdx = encoded - 1;
                    if (bitIdx < 64) {
                        m0 |= (1L << bitIdx);
                    } else {
                        m1 |= (1L << (bitIdx - 64));
                    }
                }
            }
        }
        if ((m0 | m1) != 0L) {
            BuildState st = BUILD_STATE.get();
            if (st.building) {
                st.mask0 |= m0;
                st.mask1 |= m1;
            } else {
                accumulatedMask0 |= m0;
                accumulatedMask1 |= m1;
                hasFreshFrameData = true;
            }
        }
    }

    /**
     * Agrège les bitmasks d'un BakedModel d'item lors du rendu mis en cache (FastItemModelCache).
     */
    public static void collectQuadsMask(List<BakedQuad> quads, long[] outMasks) {
        if (quads == null || quads.isEmpty()) {
            return;
        }
        byte[] grid = uvAnimGrid;
        long m0 = outMasks[0];
        long m1 = outMasks[1];
        for (int i = 0, n = quads.size(); i < n; i++) {
            BakedQuad q = quads.get(i);
            if (q == null) {
                continue;
            }
            int[] vd = q.getVertexData();
            if (vd != null && vd.length >= 26) {
                float u = 0.5F * (Float.intBitsToFloat(vd[4]) + Float.intBitsToFloat(vd[18]));
                float v = 0.5F * (Float.intBitsToFloat(vd[5]) + Float.intBitsToFloat(vd[19]));
                int gx = ((int) (u * UV_GRID_DIM)) & UV_GRID_MASK;
                int gy = ((int) (v * UV_GRID_DIM)) & UV_GRID_MASK;
                int encoded = grid[(gy << 7) | gx] & 0xFF;
                if (encoded != 0) {
                    int bitIdx = encoded - 1;
                    if (bitIdx < 64) {
                        m0 |= (1L << bitIdx);
                    } else {
                        m1 |= (1L << (bitIdx - 64));
                    }
                }
            }
        }
        outMasks[0] = m0;
        outMasks[1] = m1;
    }

    public static void recordVisibleMasks(long mask0, long mask1) {
        if ((mask0 | mask1) != 0L) {
            accumulatedMask0 |= mask0;
            accumulatedMask1 |= mask1;
            hasFreshFrameData = true;
        }
    }

    public static void markCompiledChunkVisible(CompiledChunk compiled) {
        if (compiled instanceof CompiledChunkAnimAccess) {
            CompiledChunkAnimAccess acc = (CompiledChunkAnimAccess) compiled;
            long m0 = acc.purefps$getAnimMask0();
            long m1 = acc.purefps$getAnimMask1();
            if ((m0 | m1) != 0L) {
                accumulatedMask0 |= m0;
                accumulatedMask1 |= m1;
            }
        }
    }

    public static void onFrameStart() {
        hasFreshFrameData = true;
    }

    private static void recordBitIndex(int bitIdx) {
        if (bitIdx < 64) {
            accumulatedMask0 |= (1L << bitIdx);
        } else {
            accumulatedMask1 |= (1L << (bitIdx - 64));
        }
        hasFreshFrameData = true;
    }

    public static void beginTick() {
        animationTick++;
        if (hasFreshFrameData) {
            activeMask0 = accumulatedMask0;
            activeMask1 = accumulatedMask1;
            accumulatedMask0 = 0L;
            accumulatedMask1 = 0L;
            hasFreshFrameData = false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        skipAllThisTick = (mc == null || mc.theWorld == null || mc.isGamePaused() || !Display.isActive());
    }

    /**
     * Méthode pure testable sans Minecraft/Display : avance d'un tick avec le bitmask courant.
     */
    static void beginTickForTest(boolean pausedOrInactive) {
        animationTick++;
        if (hasFreshFrameData) {
            activeMask0 = accumulatedMask0;
            activeMask1 = accumulatedMask1;
            accumulatedMask0 = 0L;
            accumulatedMask1 = 0L;
            hasFreshFrameData = false;
        }
        skipAllThisTick = pausedOrInactive;
    }

    static boolean isBitActiveForTest(int bitIdx) {
        if (skipAllThisTick || bitIdx < 0 || bitIdx >= MAX_BITS) {
            return false;
        }
        return (bitIdx < 64)
                ? ((activeMask0 & (1L << bitIdx)) != 0L)
                : ((activeMask1 & (1L << (bitIdx - 64))) != 0L);
    }

    static void resetForTest() {
        accumulatedMask0 = 0L;
        accumulatedMask1 = 0L;
        activeMask0 = 0L;
        activeMask1 = 0L;
        hasFreshFrameData = false;
        animationTick = 0;
        skipAllThisTick = false;
        hasSpecialDynamicSprites = false;
    }

    public static boolean hasAnyAnimationThisTick() {
        return !skipAllThisTick && ((activeMask0 | activeMask1) != 0L || hasSpecialDynamicSprites);
    }

    public static boolean shouldAnimateThisTick(TextureAtlasSprite sprite) {
        if (skipAllThisTick) {
            return false;
        }
        if (sprite == null || sprite.getClass() != TextureAtlasSprite.class) {
            // Garde toujours TextureCompass et TextureClock à jour
            return true;
        }
        if (sprite instanceof AnimatedSpriteAccess) {
            int bitIdx = ((AnimatedSpriteAccess) sprite).purefps$getAnimIndex();
            if (bitIdx < 0) {
                return true;
            }
            boolean visible = (bitIdx < 64)
                    ? ((activeMask0 & (1L << bitIdx)) != 0L)
                    : ((activeMask1 & (1L << (bitIdx - 64))) != 0L);
            if (!visible) {
                return false;
            }
        }
        if ((animationTick & 1) != 0 && (sprite.getIconWidth() >= 64 || sprite.getIconHeight() >= 64)) {
            return false;
        }
        return true;
    }
}

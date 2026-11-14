package dev.purefps.core;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.opengl.Display;

import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Smart Animations v4.0 — Piloté par la visibilité réelle des sprites (MashClient 4.0) :
 * - Indexe les sprites animés de TextureMap dans une grille UV 128x128 O(1) (compatible 1.8.9 BakedQuad.getVertexData)
 *   et suit séparément l'eau et la lave compilées par BlockFluidRenderer.
 * - Élimine 100 % des interpolations CPU et uploads glTexSubImage2D (5 niveaux mipmap chacun) pour la lave,
 *   l'eau, le feu, les portails, la prismarine et les lanternes aquatiques absents des chunks proches !
 */
public final class SmartAnimations {

    private static final int TABLE_SIZE = 1024;
    private static final int TABLE_MASK = TABLE_SIZE - 1;
    private static final int UV_GRID_DIM = 128;
    private static final int UV_GRID_MASK = UV_GRID_DIM - 1;

    private static final AtomicIntegerArray LAST_SEEN_EPOCH = new AtomicIntegerArray(TABLE_SIZE);
    private static volatile short[] uvSlotGrid = new short[UV_GRID_DIM * UV_GRID_DIM];
    private static int indexedCount = -1;
    private static TextureAtlasSprite indexedFirst = null;

    private static volatile int currentEpoch = 10;
    private static volatile int waterSeenEpoch = 0;
    private static volatile int lavaSeenEpoch = 0;
    private static int animationTick = 0;
    private static boolean skipAllThisTick = false;

    private SmartAnimations() {
    }

    private static int slotForSprite(TextureAtlasSprite sprite) {
        int h = sprite.getOriginX() * 73471 + sprite.getOriginY() * 19349663;
        return (h ^ (h >>> 12)) & TABLE_MASK;
    }

    public static void syncAnimatedSprites(List<TextureAtlasSprite> sprites) {
        if (sprites == null || sprites.isEmpty()) {
            return;
        }
        TextureAtlasSprite first = sprites.get(0);
        if (sprites.size() == indexedCount && first == indexedFirst) {
            return;
        }
        short[] grid = new short[UV_GRID_DIM * UV_GRID_DIM];
        for (int i = 0, n = sprites.size(); i < n; i++) {
            TextureAtlasSprite sp = sprites.get(i);
            if (sp == null) {
                continue;
            }
            short encodedSlot = (short) (slotForSprite(sp) + 1);
            int x0 = clampGrid((int) Math.floor(sp.getMinU() * UV_GRID_DIM));
            int x1 = clampGrid((int) Math.floor(Math.max(sp.getMinU(), sp.getMaxU() - 1e-5F) * UV_GRID_DIM));
            int y0 = clampGrid((int) Math.floor(sp.getMinV() * UV_GRID_DIM));
            int y1 = clampGrid((int) Math.floor(Math.max(sp.getMinV(), sp.getMaxV() - 1e-5F) * UV_GRID_DIM));
            for (int gy = y0; gy <= y1; gy++) {
                int row = gy << 7;
                for (int gx = x0; gx <= x1; gx++) {
                    grid[row | gx] = encodedSlot;
                }
            }
        }
        uvSlotGrid = grid;
        indexedCount = sprites.size();
        indexedFirst = first;
    }

    private static int clampGrid(int v) {
        return v < 0 ? 0 : (v > UV_GRID_MASK ? UV_GRID_MASK : v);
    }

    public static void markSpriteVisible(TextureAtlasSprite sprite) {
        if (sprite != null && sprite.hasAnimationMetadata()) {
            LAST_SEEN_EPOCH.lazySet(slotForSprite(sprite), currentEpoch);
        }
    }

    public static void markLiquidBlock(Block block) {
        if (block == null) {
            return;
        }
        Material mat = block.getMaterial();
        if (mat == Material.water) {
            waterSeenEpoch = currentEpoch;
        } else if (mat == Material.lava) {
            lavaSeenEpoch = currentEpoch;
        }
    }

    public static void markQuadsVisible(List<BakedQuad> quads) {
        if (quads == null || quads.isEmpty()) {
            return;
        }
        short[] grid = uvSlotGrid;
        int epoch = currentEpoch;
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
                int encoded = grid[(gy << 7) | gx] & 0xFFFF;
                if (encoded != 0) {
                    LAST_SEEN_EPOCH.lazySet(encoded - 1, epoch);
                }
            }
        }
    }

    public static void beginTick() {
        animationTick++;
        if ((animationTick & 63) == 0) {
            currentEpoch++;
        }
        Minecraft mc = Minecraft.getMinecraft();
        skipAllThisTick = (mc == null || mc.theWorld == null || mc.isGamePaused() || !Display.isActive());
    }

    public static boolean shouldAnimateThisTick(TextureAtlasSprite sprite) {
        if (skipAllThisTick) {
            return false;
        }
        if (sprite == null || sprite.getClass() != TextureAtlasSprite.class) {
            // Garde toujours TextureCompass et TextureClock à jour
            return true;
        }
        String name = sprite.getIconName();
        if (name != null) {
            if (name.contains("water_")) {
                if (currentEpoch - waterSeenEpoch > 4) {
                    return false;
                }
                return (animationTick & 1) == 0 || sprite.getIconWidth() < 64;
            }
            if (name.contains("lava_")) {
                if (currentEpoch - lavaSeenEpoch > 4) {
                    return false;
                }
                return (animationTick & 1) == 0 || sprite.getIconWidth() < 64;
            }
        }
        int seen = LAST_SEEN_EPOCH.get(slotForSprite(sprite));
        if (currentEpoch - seen > 4) {
            return false;
        }
        if ((animationTick & 1) != 0 && (sprite.getIconWidth() >= 64 || sprite.getIconHeight() >= 64)) {
            return false;
        }
        return true;
    }
}

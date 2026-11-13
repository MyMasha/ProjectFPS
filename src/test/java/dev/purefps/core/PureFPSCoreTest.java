package dev.purefps.core;

import dev.purefps.client.CpsTracker;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.BitSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

public class PureFPSCoreTest {

    @Test
    public void testFastTrigAccuracy() {
        for (int deg = -1440; deg <= 1440; deg++) {
            float rad = (float) Math.toRadians(deg);
            assertEquals("Sin mismatch at " + deg, (float) Math.sin(rad), FastTrig.sin(rad), 0.002f);
            assertEquals("Cos mismatch at " + deg, (float) Math.cos(rad), FastTrig.cos(rad), 0.002f);
        }
        assertEquals(0.0f, FastTrig.sin(0.0f), 1e-6f);
        assertEquals(1.0f, FastTrig.cos(0.0f), 1e-6f);

        for (double y = -5.0; y <= 5.0; y += 0.37) {
            for (double x = -5.0; x <= 5.0; x += 0.37) {
                if (x == 0.0 && y == 0.0) continue;
                assertEquals("Atan2 mismatch", Math.atan2(y, x), FastTrig.fastAtan2(y, x), 0.002);
            }
        }
    }

    @Test
    public void testPrimitiveQuadSorterDescendingOrder() {
        int quadCount = 64;
        int vertexIntSize = 7;
        int quadIntStride = vertexIntSize * 4;
        ByteBuffer bb = ByteBuffer.allocateDirect(quadCount * quadIntStride * 4).order(ByteOrder.nativeOrder());
        IntBuffer ib = bb.asIntBuffer();
        FloatBuffer fb = bb.asFloatBuffer();

        for (int i = 0; i < quadCount; i++) {
            int base = i * quadIntStride;
            for (int v = 0; v < 4; v++) {
                int vBase = base + v * vertexIntSize;
                fb.put(vBase, 0.0f);
                fb.put(vBase + 1, 0.0f);
                fb.put(vBase + 2, (float) i);
                ib.put(vBase + 3, 1000 + i);
            }
        }

        PrimitiveQuadSorter.sortQuads(ib, fb, quadCount * 4, vertexIntSize, 0.0f, 0.0f, 0.0f);

        for (int i = 0; i < quadCount; i++) {
            int expectedZ = (quadCount - 1) - i;
            int base = i * quadIntStride;
            assertEquals((float) expectedZ, fb.get(base + 2), 1e-5f);
            assertEquals(1000 + expectedZ, ib.get(base + 3));
        }
    }

    @Test
    public void testFastVisGraphBFS() {
        BitSet emptyChunk = new BitSet(4096);
        Set<EnumFacing> faces = FastVisGraphBFS.floodFill(emptyChunk, 0);
        assertEquals(6, faces.size());

        BitSet walledChunk = new BitSet(4096);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                int idx = 8 | (y << 8) | (z << 4);
                walledChunk.set(idx, true);
            }
        }
        Set<EnumFacing> westSideFaces = FastVisGraphBFS.floodFill(walledChunk, 0);
        assertTrue(westSideFaces.contains(EnumFacing.WEST));
        assertTrue(westSideFaces.contains(EnumFacing.UP));
        assertTrue(westSideFaces.contains(EnumFacing.DOWN));
        assertTrue(westSideFaces.contains(EnumFacing.NORTH));
        assertTrue(westSideFaces.contains(EnumFacing.SOUTH));
        assertFalse("EAST face must be occluded by solid wall at x=8", westSideFaces.contains(EnumFacing.EAST));
    }

    @Test
    public void testFastFontFormatLookupWidthCacheAndDinnerbone() {
        String alphabet = "0123456789abcdefklmnor";
        for (char c = 0; c < 256; c++) {
            int expected = alphabet.indexOf(String.valueOf(c).toLowerCase(Locale.ENGLISH).charAt(0));
            assertEquals("Mismatch for char code " + (int) c, expected, FastFontCaches.lookupFormatIndex(c));
        }

        FastFontCaches.WidthCache cache = new FastFontCaches.WidthCache();
        assertEquals(-1, cache.get("ScoreboardLine1"));
        cache.put("ScoreboardLine1", 84);
        cache.put("§aFPS: 1250", 62);
        assertEquals(84, cache.get("ScoreboardLine1"));
        assertEquals(62, cache.get("§aFPS: 1250"));
        cache.clear();
        assertEquals(-1, cache.get("ScoreboardLine1"));

        assertNull(FastFontCaches.resolveDinnerboneToken(null));
        assertEquals("", FastFontCaches.resolveDinnerboneToken(""));
        assertEquals("", FastFontCaches.resolveDinnerboneToken("§b[MVP§c+§b] Technoblade"));
        assertEquals("", FastFontCaches.resolveDinnerboneToken("§e§lCLIQUEZ POUR JOUER"));
        assertEquals("", FastFontCaches.resolveDinnerboneToken("DinnerboneExtra"));
        assertEquals("", FastFontCaches.resolveDinnerboneToken("Grumm2"));
        assertEquals("Dinnerbone", FastFontCaches.resolveDinnerboneToken("Dinnerbone"));
        assertEquals("Dinnerbone", FastFontCaches.resolveDinnerboneToken("§a§lDinnerbone§r"));
        assertEquals("Grumm", FastFontCaches.resolveDinnerboneToken("Grumm"));
        assertEquals("Grumm", FastFontCaches.resolveDinnerboneToken("§6Gr§eumm§r"));
    }

    @Test
    public void testProfilesFpsBalancedQualityAndCustomDetection() {
        PureFPSConfig.applyProfile(PureFPSProfile.FPS, false);
        assertEquals(PureFPSProfile.FPS, PureFPSConfig.activeProfile);
        assertEquals(48, PureFPSConfig.maxEntityRenderDistance);
        assertEquals(250, PureFPSConfig.maxParticlesPerLayer);
        assertTrue(PureFPSConfig.skipClientSkylightUpdates);
        PureFPSConfig.refreshProfileAfterManualEdit();
        assertEquals(PureFPSProfile.FPS, PureFPSConfig.activeProfile);

        PureFPSConfig.applyProfile(PureFPSProfile.QUALITY, false);
        assertEquals(PureFPSProfile.QUALITY, PureFPSConfig.activeProfile);
        assertFalse(PureFPSConfig.entityOcclusionCulling);
        assertFalse(PureFPSConfig.fastDroppedItems);
        assertEquals(4000, PureFPSConfig.maxParticlesPerLayer);
        assertTrue("Lossless skull cache stays enabled in QUALITY", PureFPSConfig.fastSkullRendering);
        PureFPSConfig.refreshProfileAfterManualEdit();
        assertEquals(PureFPSProfile.QUALITY, PureFPSConfig.activeProfile);

        // Modification manuelle -> bascule automatiquement sur CUSTOM
        PureFPSConfig.maxParticlesPerLayer = 500;
        PureFPSConfig.refreshProfileAfterManualEdit();
        assertEquals(PureFPSProfile.CUSTOM, PureFPSConfig.activeProfile);

        // Retour au profil par défaut BALANCED
        PureFPSConfig.applyProfile(PureFPSProfile.BALANCED, false);
        assertEquals(PureFPSProfile.BALANCED, PureFPSConfig.activeProfile);
        assertEquals(64, PureFPSConfig.maxEntityRenderDistance);
        assertEquals(1000, PureFPSConfig.maxParticlesPerLayer);
        assertTrue(PureFPSConfig.regionMultiDraw);
        assertTrue(PureFPSConfig.asyncSkinUploadPacing);
        assertTrue(PureFPSConfig.fastScoreboard);
    }

    @Test
    public void testFreeRangeAllocatorCoalescingAndTailTrim() {
        assertEquals(0, FreeRangeAllocator.alignVertices(0));
        assertEquals(64, FreeRangeAllocator.alignVertices(1));
        assertEquals(64, FreeRangeAllocator.alignVertices(64));
        assertEquals(128, FreeRangeAllocator.alignVertices(65));

        FreeRangeAllocator alloc = new FreeRangeAllocator();
        int a = alloc.allocate(64);   // [0..64)
        int b = alloc.allocate(128);  // [64..192)
        int c = alloc.allocate(64);   // [192..256)
        int d = alloc.allocate(64);   // [256..320)
        assertEquals(0, a);
        assertEquals(64, b);
        assertEquals(192, c);
        assertEquals(256, d);
        assertEquals(320, alloc.getHighWaterMark());
        assertEquals(4, alloc.getLiveAllocations());

        // Libération de B puis C -> fusion gauche/droite en [64..256)
        alloc.free(b, 128);
        assertEquals(1, alloc.getFreeBlockCount());
        alloc.free(c, 64);
        assertEquals(1, alloc.getFreeBlockCount());

        // Réallocation partielle first-fit de 64 sommets -> prend [64..128), laisse [128..256) libre
        int e = alloc.allocate(64);
        assertEquals(64, e);
        assertEquals(1, alloc.getFreeBlockCount());

        // Libération de D (fin de tampon [256..320)) -> fusionne avec [128..256) et réduit highWaterMark à 128 !
        alloc.free(d, 64);
        assertEquals(128, alloc.getHighWaterMark());
        assertEquals(0, alloc.getFreeBlockCount());

        // Libération des deux derniers blocs -> retour à 0
        alloc.free(e, 64);
        alloc.free(a, 64);
        assertEquals(0, alloc.getLiveAllocations());
        assertEquals(0, alloc.getHighWaterMark());
        assertEquals(0, alloc.getFreeBlockCount());
    }

    @Test
    public void testRegionKeyPackingAndVertexTransform() {
        long kPos0 = ChunkRegionManager.packRegionLayerKey(16, 32, 0);
        long kPos0SameRegion = ChunkRegionManager.packRegionLayerKey(240, 240, 0);
        long kPos1 = ChunkRegionManager.packRegionLayerKey(16, 32, 1);
        long kNeg = ChunkRegionManager.packRegionLayerKey(-16, -32, 0);

        assertEquals(kPos0, kPos0SameRegion);
        assertNotEquals(kPos0, kPos1);
        assertNotEquals(kPos0, kNeg);

        int vertexCount = 4;
        ByteBuffer src = ByteBuffer.allocateDirect(vertexCount * 28).order(ByteOrder.nativeOrder());
        ByteBuffer dst = ByteBuffer.allocateDirect(vertexCount * 28).order(ByteOrder.nativeOrder());
        IntBuffer srcInts = src.asIntBuffer();

        for (int v = 0; v < vertexCount; v++) {
            int base = v * 7;
            srcInts.put(base, Float.floatToRawIntBits(16.0f));
            srcInts.put(base + 1, Float.floatToRawIntBits(8.0f));
            srcInts.put(base + 2, Float.floatToRawIntBits(0.0f));
            srcInts.put(base + 3, 0xFFAA5511);
            srcInts.put(base + 4, Float.floatToRawIntBits(0.5f));
            srcInts.put(base + 5, Float.floatToRawIntBits(0.25f));
            srcInts.put(base + 6, 0x00F000F0);
        }

        ChunkRegionManager.transformBlockVertices(src, vertexCount, 128.0f, 64.0f, 240.0f, dst);
        assertEquals(vertexCount * 28, dst.limit());

        IntBuffer dstInts = dst.asIntBuffer();
        float expectedX = (16.0f - 8.0f) * 1.000001f + 8.0f + 128.0f;
        float expectedY = (8.0f - 8.0f) * 1.000001f + 8.0f + 64.0f;
        float expectedZ = (0.0f - 8.0f) * 1.000001f + 8.0f + 240.0f;

        for (int v = 0; v < vertexCount; v++) {
            int base = v * 7;
            assertEquals(expectedX, Float.intBitsToFloat(dstInts.get(base)), 1e-5f);
            assertEquals(expectedY, Float.intBitsToFloat(dstInts.get(base + 1)), 1e-5f);
            assertEquals(expectedZ, Float.intBitsToFloat(dstInts.get(base + 2)), 1e-5f);
            assertEquals(0xFFAA5511, dstInts.get(base + 3));
            assertEquals(0.5f, Float.intBitsToFloat(dstInts.get(base + 4)), 1e-6f);
            assertEquals(0.25f, Float.intBitsToFloat(dstInts.get(base + 5)), 1e-6f);
            assertEquals(0x00F000F0, dstInts.get(base + 6));
        }

        // Test de non-régression : réutilisation du même tampon dst (limit réduit à 4*28=112)
        // pour un second upload plus grand (32 sommets = 896 octets) sans IndexOutOfBoundsException !
        int largerCount = 32;
        ByteBuffer srcLarge = ByteBuffer.allocateDirect(largerCount * 28).order(ByteOrder.nativeOrder());
        ByteBuffer sharedStaging = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());
        ChunkRegionManager.transformBlockVertices(src, vertexCount, 0.0f, 0.0f, 0.0f, sharedStaging);
        assertEquals(vertexCount * 28, sharedStaging.limit());

        for (int v = 0; v < largerCount; v++) {
            int off = v * 28;
            srcLarge.putFloat(off, 4.0f);
            srcLarge.putFloat(off + 4, 8.0f);
            srcLarge.putFloat(off + 8, 12.0f);
            srcLarge.putInt(off + 12, 0xFF112233);
            srcLarge.putFloat(off + 16, 0.75f);
            srcLarge.putFloat(off + 20, 0.125f);
            srcLarge.putInt(off + 24, 0x00E000E0);
        }
        srcLarge.limit(largerCount * 28);

        ChunkRegionManager.transformBlockVertices(srcLarge, largerCount, 16.0f, 32.0f, 48.0f, sharedStaging);
        assertEquals(0, sharedStaging.position());
        assertEquals(largerCount * 28, sharedStaging.limit());
        assertEquals((4.0f - 8.0f) * 1.000001f + 8.0f + 16.0f, sharedStaging.getFloat((largerCount - 1) * 28), 1e-5f);
        assertEquals(0xFF112233, sharedStaging.getInt((largerCount - 1) * 28 + 12));
        assertEquals(0x00E000E0, sharedStaging.getInt((largerCount - 1) * 28 + 24));
    }

    @Test
    public void testGpuUploadBudgetCapsAndReset() {
        GpuUploadBudget.resetFrame();
        assertTrue(GpuUploadBudget.tryAcquireSkinUpload(true));
        assertFalse("In-world skin upload must cap at 1/frame", GpuUploadBudget.tryAcquireSkinUpload(true));

        GpuUploadBudget.resetFrame();
        for (int i = 0; i < 4; i++) {
            assertTrue(GpuUploadBudget.tryAcquireSkinUpload(false));
        }
        assertFalse("Menu skin upload must cap at 4/frame", GpuUploadBudget.tryAcquireSkinUpload(false));

        for (int i = 0; i < 16; i++) {
            assertTrue(GpuUploadBudget.tryAcquireRaymarch());
        }
        assertFalse("Raymarch budget must cap at 16/frame", GpuUploadBudget.tryAcquireRaymarch());

        GpuUploadBudget.resetFrame();
        assertTrue(GpuUploadBudget.tryAcquireSkinUpload(true));
        assertTrue(GpuUploadBudget.tryAcquireRaymarch());
    }

    @Test
    public void testBlockPosPoolAndLightmapStateCache() {
        BlockPos origin = new BlockPos(100, 64, -200);
        BlockPos[] generated = new BlockPos[16];
        for (int i = 0; i < 16; i++) {
            EnumFacing f = EnumCaches.FACINGS[i % 6];
            generated[i] = BlockPosPool.offset(origin, f);
        }
        for (int i = 0; i < 16; i++) {
            EnumFacing f = EnumCaches.FACINGS[i % 6];
            assertEquals(100 + f.getFrontOffsetX(), generated[i].getX());
            assertEquals(64 + f.getFrontOffsetY(), generated[i].getY());
            assertEquals(-200 + f.getFrontOffsetZ(), generated[i].getZ());
        }

        BlockPos direct = BlockPosPool.acquire(-42, 70, 999);
        assertEquals(-42, direct.getX());
        assertEquals(70, direct.getY());
        assertEquals(999, direct.getZ());

        LightmapStateCache.invalidate();
        assertFalse(LightmapStateCache.checkAndUpdate(240.0f, 240.0f));
        assertTrue(LightmapStateCache.checkAndUpdate(240.0f, 240.0f));
        assertFalse(LightmapStateCache.checkAndUpdate(120.0f, 240.0f));
        LightmapStateCache.invalidate();
        assertFalse(LightmapStateCache.checkAndUpdate(120.0f, 240.0f));
    }

    @Test
    public void testBiomeKeyPackingUniqueness() {
        long k1 = FastBiomeColors.packKey(-512, 0, 1024, FastBiomeColors.MODE_GRASS);
        long k2 = FastBiomeColors.packKey(-512, 0, 1024, FastBiomeColors.MODE_FOLIAGE);
        long k3 = FastBiomeColors.packKey(-511, 0, 1024, FastBiomeColors.MODE_GRASS);
        long k4 = FastBiomeColors.packKey(-512, 85, 1024, FastBiomeColors.MODE_GRASS);
        assertNotEquals(k1, k2);
        assertNotEquals(k1, k3);
        assertNotEquals(k1, k4);
    }

    @Test
    public void testFrustumPVertexEquivalenceWithEightCorners() {
        Random rng = new Random(0x5EED300L);
        for (int trial = 0; trial < 500; trial++) {
            double x0 = (rng.nextDouble() - 0.5D) * 200.0D;
            double y0 = (rng.nextDouble() - 0.5D) * 200.0D;
            double z0 = (rng.nextDouble() - 0.5D) * 200.0D;
            double minX = x0;
            double minY = y0;
            double minZ = z0;
            double maxX = x0 + 0.1D + rng.nextDouble() * 16.0D;
            double maxY = y0 + 0.1D + rng.nextDouble() * 16.0D;
            double maxZ = z0 + 0.1D + rng.nextDouble() * 16.0D;

            double[][] planes = new double[6][4];
            for (int p = 0; p < 6; p++) {
                planes[p][0] = (rng.nextDouble() - 0.5D) * 4.0D;
                planes[p][1] = (rng.nextDouble() - 0.5D) * 4.0D;
                planes[p][2] = (rng.nextDouble() - 0.5D) * 4.0D;
                planes[p][3] = (rng.nextDouble() - 0.5D) * 300.0D;
            }

            boolean vanillaRef = eightCornerFrustumReference(minX, minY, minZ, maxX, maxY, maxZ, planes);
            boolean pVertex = FboBlitHelper.isBoxInFrustumPVertex(
                    minX, minY, minZ, maxX, maxY, maxZ,
                    planes[0][0], planes[0][1], planes[0][2], planes[0][3],
                    planes[1][0], planes[1][1], planes[1][2], planes[1][3],
                    planes[2][0], planes[2][1], planes[2][2], planes[2][3],
                    planes[3][0], planes[3][1], planes[3][2], planes[3][3],
                    planes[4][0], planes[4][1], planes[4][2], planes[4][3],
                    planes[5][0], planes[5][1], planes[5][2], planes[5][3]
            );
            assertEquals("Mismatch at trial " + trial, vanillaRef, pVertex);
        }
    }

    private static boolean eightCornerFrustumReference(
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            double[][] f
    ) {
        for (int i = 0; i < 6; i++) {
            double px = f[i][0];
            double py = f[i][1];
            double pz = f[i][2];
            double pw = f[i][3];
            if (px * minX + py * minY + pz * minZ + pw <= 0.0D
                    && px * maxX + py * minY + pz * minZ + pw <= 0.0D
                    && px * minX + py * maxY + pz * minZ + pw <= 0.0D
                    && px * maxX + py * maxY + pz * minZ + pw <= 0.0D
                    && px * minX + py * minY + pz * maxZ + pw <= 0.0D
                    && px * maxX + py * minY + pz * maxZ + pw <= 0.0D
                    && px * minX + py * maxY + pz * maxZ + pw <= 0.0D
                    && px * maxX + py * maxY + pz * maxZ + pw <= 0.0D) {
                return false;
            }
        }
        return true;
    }

    @Test
    public void testCpsTrackerSlidingWindowAndRingWrap() {
        CpsTracker.reset();
        assertEquals(0, CpsTracker.getLeftCps(5000L));
        assertEquals(0, CpsTracker.getRightCps(5000L));

        CpsTracker.recordLeftClick(4000L);  // pile à 1000ms -> expiré à t=5000 (ts <= cutoff)
        CpsTracker.recordLeftClick(4001L);  // actif
        CpsTracker.recordLeftClick(4500L);  // actif
        CpsTracker.recordLeftClick(5000L);  // actif
        CpsTracker.recordRightClick(4800L); // actif
        assertEquals(3, CpsTracker.getLeftCps(5000L));
        assertEquals(1, CpsTracker.getRightCps(5000L));
        assertEquals(0, CpsTracker.getLeftCps(6001L));

        // Saturation et tour complet de l'anneau (> 128 clics)
        CpsTracker.reset();
        for (int i = 0; i < 150; i++) {
            CpsTracker.recordLeftClick(10_000L + i * 5L);
        }
        long latest = 10_000L + 149L * 5L; // 10_745L -> tous dans la dernière seconde, plafonné à 128
        assertEquals(128, CpsTracker.getLeftCps(latest));
        CpsTracker.reset();
    }

    @Test
    public void testPureClientV3ConfigDefaults() {
        PureFPSConfig.applyProfile(PureFPSProfile.BALANCED, false);
        assertTrue(PureFPSConfig.disableGlErrorChecks);
        assertTrue(PureFPSConfig.hardwareRegionVao);
        assertTrue(PureFPSConfig.lazyTerrainSetup);
        assertTrue(PureFPSConfig.limitDistantGlint);
        assertTrue(PureFPSConfig.fastItemRender);
        assertTrue(PureFPSConfig.skipVoidFogLoop);
        assertTrue(PureFPSConfig.fastFboBlit);
        assertTrue(PureFPSConfig.fastFrustumPVertex);
        assertTrue(PureFPSConfig.fastNametags);
        assertTrue(PureFPSConfig.hudEnabled);
        assertTrue(PureFPSConfig.smoothClientFont);
        assertTrue(PureFPSConfig.toggleSprint);
        assertTrue(PureFPSConfig.zoomEnabled);
        assertTrue(PureFPSConfig.fullbright);
    }

    @Test
    public void testSmoothFontAtlasBakingAndWhiteRgbFringeFix() {
        dev.purefps.client.ui.SmoothFontRenderer font = dev.purefps.client.ui.SmoothFontRenderer.body();
        java.awt.image.BufferedImage img = font.bakeAtlasImage();
        assertEquals(1024, img.getWidth());
        assertEquals(1024, img.getHeight());
        assertEquals(0x00FFFFFF, img.getRGB(0, 0) & 0x00FFFFFF);
        assertEquals(0x00FFFFFF, img.getRGB(16, 16) & 0x00FFFFFF);
        float w = font.getStringWidth("Équilibré \u2022 500 FPS");
        assertTrue("Expected positive string width, got " + w, w > 40.0f);
        assertTrue(font.getHeight() > 6.0f);
    }
}

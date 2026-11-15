package dev.purefps.core;

import dev.purefps.client.CpsTracker;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.junit.Test;

import java.io.File;
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

    @Test
    public void testQuadIndexBufferUshortAndUintConversion() {
        assertEquals(0, QuadIndexBuffer.quadVertexCountToIndexCount(0));
        assertEquals(6, QuadIndexBuffer.quadVertexCountToIndexCount(4));
        assertEquals(98304, QuadIndexBuffer.quadVertexCountToIndexCount(65536));

        java.nio.ShortBuffer sb = ByteBuffer.allocateDirect(QuadIndexBuffer.MAX_USHORT_QUADS * 6 * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer();
        QuadIndexBuffer.fillUshortIndices(sb, QuadIndexBuffer.MAX_USHORT_QUADS);
        assertEquals(0, sb.position());
        assertEquals(QuadIndexBuffer.MAX_USHORT_QUADS * 6, sb.limit());

        // Premier quad : {0, 1, 2, 2, 3, 0}
        assertEquals(0, sb.get(0) & 0xFFFF);
        assertEquals(1, sb.get(1) & 0xFFFF);
        assertEquals(2, sb.get(2) & 0xFFFF);
        assertEquals(2, sb.get(3) & 0xFFFF);
        assertEquals(3, sb.get(4) & 0xFFFF);
        assertEquals(0, sb.get(5) & 0xFFFF);

        // Dernier quad USHORT (quad 16383 -> sommets non signés 65532..65535)
        int lastOff = (QuadIndexBuffer.MAX_USHORT_QUADS - 1) * 6;
        assertEquals(65532, sb.get(lastOff) & 0xFFFF);
        assertEquals(65533, sb.get(lastOff + 1) & 0xFFFF);
        assertEquals(65534, sb.get(lastOff + 2) & 0xFFFF);
        assertEquals(65534, sb.get(lastOff + 3) & 0xFFFF);
        assertEquals(65535, sb.get(lastOff + 4) & 0xFFFF);
        assertEquals(65532, sb.get(lastOff + 5) & 0xFFFF);

        // Tampon UINT (> 65536 sommets)
        int quadCountUint = 20000;
        IntBuffer ib = ByteBuffer.allocateDirect(quadCountUint * 6 * 4)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        QuadIndexBuffer.fillUintIndices(ib, quadCountUint);
        int lastUintOff = (quadCountUint - 1) * 6;
        int expectedBase = (quadCountUint - 1) * 4;
        assertEquals(expectedBase, ib.get(lastUintOff));
        assertEquals(expectedBase + 1, ib.get(lastUintOff + 1));
        assertEquals(expectedBase + 2, ib.get(lastUintOff + 2));
        assertEquals(expectedBase + 2, ib.get(lastUintOff + 3));
        assertEquals(expectedBase + 3, ib.get(lastUintOff + 4));
        assertEquals(expectedBase, ib.get(lastUintOff + 5));
    }

    @Test
    public void testStaticAntiSeamMatrixAndChunkStaging() {
        FloatBuffer fb = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        ChunkRegionManager.fillStaticAntiSeamMatrix(fb);
        assertEquals(0, fb.position());
        assertEquals(16, fb.limit());

        float s = ChunkRegionManager.ANTI_SEAM_SCALE;
        // En OpenGL : translate(-8,-8,-8) * scale(s,s,s) * translate(8,8,8) applique à un point X :
        // X' = (X + 8) * s - 8  (car OpenGL post-multiplie : M = T(-8) * S(s) * T(8) -> t = 8*s - 8)
        float expectedT = 8.0F * s - 8.0F;
        assertEquals(s, fb.get(0), 1e-7f);
        assertEquals(s, fb.get(5), 1e-7f);
        assertEquals(s, fb.get(10), 1e-7f);
        assertEquals(expectedT, fb.get(12), 1e-7f);
        assertEquals(expectedT, fb.get(13), 1e-7f);
        assertEquals(expectedT, fb.get(14), 1e-7f);
        assertEquals(1.0F, fb.get(15), 1e-7f);

        ByteBuffer rawVerts = ByteBuffer.allocateDirect(4 * 28).order(ByteOrder.nativeOrder());
        for (int v = 0; v < 4; v++) {
            int off = v * 28;
            rawVerts.putFloat(off, 8.0F);
            rawVerts.putFloat(off + 4, 16.0F);
            rawVerts.putFloat(off + 8, 0.0F);
            rawVerts.putInt(off + 12, 0xFFFFFFFF);
            rawVerts.putFloat(off + 16, 0.25F);
            rawVerts.putFloat(off + 20, 0.75F);
            rawVerts.putInt(off + 24, 0x00F000F0);
        }
        ByteBuffer staged = ChunkRegionManager.prepareChunkAntiSeamBuffer(rawVerts, 4);
        assertEquals(4 * 28, staged.limit());
        assertEquals(8.0F, staged.getFloat(0), 1e-6f);
        assertEquals((16.0F - 8.0F) * s + 8.0F, staged.getFloat(4), 1e-5f);
        assertEquals((0.0F - 8.0F) * s + 8.0F, staged.getFloat(8), 1e-5f);
    }

    @Test
    public void testFastArmorCacheVanillaTable() {
        String[] expectedNames = {"leather", "chainmail", "iron", "gold", "diamond"};
        for (int ord = 0; ord < expectedNames.length; ord++) {
            String n = expectedNames[ord];
            assertEquals(
                    "minecraft:textures/models/armor/" + n + "_layer_1.png",
                    FastArmorCache.lookupByOrdinalForTest(ord, false, false).toString()
            );
            assertEquals(
                    "minecraft:textures/models/armor/" + n + "_layer_1_overlay.png",
                    FastArmorCache.lookupByOrdinalForTest(ord, false, true).toString()
            );
            assertEquals(
                    "minecraft:textures/models/armor/" + n + "_layer_2.png",
                    FastArmorCache.lookupByOrdinalForTest(ord, true, false).toString()
            );
            assertEquals(
                    "minecraft:textures/models/armor/" + n + "_layer_2_overlay.png",
                    FastArmorCache.lookupByOrdinalForTest(ord, true, true).toString()
            );
        }
        assertNull(FastArmorCache.lookupByOrdinalForTest(-1, false, false));
        assertNull(FastArmorCache.lookupByOrdinalForTest(5, false, false));
    }

    @Test
    public void testNewEntityItemFramePlayerAndSignProfileFlags() {
        PureFPSConfig.applyProfile(PureFPSProfile.FPS, false);
        assertTrue(PureFPSConfig.fastBakedItemModels);
        assertTrue(PureFPSConfig.fastItemFrames);
        assertEquals(36, PureFPSConfig.maxItemFrameDistance);
        assertTrue(PureFPSConfig.fastPlayerRendering);
        assertTrue(PureFPSConfig.fastSignText);
        PureFPSConfig.refreshProfileAfterManualEdit();
        assertEquals(PureFPSProfile.FPS, PureFPSConfig.activeProfile);

        PureFPSConfig.maxItemFrameDistance = 64;
        PureFPSConfig.refreshProfileAfterManualEdit();
        assertEquals(PureFPSProfile.CUSTOM, PureFPSConfig.activeProfile);

        PureFPSConfig.applyProfile(PureFPSProfile.QUALITY, false);
        assertTrue(PureFPSConfig.fastBakedItemModels);
        assertTrue(PureFPSConfig.fastItemFrames);
        assertEquals(96, PureFPSConfig.maxItemFrameDistance);
        assertFalse(PureFPSConfig.fastPlayerRendering);
        assertFalse(PureFPSConfig.fastSignText);
        PureFPSConfig.refreshProfileAfterManualEdit();
        assertEquals(PureFPSProfile.QUALITY, PureFPSConfig.activeProfile);

        PureFPSConfig.applyProfile(PureFPSProfile.BALANCED, false);
        assertEquals(48, PureFPSConfig.maxItemFrameDistance);
        assertTrue(PureFPSConfig.fastPlayerRendering);
        assertTrue(PureFPSConfig.fastSignText);
        PureFPSConfig.refreshProfileAfterManualEdit();
        assertEquals(PureFPSProfile.BALANCED, PureFPSConfig.activeProfile);
    }

    @Test
    public void testSeamlessLaunchTransitionHandoffAndEmergenceFade() throws Exception {
        File tmpMc = new File(System.getProperty("java.io.tmpdir"), "purefps_seamless_test_" + System.nanoTime());
        File cfgDir = new File(tmpMc, "config");
        assertTrue(cfgDir.mkdirs());
        File handoff = new File(cfgDir, ".purefps_handoff");
        File ready = new File(cfgDir, ".purefps_menu_ready");

        try {
            // 1. Jeton expiré (> 120s) -> ignoré et supprimé proprement
            assertTrue(handoff.createNewFile());
            long now = 1_000_000_000L;
            assertTrue(handoff.setLastModified(now - 300_000L));
            SeamlessLaunchTransition.resetForTest();
            assertFalse(SeamlessLaunchTransition.evaluateHandoffActivation(false, tmpMc, now));
            assertFalse(handoff.exists());

            // 2. Jeton frais -> active la transition sans couture
            assertTrue(handoff.createNewFile());
            long freshNow = System.currentTimeMillis();
            SeamlessLaunchTransition.resetForTest();
            assertTrue(SeamlessLaunchTransition.evaluateHandoffActivation(false, tmpMc, freshNow));
            assertTrue(SeamlessLaunchTransition.isActive());

            // Frame 1 : écran 100 % noir (255), pas encore de signal au Launcher
            assertEquals(255, SeamlessLaunchTransition.computeMenuCurtainAlphaAt(tmpMc, freshNow));
            assertFalse(ready.exists());

            // Frame 2 : signal .purefps_menu_ready écrit + .purefps_handoff consommé + début du fondu (255)
            assertEquals(255, SeamlessLaunchTransition.computeMenuCurtainAlphaAt(tmpMc, freshNow + 16L));
            assertTrue(ready.isFile());
            assertFalse(handoff.exists());

            // Milieu de l'émergence (~325 ms sur 650 ms -> ease-out cubique ~ (0.5)^3 * 255 = 32)
            int midAlpha = SeamlessLaunchTransition.computeMenuCurtainAlphaAt(tmpMc, freshNow + 16L + 325L);
            assertTrue(midAlpha > 20 && midAlpha < 50);

            // Fin de l'émergence (>= 650 ms) -> alpha = 0 et court-circuit définitif
            assertEquals(0, SeamlessLaunchTransition.computeMenuCurtainAlphaAt(tmpMc, freshNow + 16L + 700L));
            assertEquals(0, SeamlessLaunchTransition.computeMenuCurtainAlphaAt(tmpMc, freshNow + 16L + 2000L));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            new File(cfgDir, SeamlessLaunchTransition.DISPLAY_READY_FILE_NAME).delete();
            //noinspection ResultOfMethodCallIgnored
            ready.delete();
            //noinspection ResultOfMethodCallIgnored
            handoff.delete();
            //noinspection ResultOfMethodCallIgnored
            cfgDir.delete();
            //noinspection ResultOfMethodCallIgnored
            tmpMc.delete();
            SeamlessLaunchTransition.resetForTest();
        }
    }

    @Test
    public void testBlackHoleEmergenceTimelineAndSplashSuppression() throws Exception {
        File tmpMc = new File(System.getProperty("java.io.tmpdir"), "purefps_bh_splash_test_" + System.nanoTime());
        File cfgDir = new File(tmpMc, "config");
        File splash = new File(cfgDir, "splash.properties");
        try {
            // 1. Configuration automatique du thread dédié Forge SplashProgress en fond noir #000000 (100 FPS sans freeze)
            SeamlessLaunchTransition.ensureForgeSplashConfiguredDark(tmpMc);
            assertTrue(splash.isFile());
            java.util.Properties props = new java.util.Properties();
            try (java.io.FileInputStream fis = new java.io.FileInputStream(splash)) {
                props.load(fis);
            }
            assertEquals("false", props.getProperty("enabled"));
            assertEquals("0x000000", props.getProperty("background"));

            // 2. Émergence fluide depuis #000000 pur (0.0 -> 1.0 sur 2150 ms à 240 FPS dans GuiPureMainMenu)
            BlackHoleEmergenceRenderer.resetForBoot();
            float s0 = BlackHoleEmergenceRenderer.computeEmergenceProgress(0.0F, -1L);
            float mStart = BlackHoleEmergenceRenderer.computeEmergenceProgress(0.0F, 0L);
            float mEarly = BlackHoleEmergenceRenderer.computeEmergenceProgress(0.0F, 500L);
            float mMid = BlackHoleEmergenceRenderer.computeEmergenceProgress(0.0F, 1550L);
            float mEnd = BlackHoleEmergenceRenderer.computeEmergenceProgress(0.0F, 2200L);

            assertEquals(0.0F, s0, 1e-5F);
            assertEquals(0.0F, mStart, 1e-5F);
            assertTrue(mEarly > 0.10F && mEarly < 0.35F);
            assertTrue(mMid > 0.75F && mMid < 1.0F);
            assertEquals(1.0F, mEnd, 1e-5F);
            assertFalse(BlackHoleEmergenceRenderer.isBootEmergenceComplete());

            assertEquals(0.0F, BlackHoleEmergenceRenderer.computeCameraUiAlpha(0.50F), 1e-5F);
            float uiMid = BlackHoleEmergenceRenderer.computeCameraUiAlpha(mMid);
            assertTrue(uiMid > 0.35F && uiMid < 1.0F);
            assertEquals(1.0F, BlackHoleEmergenceRenderer.computeCameraUiAlpha(mEnd), 1e-5F);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            splash.delete();
            //noinspection ResultOfMethodCallIgnored
            cfgDir.delete();
            //noinspection ResultOfMethodCallIgnored
            tmpMc.delete();
            BlackHoleEmergenceRenderer.resetForBoot();
        }
    }

    @Test
    public void testMashModules200CountAndSettingsRoundTrip() {
        File tmpMc = new File(System.getProperty("java.io.tmpdir"), "mashclient_mods_test_" + System.nanoTime());
        File cfgDir = new File(tmpMc, "config");
        File modsJson = new File(cfgDir, "mashclient_modules.json");
        try {
            dev.purefps.module.MashModules.init(tmpMc);
            java.util.List<dev.purefps.module.MashModule> all = dev.purefps.module.MashModules.getAll();
            java.util.List<dev.purefps.module.MashModule> huds = dev.purefps.module.MashModules.getHudModules();

            assertEquals("MashClient 4.4.4 must expose exactly 204 modules", 204, all.size());
            assertEquals("MashClient 4.0 must expose 65 draggable HUD widgets", 65, huds.size());

            for (dev.purefps.module.MashModule mod : all) {
                assertFalse("Every module must have right-click sub-settings: " + mod.getId(), mod.getSettings().isEmpty());
            }

            // Vérification des sous-paramètres clés demandés (Curseur, FOV PvP de base, Molette Zoom)
            assertNotNull(dev.purefps.module.MashModules.MOD_CROSSHAIR.findSetting("shape"));
            assertNotNull(dev.purefps.module.MashModules.MOD_CROSSHAIR.findSetting("gap"));
            dev.purefps.module.MashSetting.NumberSetting baseFov =
                    (dev.purefps.module.MashSetting.NumberSetting) dev.purefps.module.MashModules.MOD_PVP_FOV.findSetting("base_fov");
            dev.purefps.module.MashSetting.BooleanSetting scrollZoom =
                    (dev.purefps.module.MashSetting.BooleanSetting) dev.purefps.module.MashModules.MOD_ZOOM.findSetting("scroll_zoom");
            assertNotNull(baseFov);
            assertNotNull(scrollZoom);

            dev.purefps.module.MashModule fpsHud = dev.purefps.module.MashModules.getById("hud_fps");
            assertNotNull(fpsHud);
            fpsHud.setHudPos(242.5F, 118.0F);
            baseFov.set(103.0F);
            scrollZoom.set(false);
            dev.purefps.module.MashModules.MOD_CROSSHAIR.setEnabled(true);

            dev.purefps.module.MashModules.saveNow();
            assertTrue(modsJson.isFile());

            // Mutation temporaire en mémoire puis rechargement depuis mashclient_modules.json
            fpsHud.setHudPos(10.0F, 10.0F);
            baseFov.set(70.0F);
            scrollZoom.set(true);
            dev.purefps.module.MashModules.MOD_CROSSHAIR.setEnabled(false);

            dev.purefps.module.MashModules.loadNow();
            assertEquals(242.5F, fpsHud.getHudX(), 0.01F);
            assertEquals(118.0F, fpsHud.getHudY(), 0.01F);
            assertEquals(103.0F, baseFov.get(), 0.01F);
            assertFalse(scrollZoom.get());
            assertTrue(dev.purefps.module.MashModules.MOD_CROSSHAIR.isEnabled());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            modsJson.delete();
            //noinspection ResultOfMethodCallIgnored
            cfgDir.delete();
            //noinspection ResultOfMethodCallIgnored
            tmpMc.delete();
        }
    }

    @Test
    public void testHudSnapEngineMagneticGlueAndAxisLock() {
        java.util.List<dev.purefps.module.HudSnapEngine.Box> others = java.util.Collections.singletonList(
                new dev.purefps.module.HudSnapEngine.Box("hud_fps", 100.0F, 80.0F, 70.0F, 20.0F)
        );

        // 1. Collage direct bord-à-bord (le bord gauche à x=172.5 se colle exactement au bord droit x=170.0 de hud_fps)
        dev.purefps.module.HudSnapEngine.SnapResult glueSnap = dev.purefps.module.HudSnapEngine.computeSnap(
                "hud_cps",
                171.2F,
                81.5F,
                64.0F,
                20.0F,
                150.0F,
                60.0F,
                false,
                960.0F,
                540.0F,
                others,
                dev.purefps.module.HudSnapEngine.DEFAULT_SNAP_RADIUS
        );
        assertEquals(170.0F, glueSnap.x, 0.01F);
        assertEquals(80.0F, glueSnap.y, 0.01F);
        assertTrue(glueSnap.hasVerticalGuide);
        assertTrue(glueSnap.hasHorizontalGuide);

        // 2. Alignement sur l'axe central de l'écran (960 / 2 = 480 -> x = 480 - 40 = 440)
        dev.purefps.module.HudSnapEngine.SnapResult centerSnap = dev.purefps.module.HudSnapEngine.computeSnap(
                "hud_ping",
                442.0F,
                200.0F,
                80.0F,
                20.0F,
                300.0F,
                200.0F,
                false,
                960.0F,
                540.0F,
                others,
                dev.purefps.module.HudSnapEngine.DEFAULT_SNAP_RADIUS
        );
        assertEquals(440.0F, centerSnap.x, 0.01F);
        assertTrue(centerSnap.hasVerticalGuide);
        assertEquals(480.0F, centerSnap.verticalLineX, 0.01F);

        // 3. Verrouillage d'axe strict avec Shift maintenu (déplacement horizontal pur -> Y figé à dragOriginY)
        dev.purefps.module.HudSnapEngine.SnapResult shiftAxis = dev.purefps.module.HudSnapEngine.computeSnap(
                "hud_coords",
                310.0F,
                148.0F,
                90.0F,
                22.0F,
                220.0F,
                135.0F,
                true,
                960.0F,
                540.0F,
                others,
                dev.purefps.module.HudSnapEngine.DEFAULT_SNAP_RADIUS
        );
        assertEquals(135.0F, shiftAxis.y, 0.01F);
        assertTrue(shiftAxis.hasHorizontalGuide);
    }

    @Test
    public void testBilingualEnglishModuleSearchAndDesktopFonts() throws Exception {
        java.io.File tempDir = java.nio.file.Files.createTempDirectory("mashclient_en_test").toFile();
        tempDir.deleteOnExit();
        dev.purefps.module.MashModules.init(tempDir);

        java.util.List<dev.purefps.module.MashModule> all = dev.purefps.module.MashModules.getAll();
        assertEquals(204, all.size());
        for (dev.purefps.module.MashModule mod : all) {
            dev.purefps.module.MashEnglishCatalog.Entry entry = dev.purefps.module.MashEnglishCatalog.lookup(mod.getId());
            assertNotNull("Catalogue anglais manquant pour " + mod.getId(), entry);
            assertFalse("Nom anglais vide pour " + mod.getId(), mod.getEnglishName().trim().isEmpty());
        }

        String[][] expectedTopMatches = {
                {"Armor Status", "hud_armor"},
                {"armour status", "hud_armor"},
                {"FPS Counter", "hud_fps"},
                {"Custom Crosshair", "pvp_crosshair"},
                {"croshair", "pvp_crosshair"},
                {"No Hurt Cam", "pvp_no_hurtcam"},
                {"hurtcam", "pvp_no_hurtcam"},
                {"hurt cam", "pvp_no_hurtcam"},
                {"Time Changer", "vis_time_changer"},
                {"timechanger", "vis_time_changer"},
                {"Toggle Sprint", "pvp_toggle_sprint"},
                {"togglesprint", "pvp_toggle_sprint"},
                {"Freelook", "pvp_freelook"},
                {"perspective mod", "pvp_freelook"},
                {"1.7 animations", "pvp_old_animations"},
                {"blockhit", "pvp_old_animations"},
                {"Block Outline", "vis_block_overlay"},
                {"Clear Water", "pvp_clean_water_overlay"},
                {"Item Physics", "vis_item_physics"},
                {"UHC Overlay", "hyp_uhc_overlay"},
                {"AutoGG", "hyp_autogg"},
                {"auto gg", "hyp_autogg"},
                {"Entity Culling", "eng_entity_culling"},
                {"Keystrokes", "hud_keystrokes"},
                {"Potion Effects", "hud_potions"},
                {"Coordinates", "hud_coords"},
                {"Zoom", "pvp_zoom"},
                {"FOV Changer", "pvp_fov"},
                {"Custom Mouse Pointer", "vis_custom_pointer"},
                {"custom cursor", "vis_custom_pointer"}
        };

        for (String[] pair : expectedTopMatches) {
            String query = pair[0];
            String expectedId = pair[1];
            java.util.List<dev.purefps.module.MashModule> results = dev.purefps.module.MashModules.filter(
                    dev.purefps.module.MashCategory.ALL,
                    false,
                    false,
                    query
            );
            assertFalse("Aucun résultat pour la requête anglaise '" + query + "'", results.isEmpty());
            assertEquals("Mauvais premier résultat pour '" + query + "'", expectedId, results.get(0).getId());
        }

        // Vérification du bake headless des nouvelles polices UI Desktop (incluant ▶ et ♥)
        assertNotNull(dev.purefps.client.ui.SmoothFontRenderer.uiHero().bakeAtlasImage());
        assertNotNull(dev.purefps.client.ui.SmoothFontRenderer.uiCardTitle().bakeAtlasImage());
        assertTrue(dev.purefps.client.ui.SmoothFontRenderer.uiCardTitle().getStringWidth("\u25B6 REJOINDRE HYPIXEL") > 40.0F);
        assertTrue(dev.purefps.client.ui.SmoothFontRenderer.uiBody().getStringWidth("10.0 \u2665") > 15.0F);
    }

    @Test
    public void testMashAudioAndLogoAssets42() throws Exception {
        byte[] emergencePcm = dev.purefps.client.audio.MashAudioEngine.synthBlackHoleEmergence();
        assertNotNull(emergencePcm);
        assertTrue("PCM émergence Trou Noir trop court", emergencePcm.length > 400_000);
        assertEquals("Alignement stéréo 16-bit attendu (4 octets/frame)", 0, emergencePcm.length % 4);

        boolean hasSignal = false;
        for (int i = 0; i < emergencePcm.length; i += 64) {
            if (emergencePcm[i] != 0 || emergencePcm[i + 1] != 0) {
                hasSignal = true;
                break;
            }
        }
        assertTrue("Signal PCM muet", hasSignal);

        java.awt.image.BufferedImage mark = javax.imageio.ImageIO.read(
                PureFPSCoreTest.class.getResourceAsStream("/assets/purefps/textures/gui/mash_mark.png")
        );
        assertNotNull("mash_mark.png introuvable", mark);
        assertEquals(128, mark.getWidth());
        assertEquals(128, mark.getHeight());

        java.awt.image.BufferedImage icon16 = javax.imageio.ImageIO.read(
                PureFPSCoreTest.class.getResourceAsStream("/assets/purefps/textures/gui/mash_icon_16.png")
        );
        assertNotNull("mash_icon_16.png introuvable", icon16);
        assertEquals(16, icon16.getWidth());
        assertEquals(16, icon16.getHeight());

        java.awt.image.BufferedImage icon32 = javax.imageio.ImageIO.read(
                PureFPSCoreTest.class.getResourceAsStream("/assets/purefps/textures/gui/mash_icon_32.png")
        );
        assertNotNull("mash_icon_32.png introuvable", icon32);
        assertEquals(32, icon32.getWidth());
        assertEquals(32, icon32.getHeight());

        java.io.File tempDir = java.nio.file.Files.createTempDirectory("purefps-v42-test").toFile();
        tempDir.deleteOnExit();
        dev.purefps.module.MashModules.init(tempDir);
        for (dev.purefps.module.MashModule mod : dev.purefps.module.MashModules.getAll()) {
            String combined = mod.getName() + " " + mod.getDescription();
            assertFalse("Terme Singularity résiduel sur " + mod.getId(), combined.contains("Singularity"));
            assertFalse("Glyphe dingbat risquant un carré blanc sur " + mod.getId(),
                    combined.indexOf('\u25B6') >= 0 || combined.indexOf('\u2665') >= 0 || combined.indexOf('\u2605') >= 0);
        }
    }

    @Test
    public void testIslandCoreCullerFullyBuriedAndSealedCaveUnloading() {
        IslandCoreCuller.Context ctx = IslandCoreCuller.currentContext();
        try {
            long[] opaque = new long[64];
            long[] outwardOpen = new long[64];
            java.util.Arrays.fill(opaque, -1L);

            // 1. Section 100% enfouie au coeur d'une île BedWars -> 100% déchargée (fullyBuried = true)
            IslandCoreCuller.analyzeBitsets(opaque, outwardOpen, false, false, ctx);
            assertTrue("Fully buried island core must unload 100% of geometry", ctx.fullyBuried);
            assertTrue(ctx.active);

            // 2. Île avec surface supérieure à l'air libre (ly=15 non-opaque) + grotte interne scellée (7..9, 7..9, 7..9)
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int airTop = IslandCoreCuller.packLocal(lx, 15, lz);
                    opaque[airTop >>> 6] &= ~(1L << (airTop & 63));
                    outwardOpen[airTop >>> 6] |= (1L << (airTop & 63));
                }
            }
            for (int ly = 7; ly <= 9; ly++) {
                for (int lz = 7; lz <= 9; lz++) {
                    for (int lx = 7; lx <= 9; lx++) {
                        int caveIdx = IslandCoreCuller.packLocal(lx, ly, lz);
                        opaque[caveIdx >>> 6] &= ~(1L << (caveIdx & 63));
                    }
                }
            }

            ctx.baseX = 0;
            ctx.baseY = 64;
            ctx.baseZ = 0;
            IslandCoreCuller.analyzeBitsets(opaque, outwardOpen, false, false, ctx);
            assertFalse(ctx.fullyBuried);
            assertTrue(ctx.active);

            assertTrue("Top surface crust must remain visible", ctx.isBlockVisibleFromExterior(8, 14, 8));
            assertTrue("Top UP face must remain visible", IslandCoreCuller.isFaceVisible(8, 78, 8, EnumFacing.UP));
            assertFalse("Top crust DOWN face into solid core must be culled", IslandCoreCuller.isFaceVisible(8, 78, 8, EnumFacing.DOWN));

            assertFalse("Deep core block must be unloaded", ctx.isBlockVisibleFromExterior(8, 10, 8));
            assertFalse("Sealed cave floor must be unloaded", ctx.isBlockVisibleFromExterior(8, 6, 8));
            assertFalse("Sealed cave UP face must be culled", IslandCoreCuller.isFaceVisible(8, 70, 8, EnumFacing.UP));
        } finally {
            IslandCoreCuller.clearCurrentThread();
        }
    }

    @Test
    public void testSmartChunkUpdateSchedulerBakeOncePlayerRadiusAndDynamicExplosionUnfreeze() {
        SmartChunkUpdateScheduler.clear();
        long now = System.currentTimeMillis();

        // 1. Deux joueurs en (0, 0) et (-10, 8) : seule la grille 3x3 autour de chaque joueur est active
        SmartChunkUpdateScheduler.setActivePlayersForTest(new int[]{0, -10}, new int[]{0, 8}, 2);
        assertTrue(SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(0, 4, 0, now));
        assertTrue(SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(1, 4, -1, now));
        assertTrue(SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(-9, 5, 9, now));
        assertFalse("Chunk à 2 chunks du joueur sans explosion doit être gelé",
                SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(2, 4, 0, now));
        assertFalse("Chunk distant (20, 4, 20) doit être strictement gelé",
                SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(20, 4, 20, now));

        // 2. Modification serveur de bloc à distance (ex: pont ou lit cassé à (320, 64, 320)) -> dégel 0 ms + voisins
        SmartChunkUpdateScheduler.markDynamicBlockChange(320, 64, 320, true);
        assertTrue(SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(20, 4, 20, now));
        assertTrue(SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(21, 4, 20, now));
        assertFalse("Re-gel automatique après expiration du TTL de modification",
                SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(20, 4, 20, now + 20_000L));

        // 3. Fireball / TNT en vol (markDestructiveEntityActivity) + Explosion S27 à 600 blocs
        SmartChunkUpdateScheduler.markDestructiveEntityActivity(480.5D, 72.0D, -160.5D);
        assertTrue("Fireball/TNT distante doit dégeler son chunk et ses voisins",
                SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(30, 4, -11, now));

        java.util.List<net.minecraft.util.BlockPos> crater = java.util.Arrays.asList(
                new net.minecraft.util.BlockPos(640, 65, 640),
                new net.minecraft.util.BlockPos(657, 65, 640)
        );
        SmartChunkUpdateScheduler.markExplosion(640.0D, 65.0D, 640.0D, 4.0F, crater);
        assertTrue(SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(40, 4, 40, now));
        assertTrue(SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(41, 4, 40, now));
        assertTrue(SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(42, 4, 40, now));

        // 4. Grâce de couture initiale à la réception réseau d'une colonne S21/S26, puis gel définitif
        SmartChunkUpdateScheduler.markChunkColumnLoaded(-25, 35);
        long graceNow = System.currentTimeMillis();
        assertTrue(SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(-25, 3, 35, graceNow));
        assertTrue(SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(-24, 3, 35, graceNow));
        assertFalse("Une fois la couture initiale terminée, la colonne distante est gelée",
                SmartChunkUpdateScheduler.shouldRebuildCompiledSubchunk(-25, 3, 35, graceNow + 5_000L));

        // 5. Filtrage notifyLightSet : gel intégral hors joueur ±1 (sauf explosion/modif), cooldown en zone active
        PureFPSConfig.applyProfile(PureFPSProfile.BALANCED, false);
        assertFalse("Aucune recompilation lumineuse sur un chunk distant gelé",
                SmartChunkUpdateScheduler.shouldAllowLightRebuild(800, 64, 800, now));
        assertTrue("Explosion distante autorise la mise à jour lumineuse du cratère",
                SmartChunkUpdateScheduler.shouldAllowLightRebuild(640, 65, 640, now));
        assertTrue("Premier recalcul lumineux autorisé dans le chunk du joueur (0, 0)",
                SmartChunkUpdateScheduler.shouldAllowLightRebuild(8, 64, 8, now));
        assertFalse("Cooldown anti-spam lumineux dans le chunk du joueur",
                SmartChunkUpdateScheduler.shouldAllowLightRebuild(8, 64, 8, now + 400L));

        PureFPSConfig.applyProfile(PureFPSProfile.FPS, false);
        assertFalse("Profil FPS coupe le recalcul lumineux passif même proche si aucun bloc n'est modifié",
                SmartChunkUpdateScheduler.shouldAllowLightRebuild(8, 64, 8, now + 5_000L));

        SmartChunkUpdateScheduler.clear();
    }

    @Test
    public void testTntTimerBedWarsAndVanillaCountdownAndColors() {
        // Vanilla 80 ticks = 4.00s
        assertEquals(4.00F, TntTimerRenderer.computeRemainingSeconds(80, 0, 0.0F, 0, false), 1e-4F);
        assertEquals(2.00F, TntTimerRenderer.computeRemainingSeconds(40, 40, 0.0F, 0, false), 1e-4F);
        assertEquals("4.00s", TntTimerRenderer.formatSeconds(4.00F));
        assertEquals("0.05s", TntTimerRenderer.formatSeconds(0.05F));

        // Hypixel BedWars 52 ticks (serveur envoie fuse=80 au spawn puis explose à 52 ticks = 2.60s)
        assertEquals(2.60F, TntTimerRenderer.computeRemainingSeconds(80, 0, 0.0F, 0, true), 1e-4F);
        assertEquals(0.00F, TntTimerRenderer.computeRemainingSeconds(28, 52, 0.0F, 0, true), 1e-4F);
        assertEquals(2.60F, TntTimerRenderer.resolveMaxFuseSeconds(80, 0, 0, true), 1e-4F);
        assertEquals(4.00F, TntTimerRenderer.resolveMaxFuseSeconds(80, 0, 1, true), 1e-4F);

        // Dégradé de couleur dynamique
        assertEquals(0xFF34D399, TntTimerRenderer.computeFuseColor(3.50F, 4.00F, true));
        assertEquals(0xFFFBBF24, TntTimerRenderer.computeFuseColor(2.00F, 4.00F, true));
        assertEquals(0xFFFB923C, TntTimerRenderer.computeFuseColor(1.00F, 4.00F, true));
        assertEquals(0xFFF87171, TntTimerRenderer.computeFuseColor(0.30F, 4.00F, true));
        assertEquals(0xFFF4F6FA, TntTimerRenderer.computeFuseColor(0.30F, 4.00F, false));
    }

    @Test
    public void testBlackHoleMenuAmbientSoundAndCleanCopywriting44() throws Exception {
        byte[] loopPcm = dev.purefps.client.audio.MashAudioEngine.synthBlackHoleAmbientLoop();
        assertNotNull(loopPcm);
        assertEquals(44100 * 6 * 4, loopPcm.length);

        boolean nonZero = false;
        for (int i = 0; i < loopPcm.length; i += 128) {
            if (loopPcm[i] != 0 || loopPcm[i + 1] != 0) {
                nonZero = true;
                break;
            }
        }
        assertTrue("Boucle ambiante trou noir muette", nonZero);

        java.io.File tempDir = java.nio.file.Files.createTempDirectory("purefps-v44-copy").toFile();
        tempDir.deleteOnExit();
        PureFPSConfig.blackHoleMenuSound = true;
        dev.purefps.module.MashModules.init(tempDir);

        dev.purefps.module.MashModule mainMenuMod = dev.purefps.module.MashModules.getById("wld_custom_main_menu");
        assertNotNull(mainMenuMod);
        dev.purefps.module.MashSetting.BooleanSetting bhSoundSet =
                (dev.purefps.module.MashSetting.BooleanSetting) mainMenuMod.findSetting("black_hole_sound");
        assertNotNull(bhSoundSet);
        assertTrue(bhSoundSet.get());
        bhSoundSet.set(false);
        assertFalse(PureFPSConfig.blackHoleMenuSound);
        bhSoundSet.set(true);
        assertTrue(PureFPSConfig.blackHoleMenuSound);

        dev.purefps.module.MashModule tntMod = dev.purefps.module.MashModules.getById("vis_tnt_timer");
        assertNotNull(tntMod);
        assertNotNull(tntMod.findSetting("color_code"));
        assertNotNull(tntMod.findSetting("fuse_mode"));

        for (dev.purefps.module.MashModule mod : dev.purefps.module.MashModules.getAll()) {
            String combined = mod.getName() + " " + mod.getDescription();
            assertFalse("Point d'exclamation résiduel sur " + mod.getId(), combined.contains("!"));
            assertFalse("Suffixe ' Pro' artificiel résiduel sur " + mod.getId(), mod.getName().endsWith(" Pro"));
            assertFalse("Jargon IA 'Obsidienne'/'Cinétique' sur " + mod.getId(),
                    combined.contains("Obsidienne") || combined.contains("Cinétique"));
        }
    }

    @Test
    public void testMenuCursorRendererSdfAndNativeCursorDefault() throws Exception {
        java.io.File tempDir = java.nio.file.Files.createTempDirectory("purefps-v444-cursor").toFile();
        tempDir.deleteOnExit();
        PureFPSConfig.enabled = true;
        dev.purefps.module.MashModules.init(tempDir);

        assertNull("Ancien module vis_menu_cursor ne doit plus écraser le curseur OS natif",
                dev.purefps.module.MashModules.getById("vis_menu_cursor"));
        dev.purefps.module.MashModule cursorMod = dev.purefps.module.MashModules.getById("vis_custom_pointer");
        assertNotNull("vis_custom_pointer doit être enregistré", cursorMod);
        assertFalse("vis_custom_pointer doit être désactivé par défaut pour préserver le curseur Windows natif",
                cursorMod.isEnabled());
        assertFalse(dev.purefps.module.MashFlags.menuCursorEnabled);
        assertFalse(MenuCursorRenderer.isCustomPointerModuleActive());

        // Activation manuelle optionnelle par l'utilisateur
        cursorMod.setEnabled(true);
        assertTrue(dev.purefps.module.MashFlags.menuCursorEnabled);
        assertTrue(MenuCursorRenderer.isCustomPointerModuleActive());
        cursorMod.setEnabled(false);
        assertFalse(MenuCursorRenderer.isCustomPointerModuleActive());
        assertEquals(0, dev.purefps.module.MashFlags.menuCursorStyle);

        for (int style = 0; style <= 2; style++) {
            assertTrue("Intérieur flèche style " + style + " doit avoir SDF < 0",
                    MenuCursorRenderer.evaluateCursorSdf(3.0F, 9.0F, style) < -0.5F);
            assertTrue("Coin éloigné style " + style + " doit avoir SDF > 15",
                    MenuCursorRenderer.evaluateCursorSdf(35.0F, 35.0F, style) > 15.0F);

            byte[] rgba = MenuCursorRenderer.rasterizeSupersampledCursorRgba(style, 0xFFFFFFFF, 0xFF0A0D14, true);
            assertEquals(64 * 64 * 4, rgba.length);

            int insideIdx = ((8 + 9) * 64 + (8 + 3)) * 4;
            assertEquals(255, rgba[insideIdx] & 0xFF);
            assertEquals(255, rgba[insideIdx + 1] & 0xFF);
            assertEquals(255, rgba[insideIdx + 2] & 0xFF);
            assertEquals(255, rgba[insideIdx + 3] & 0xFF);

            int cornerIdx = (63 * 64 + 63) * 4;
            assertEquals(0, rgba[cornerIdx + 3] & 0xFF);
        }

        // Style 3 (Croix centrée sur 32, 32)
        assertTrue(MenuCursorRenderer.evaluateCursorSdf(0.0F, 0.0F, 3) < -0.8F);
        byte[] crossRgba = MenuCursorRenderer.rasterizeSupersampledCursorRgba(3, 0xFFFFFFFF, 0xFF0A0D14, true);
        int centerIdx = (32 * 64 + 32) * 4;
        assertEquals(255, crossRgba[centerIdx + 3] & 0xFF);
        assertEquals(0, crossRgba[(63 * 64 + 63) * 4 + 3] & 0xFF);
    }

    @Test
    public void testDwmSafeBorderlessBoundsPreventsLegacyExclusiveFlip() {
        // 1. Moniteur unique 1920x1080 -> {0, 0, 1920, 1081} (empêche EqualRect Legacy Flip + masque la barre des tâches)
        assertArrayEquals(
                new int[]{0, 0, 1920, 1081},
                BorderlessWindowHelper.resolveDwmSafeBorderlessBounds(1920, 1080, 0, 0, 1920, 1080)
        );

        // 2. Double écran côte-à-côte (second moniteur à droite ou à gauche, aucun en bas) -> {0, 0, 1920, 1081}, 0 débordement latéral
        assertArrayEquals(
                new int[]{0, 0, 1920, 1081},
                BorderlessWindowHelper.resolveDwmSafeBorderlessBounds(1920, 1080, 0, 0, 3840, 1080)
        );
        assertArrayEquals(
                new int[]{0, 0, 2560, 1441},
                BorderlessWindowHelper.resolveDwmSafeBorderlessBounds(2560, 1440, -1920, 0, 4480, 1440)
        );

        // 3. Second moniteur en bas (virtBottom > deskH, aucun en haut virtY == 0) -> étend en haut {0, -1, 1920, 1081}, bas exact à 1080 !
        int[] stackedBottom = BorderlessWindowHelper.resolveDwmSafeBorderlessBounds(1920, 1080, 0, 0, 1920, 2160);
        assertArrayEquals(new int[]{0, -1, 1920, 1081}, stackedBottom);
        assertEquals("Aucun pixel ne doit déborder sur le moniteur du bas", 1080, stackedBottom[1] + stackedBottom[3]);

        // 4. Moniteurs en haut ET en bas, aucun à droite -> étend à droite {0, 0, 1921, 1080}
        assertArrayEquals(
                new int[]{0, 0, 1921, 1080},
                BorderlessWindowHelper.resolveDwmSafeBorderlessBounds(1920, 1080, 0, -1080, 1920, 3240)
        );

        // 5. Moniteurs en haut, en bas ET à droite, aucun à gauche -> étend à gauche {-1, 0, 1921, 1080}
        int[] openLeft = BorderlessWindowHelper.resolveDwmSafeBorderlessBounds(1920, 1080, 0, -1080, 3840, 3240);
        assertArrayEquals(new int[]{-1, 0, 1921, 1080}, openLeft);
        assertEquals("Aucun pixel ne doit déborder sur le moniteur de droite", 1920, openLeft[0] + openLeft[2]);

        // Vérification globale : ne retourne JAMAIS le rectangle exact (0, 0, deskW, deskH) et couvre toujours [0..deskW, 0..deskH]
        int[][] layouts = {
                {0, 0, 1920, 1080},
                {0, 0, 3840, 1080},
                {-1920, 0, 3840, 1080},
                {0, 0, 1920, 2160},
                {0, -1080, 1920, 3240},
                {0, -1080, 3840, 3240},
                {-1920, -1080, 5760, 3240}
        };
        for (int[] v : layouts) {
            int[] b = BorderlessWindowHelper.resolveDwmSafeBorderlessBounds(1920, 1080, v[0], v[1], v[2], v[3]);
            int left = b[0];
            int top = b[1];
            int right = b[0] + b[2];
            int bottom = b[1] + b[3];
            assertFalse("EqualRect (0,0,1920,1080) déclencherait Legacy Flip",
                    left == 0 && top == 0 && right == 1920 && bottom == 1080);
            assertTrue("Doit couvrir tout le moniteur pour masquer la barre des tâches",
                    left <= 0 && top <= 0 && right >= 1920 && bottom >= 1080);
        }
    }
}

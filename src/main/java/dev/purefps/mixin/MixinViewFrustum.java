package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Élimine les 10 000 à 17 424 allocations de `new BlockPos(m, q, o)` effectuées par Vanilla
 * dans ViewFrustum.updateChunkPositions à CHAQUE franchissement d'une bordure de chunk de 16 blocs :
 * compare d'abord les entiers primitifs (x, y, z) et n'alloue un BlockPos que pour la tranche
 * de sections réellement déplacées (~3 % de la grille).
 */
@Mixin(ViewFrustum.class)
public abstract class MixinViewFrustum {

    @Shadow
    protected int countChunksY;

    @Shadow
    protected int countChunksX;

    @Shadow
    protected int countChunksZ;

    @Shadow
    public RenderChunk[] renderChunks;

    @Shadow
    private int func_178157_a(int baseCoord, int spanBlocks, int chunkIndex) {
        return 0;
    }

    /**
     * @author PureClient
     * @reason Supprime ~97 % des allocations BlockPos lors des déplacements entre chunks
     */
    @Overwrite
    public void updateChunkPositions(double viewEntityX, double viewEntityZ) {
        int baseX = MathHelper.floor_double(viewEntityX) - 8;
        int baseZ = MathHelper.floor_double(viewEntityZ) - 8;
        int spanX = this.countChunksX * 16;
        int cxCount = this.countChunksX;
        int cyCount = this.countChunksY;
        int czCount = this.countChunksZ;
        RenderChunk[] chunks = this.renderChunks;

        for (int lx = 0; lx < cxCount; ++lx) {
            int worldX = this.func_178157_a(baseX, spanX, lx);
            for (int lz = 0; lz < czCount; ++lz) {
                int worldZ = this.func_178157_a(baseZ, spanX, lz);
                int zBaseIdx = lz * cyCount;
                for (int ly = 0; ly < cyCount; ++ly) {
                    int worldY = ly << 4;
                    RenderChunk rc = chunks[(zBaseIdx + ly) * cxCount + lx];
                    BlockPos cur = rc.getPosition();
                    if (PureFPSConfig.enabled && cur != null
                            && cur.getX() == worldX && cur.getY() == worldY && cur.getZ() == worldZ) {
                        continue;
                    }
                    BlockPos next = new BlockPos(worldX, worldY, worldZ);
                    if (!next.equals(cur)) {
                        rc.setPosition(next);
                    }
                }
            }
        }
    }
}

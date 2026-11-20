package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastFrustumAccess;
import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frustum Culling P-Vertex 8x en float natif (96 octets en cache L1 au lieu de 192 octets en double) :
 * Cache les 6 plans de frustum en float lors de setPosition(x, y, z) et inline les 6 tests P-Vertex
 * directement dans isBoxInFrustum avec sortie anticipée au plan 0 sans charger les plans suivants.
 */
@Mixin(Frustum.class)
public abstract class MixinFrustum implements FastFrustumAccess {

    @Shadow
    @Final
    private ClippingHelper clippingHelper;

    @Shadow
    private double xPosition;

    @Shadow
    private double yPosition;

    @Shadow
    private double zPosition;

    @Unique
    private boolean purefps$planesReady = false;
    @Unique
    private float purefps$f0x, purefps$f0y, purefps$f0z, purefps$f0w;
    @Unique
    private float purefps$f1x, purefps$f1y, purefps$f1z, purefps$f1w;
    @Unique
    private float purefps$f2x, purefps$f2y, purefps$f2z, purefps$f2w;
    @Unique
    private float purefps$f3x, purefps$f3y, purefps$f3z, purefps$f3w;
    @Unique
    private float purefps$f4x, purefps$f4y, purefps$f4z, purefps$f4w;
    @Unique
    private float purefps$f5x, purefps$f5y, purefps$f5z, purefps$f5w;

    @Inject(method = "setPosition", at = @At("RETURN"))
    private void purefps$cacheScalarFrustumPlanes(double x, double y, double z, CallbackInfo ci) {
        float[][] f = this.clippingHelper.frustum;
        if (f != null && f.length >= 6) {
            float[] p0 = f[0], p1 = f[1], p2 = f[2], p3 = f[3], p4 = f[4], p5 = f[5];
            this.purefps$f0x = p0[0]; this.purefps$f0y = p0[1]; this.purefps$f0z = p0[2]; this.purefps$f0w = p0[3];
            this.purefps$f1x = p1[0]; this.purefps$f1y = p1[1]; this.purefps$f1z = p1[2]; this.purefps$f1w = p1[3];
            this.purefps$f2x = p2[0]; this.purefps$f2y = p2[1]; this.purefps$f2z = p2[2]; this.purefps$f2w = p2[3];
            this.purefps$f3x = p3[0]; this.purefps$f3y = p3[1]; this.purefps$f3z = p3[2]; this.purefps$f3w = p3[3];
            this.purefps$f4x = p4[0]; this.purefps$f4y = p4[1]; this.purefps$f4z = p4[2]; this.purefps$f4w = p4[3];
            this.purefps$f5x = p5[0]; this.purefps$f5y = p5[1]; this.purefps$f5z = p5[2]; this.purefps$f5w = p5[3];
            this.purefps$planesReady = true;
        }
    }

    /**
     * @author PureClient
     * @reason Test P-Vertex scalaire float inliné avec sortie anticipée par plan (96 octets L1, 0 chargement inutile)
     */
    @Overwrite
    public boolean isBoxInFrustum(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double dx1 = minX - this.xPosition;
        double dy1 = minY - this.yPosition;
        double dz1 = minZ - this.zPosition;
        double dx2 = maxX - this.xPosition;
        double dy2 = maxY - this.yPosition;
        double dz2 = maxZ - this.zPosition;

        if (this.purefps$planesReady && PureFPSConfig.enabled && PureFPSConfig.fastFrustumPVertex) {
            float x1 = (float) dx1;
            float y1 = (float) dy1;
            float z1 = (float) dz1;
            float x2 = (float) dx2;
            float y2 = (float) dy2;
            float z2 = (float) dz2;

            float nx = this.purefps$f0x;
            float ny = this.purefps$f0y;
            float nz = this.purefps$f0z;
            if (nx * (nx >= 0.0F ? x2 : x1) + ny * (ny >= 0.0F ? y2 : y1) + nz * (nz >= 0.0F ? z2 : z1) + this.purefps$f0w <= 0.0F) {
                return false;
            }
            nx = this.purefps$f1x; ny = this.purefps$f1y; nz = this.purefps$f1z;
            if (nx * (nx >= 0.0F ? x2 : x1) + ny * (ny >= 0.0F ? y2 : y1) + nz * (nz >= 0.0F ? z2 : z1) + this.purefps$f1w <= 0.0F) {
                return false;
            }
            nx = this.purefps$f2x; ny = this.purefps$f2y; nz = this.purefps$f2z;
            if (nx * (nx >= 0.0F ? x2 : x1) + ny * (ny >= 0.0F ? y2 : y1) + nz * (nz >= 0.0F ? z2 : z1) + this.purefps$f2w <= 0.0F) {
                return false;
            }
            nx = this.purefps$f3x; ny = this.purefps$f3y; nz = this.purefps$f3z;
            if (nx * (nx >= 0.0F ? x2 : x1) + ny * (ny >= 0.0F ? y2 : y1) + nz * (nz >= 0.0F ? z2 : z1) + this.purefps$f3w <= 0.0F) {
                return false;
            }
            nx = this.purefps$f4x; ny = this.purefps$f4y; nz = this.purefps$f4z;
            if (nx * (nx >= 0.0F ? x2 : x1) + ny * (ny >= 0.0F ? y2 : y1) + nz * (nz >= 0.0F ? z2 : z1) + this.purefps$f4w <= 0.0F) {
                return false;
            }
            nx = this.purefps$f5x; ny = this.purefps$f5y; nz = this.purefps$f5z;
            return nx * (nx >= 0.0F ? x2 : x1) + ny * (ny >= 0.0F ? y2 : y1) + nz * (nz >= 0.0F ? z2 : z1) + this.purefps$f5w > 0.0F;
        }
        return this.clippingHelper.isBoxInFrustum(dx1, dy1, dz1, dx2, dy2, dz2);
    }

    @Override
    public boolean purefps$isSphereOrBoxInFrustum(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return this.isBoxInFrustum(minX, minY, minZ, maxX, maxY, maxZ);
    }
}

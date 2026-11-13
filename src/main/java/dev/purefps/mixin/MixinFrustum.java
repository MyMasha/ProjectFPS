package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastFrustumAccess;
import dev.purefps.core.FboBlitHelper;
import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frustum Culling P-Vertex 8x :
 * Cache les 6 plans de frustum en champs scalaires primitifs lors de setPosition(x, y, z)
 * et teste uniquement le sommet P-Vertex optimal par plan (6 produits scalaires max au lieu de 48).
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

    private boolean purefps$planesReady = false;
    private double purefps$f0x, purefps$f0y, purefps$f0z, purefps$f0w;
    private double purefps$f1x, purefps$f1y, purefps$f1z, purefps$f1w;
    private double purefps$f2x, purefps$f2y, purefps$f2z, purefps$f2w;
    private double purefps$f3x, purefps$f3y, purefps$f3z, purefps$f3w;
    private double purefps$f4x, purefps$f4y, purefps$f4z, purefps$f4w;
    private double purefps$f5x, purefps$f5y, purefps$f5z, purefps$f5w;

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
     * @reason Test P-Vertex scalaire mathématiquement exact (jusqu'à 8x moins de multiplications que ClippingHelper)
     */
    @Overwrite
    public boolean isBoxInFrustum(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double x1 = minX - this.xPosition;
        double y1 = minY - this.yPosition;
        double z1 = minZ - this.zPosition;
        double x2 = maxX - this.xPosition;
        double y2 = maxY - this.yPosition;
        double z2 = maxZ - this.zPosition;

        if (this.purefps$planesReady && PureFPSConfig.enabled && PureFPSConfig.fastFrustumPVertex) {
            return FboBlitHelper.isBoxInFrustumPVertex(
                    x1, y1, z1, x2, y2, z2,
                    this.purefps$f0x, this.purefps$f0y, this.purefps$f0z, this.purefps$f0w,
                    this.purefps$f1x, this.purefps$f1y, this.purefps$f1z, this.purefps$f1w,
                    this.purefps$f2x, this.purefps$f2y, this.purefps$f2z, this.purefps$f2w,
                    this.purefps$f3x, this.purefps$f3y, this.purefps$f3z, this.purefps$f3w,
                    this.purefps$f4x, this.purefps$f4y, this.purefps$f4z, this.purefps$f4w,
                    this.purefps$f5x, this.purefps$f5y, this.purefps$f5z, this.purefps$f5w
            );
        }
        return this.clippingHelper.isBoxInFrustum(x1, y1, z1, x2, y2, z2);
    }

    @Override
    public boolean purefps$isSphereOrBoxInFrustum(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return this.isBoxInFrustum(minX, minY, minZ, maxX, maxY, maxZ);
    }
}

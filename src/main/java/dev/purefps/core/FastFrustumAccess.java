package dev.purefps.core;

/**
 * Accès direct à Frustum.isBoxInFrustum(minX, minY, minZ, maxX, maxY, maxZ) sans allouer d'AxisAlignedBB.
 */
public interface FastFrustumAccess {
    boolean purefps$isSphereOrBoxInFrustum(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
}

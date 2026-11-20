package dev.purefps.core;

/**
 * Permet la mutation en place des bornes d'une AxisAlignedBB privée (particules client EntityFX,
 * boîtes de balayage temporaires) pour supprimer 100 % des allocations AxisAlignedBB par tick.
 */
public interface FastMutableAABB {
    void purefps$setBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
}

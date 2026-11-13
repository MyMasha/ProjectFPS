package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.LightmapStateCache;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.ARBMultitexture;
import org.lwjgl.opengl.GL13;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Élimine les appels JNI glMultiTexCoord2f redondants sur la lightmap SANS allouer de CallbackInfo.
 */
@Mixin(OpenGlHelper.class)
public class MixinOpenGlHelper {

    @Shadow
    private static boolean arbMultitexture;

    @Shadow
    public static int lightmapTexUnit;

    @Shadow(remap = false)
    public static float lastBrightnessX;

    @Shadow(remap = false)
    public static float lastBrightnessY;

    /**
     * @author PureFPS
     * @reason Déduplication directe zéro-allocation des coordonnées lightmap OpenGL
     */
    @Overwrite
    public static void setLightmapTextureCoords(int target, float x, float y) {
        if (target == lightmapTexUnit) {
            lastBrightnessX = x;
            lastBrightnessY = y;
            if (PureFPSConfig.enabled && PureFPSConfig.fastLightmapCoords && LightmapStateCache.checkAndUpdate(x, y)) {
                return;
            }
        }
        if (arbMultitexture) {
            ARBMultitexture.glMultiTexCoord2fARB(target, x, y);
        } else {
            GL13.glMultiTexCoord2f(target, x, y);
        }
    }
}

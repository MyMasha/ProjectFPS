package dev.purefps.client;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

/**
 * Contrôleur de Zoom fluide (touche C + molette 2x..14x type Lunar/Badlion) sans surcharge OptiFine.
 */
public final class ZoomController {

    private static boolean zooming = false;
    private static boolean savedSmoothCamera = false;
    private static boolean appliedSmoothCamera = false;
    private static float targetDivisor = 4.0F;
    private static float currentMultiplier = 1.0F;
    private static long lastNano = 0L;

    private ZoomController() {
    }

    public static boolean isZooming() {
        return zooming;
    }

    public static void onMouseScroll(int dwheel) {
        if (!zooming || dwheel == 0) {
            return;
        }
        if (dwheel > 0) {
            targetDivisor = Math.min(14.0F, targetDivisor * 1.18F);
        } else {
            targetDivisor = Math.max(2.0F, targetDivisor / 1.18F);
        }
    }

    public static float applyFov(Minecraft mc, float baseFov) {
        boolean holdZoom = PureFPSConfig.enabled
                && PureFPSConfig.zoomEnabled
                && mc != null
                && mc.currentScreen == null
                && Keyboard.isCreated()
                && Keyboard.isKeyDown(Keyboard.KEY_C);

        if (holdZoom && !zooming) {
            zooming = true;
            targetDivisor = 4.0F;
            if (mc.gameSettings != null && PureFPSConfig.zoomSmoothCamera) {
                savedSmoothCamera = mc.gameSettings.smoothCamera;
                mc.gameSettings.smoothCamera = true;
                appliedSmoothCamera = true;
            }
        } else if (!holdZoom && zooming) {
            zooming = false;
            if (appliedSmoothCamera && mc != null && mc.gameSettings != null) {
                mc.gameSettings.smoothCamera = savedSmoothCamera;
                appliedSmoothCamera = false;
            }
        }

        float desired = zooming ? (1.0F / targetDivisor) : 1.0F;
        long now = System.nanoTime();
        float dt = lastNano == 0L ? 0.016F : (float) (now - lastNano) * 1.0e-9F;
        lastNano = now;
        if (dt < 0.0F || dt > 0.1F) {
            dt = 0.016F;
        }

        float diff = desired - currentMultiplier;
        if (Math.abs(diff) < 0.0015F) {
            currentMultiplier = desired;
        } else {
            float speed = 1.0F - (float) Math.exp(-18.0F * dt);
            currentMultiplier += diff * speed;
        }

        return baseFov * currentMultiplier;
    }

    public static float getCurrentMultiplier() {
        return currentMultiplier;
    }
}

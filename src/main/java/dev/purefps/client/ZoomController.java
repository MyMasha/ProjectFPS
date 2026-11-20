package dev.purefps.client;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.module.MashFlags;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Contrôleur de Zoom Optique Pro MashClient 4.0 :
 * - Touche ou bouton souris configurable (MashFlags.zoomKeyCode)
 * - Choix explicite d'utiliser la molette pour zoomer (MashFlags.zoomScrollEnabled = true)
 *   OU de laisser la molette changer de slot dans la barre rapide normalement (= false) !
 * - Facteur initial, zoom max, vitesse d'animation, caméra cinématique et sensibilité souris dédiée.
 */
public final class ZoomController {

    private static boolean zooming = false;
    private static boolean savedSmoothCamera = false;
    private static boolean appliedSmoothCamera = false;
    private static float savedMouseSensitivity = 0.5F;
    private static boolean appliedSensitivity = false;

    private static float targetDivisor = 4.0F;
    private static float currentMultiplier = 1.0F;
    private static long lastNano = 0L;

    private ZoomController() {
    }

    public static boolean isZooming() {
        return zooming;
    }

    public static boolean shouldCaptureMouseWheel() {
        return zooming && MashFlags.zoomScrollEnabled;
    }

    public static void onMouseScroll(int dwheel) {
        if (!zooming || !MashFlags.zoomScrollEnabled || dwheel == 0) {
            return;
        }
        float step = Math.max(1.05F, MashFlags.zoomScrollStep);
        float maxDiv = Math.max(2.0F, MashFlags.zoomMaxDivisor);
        if (dwheel > 0) {
            targetDivisor = Math.min(maxDiv, targetDivisor * step);
        } else {
            targetDivisor = Math.max(1.25F, targetDivisor / step);
        }
    }

    private static boolean isKeybindHeld(int keyCode) {
        if (keyCode == 0) {
            return false;
        }
        if (keyCode <= -85) {
            int btn = keyCode + 100;
            return btn >= 0 && btn < 16 && Mouse.isCreated() && Mouse.isButtonDown(btn);
        }
        return keyCode > 0 && keyCode < 256 && Keyboard.isCreated() && Keyboard.isKeyDown(keyCode);
    }

    public static float applyFov(Minecraft mc, float baseFov) {
        boolean holdZoom = PureFPSConfig.enabled
                && MashFlags.zoomEnabled
                && mc != null
                && mc.currentScreen == null
                && isKeybindHeld(MashFlags.zoomKeyCode);

        if (!holdZoom && !zooming && currentMultiplier == 1.0F) {
            return baseFov;
        }

        if (holdZoom && !zooming) {
            zooming = true;
            targetDivisor = Math.max(1.25F, MashFlags.zoomInitialDivisor);
            if (mc.gameSettings != null) {
                if (MashFlags.zoomSmoothCamera) {
                    savedSmoothCamera = mc.gameSettings.smoothCamera;
                    mc.gameSettings.smoothCamera = true;
                    appliedSmoothCamera = true;
                }
                if (MashFlags.zoomSensitivityPct < 99.0F) {
                    savedMouseSensitivity = mc.gameSettings.mouseSensitivity;
                    mc.gameSettings.mouseSensitivity = savedMouseSensitivity * (MashFlags.zoomSensitivityPct * 0.01F);
                    appliedSensitivity = true;
                }
            }
        } else if (!holdZoom && zooming) {
            zooming = false;
            if (mc != null && mc.gameSettings != null) {
                if (appliedSmoothCamera) {
                    mc.gameSettings.smoothCamera = savedSmoothCamera;
                    appliedSmoothCamera = false;
                }
                if (appliedSensitivity) {
                    mc.gameSettings.mouseSensitivity = savedMouseSensitivity;
                    appliedSensitivity = false;
                }
            }
        }

        float desired = zooming ? (1.0F / Math.max(1.0F, targetDivisor)) : 1.0F;
        if (!MashFlags.zoomSmoothTransition) {
            currentMultiplier = desired;
            lastNano = System.nanoTime();
            return baseFov * currentMultiplier;
        }

        long now = System.nanoTime();
        float dt = lastNano == 0L ? 0.016F : (float) (now - lastNano) * 1.0e-9F;
        lastNano = now;
        if (dt < 0.0F || dt > 0.1F) {
            dt = 0.016F;
        }

        float diff = desired - currentMultiplier;
        if (Math.abs(diff) < 0.0012F) {
            currentMultiplier = desired;
        } else {
            float spd = Math.max(4.0F, MashFlags.zoomTransitionSpeed);
            float alpha = 1.0F - (float) Math.exp(-spd * dt);
            currentMultiplier += diff * alpha;
        }

        return baseFov * currentMultiplier;
    }

    public static float getCurrentMultiplier() {
        return currentMultiplier;
    }
}

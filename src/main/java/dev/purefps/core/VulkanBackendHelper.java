package dev.purefps.core;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.util.Locale;

/**
 * Détection sans allocation du backend de rendu MashClient 4.5 :
 * - Standard : OpenGL natif (1.8.9 PureFPS)
 * - Bêta : Traduction OpenGL -> Vulkan via Mesa3D Zink (1.8.9 PureFPS Vulkan [Bêta])
 */
public final class VulkanBackendHelper {

    public static final String BACKEND_PROP = "purefps.rendererBackend";

    private static int cachedZinkState = 0; // 0 = inconnu, 1 = Zink actif, -1 = OpenGL natif
    private static String cachedRendererString = "";

    private VulkanBackendHelper() {
    }

    public static boolean isVulkanFlagSet() {
        String prop = System.getProperty(BACKEND_PROP);
        return prop != null && "vulkan".equalsIgnoreCase(prop.trim());
    }

    public static boolean isVulkanActive() {
        if (cachedZinkState == 0) {
            try {
                if (Display.isCreated()) {
                    String renderer = GL11.glGetString(GL11.GL_RENDERER);
                    if (renderer != null) {
                        cachedRendererString = renderer;
                        String lower = renderer.toLowerCase(Locale.ROOT);
                        cachedZinkState = (lower.contains("zink") || lower.contains("vulkan")) ? 1 : -1;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return cachedZinkState == 1 || isVulkanFlagSet();
    }

    public static String getCachedRendererName() {
        isVulkanActive();
        return cachedRendererString;
    }

    public static String getWindowTitle(String version) {
        if (isVulkanActive()) {
            String badge = dev.purefps.config.PureFPSConfig.isEnglish() ? "[Vulkan Beta]" : "[Vulkan Bêta]";
            return "MashClient " + version + " " + badge + " | Minecraft 1.8.9";
        }
        return "MashClient " + version + " | Minecraft 1.8.9";
    }

    public static String getF3BackendBadge() {
        String beta = dev.purefps.config.PureFPSConfig.isEnglish() ? "Vulkan Beta" : "Vulkan Bêta";
        if (cachedZinkState == 1) {
            return "\u00a7d" + beta + " (Zink)";
        }
        if (isVulkanFlagSet()) {
            return "\u00a7d" + beta;
        }
        return "\u00a7aOpenGL";
    }
}

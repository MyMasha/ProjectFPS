package dev.purefps.config;

import java.util.Locale;

/**
 * Profils de performance prédéfinis pour PureFPS :
 * - FPS      : Compétitif / Max FPS absolu (PvP, Hypixel BedWars/SkyBlock, culling agressif + Fast Graphics).
 * - BALANCED : Équilibré (visuels soignés + très hauts FPS constants, recommandé par défaut).
 * - QUALITY  : Qualité 100 % fidèle Vanilla (zéro compromis visuel, garde toutes les optimisations CPU/GPU sans perte).
 * - CUSTOM   : Activé automatiquement dès qu'un réglage individuel est personnalisé manuellement.
 */
public enum PureFPSProfile {
    FPS("fps", "FPS", "\u00a7a", "Max FPS & PvP Compétitif (Culling fort, Fast Graphics, 0 VSync)"),
    BALANCED("balanced", "Balanced", "\u00a7e", "Équilibré (Visuels propres + Hauts FPS stables)"),
    QUALITY("quality", "Quality", "\u00a7b", "Fidélité 100% Vanilla + Moteur Zéro-Allocation sans perte"),
    CUSTOM("custom", "Custom", "\u00a7d", "Configuration personnalisée manuellement");

    private final String id;
    private final String displayName;
    private final String colorCode;
    private final String description;

    PureFPSProfile(String id, String displayName, String colorCode, String description) {
        this.id = id;
        this.displayName = displayName;
        this.colorCode = colorCode;
        this.description = description;
    }

    public String getId() {
        return this.id;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public String getColorCode() {
        return this.colorCode;
    }

    public String getColoredName() {
        return this.colorCode + this.displayName;
    }

    public String getDescription() {
        return this.description;
    }

    public static PureFPSProfile fromString(String raw) {
        if (raw == null) {
            return BALANCED;
        }
        String norm = raw.trim().toLowerCase(Locale.ENGLISH);
        if ("fps".equals(norm) || "maxfps".equals(norm) || "performance".equals(norm) || "perf".equals(norm)) {
            return FPS;
        }
        if ("balanced".equals(norm) || "equilibre".equals(norm) || "normal".equals(norm)) {
            return BALANCED;
        }
        if ("quality".equals(norm) || "qualite".equals(norm) || "ultra".equals(norm) || "fancy".equals(norm)) {
            return QUALITY;
        }
        if ("custom".equals(norm)) {
            return CUSTOM;
        }
        return BALANCED;
    }
}

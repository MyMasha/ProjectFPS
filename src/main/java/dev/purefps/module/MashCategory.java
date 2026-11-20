package dev.purefps.module;

import dev.purefps.config.PureFPSConfig;

public enum MashCategory {
    ALL("All", "Tous", 0xFFF5B85C),
    HUD("HUD", "HUD", 0xFF38BDF8),
    PVP("PvP", "PvP", 0xFFF87171),
    VISUAL("Visual", "Visuel", 0xFFA78BFA),
    HYPIXEL("Hypixel", "Hypixel", 0xFFFBBF24),
    WORLD("World", "Monde", 0xFF34D399),
    ENGINE("Performance", "Performance", 0xFF60A5FA);

    private final String englishName;
    private final String frenchName;
    private final int accentArgb;

    MashCategory(String englishName, String frenchName, int accentArgb) {
        this.englishName = englishName;
        this.frenchName = frenchName;
        this.accentArgb = accentArgb;
    }

    public String getDisplayName() {
        return PureFPSConfig.isEnglish() ? englishName : frenchName;
    }

    public int getAccentArgb() {
        return accentArgb;
    }
}

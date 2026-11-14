package dev.purefps.module;

public enum MashCategory {
    ALL("Tous", 0xFFF5B85C),
    HUD("HUD", 0xFF38BDF8),
    PVP("PvP", 0xFFF87171),
    VISUAL("Visuel", 0xFFA78BFA),
    HYPIXEL("Hypixel", 0xFFFBBF24),
    WORLD("Monde", 0xFF34D399),
    ENGINE("Moteur FPS", 0xFF60A5FA);

    private final String displayName;
    private final int accentArgb;

    MashCategory(String displayName, int accentArgb) {
        this.displayName = displayName;
        this.accentArgb = accentArgb;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getAccentArgb() {
        return accentArgb;
    }
}

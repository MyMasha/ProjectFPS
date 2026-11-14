package dev.purefps.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Représentation d'un module MashClient 4.0 (HUD déplaçable ou Module Client/PvP/Visuel/Hypixel/Monde/Moteur) :
 * - Chaque module dispose de ses propres sous-paramètres accessibles par clic droit dans le menu RSHIFT.
 * - Chaque module HUD possède sa position (hudX, hudY), sa boîte englobante en direct (hudW, hudH) et ses réglages
 *   universels de style (échelle, fond, opacité, arrondi, bordure, ombre texte, couleur texte/accent, chroma).
 */
public final class MashModule {

    private final String id;
    private final String name;
    private final String description;
    private final String searchIndex;
    private final MashCategory category;
    private final boolean defaultEnabled;
    private final boolean hudWidget;

    private boolean enabled;
    private boolean favorite;

    // Coordonnées et dimensions HUD (en pixels ScaledResolution)
    private final float defaultHudX;
    private final float defaultHudY;
    private float hudX;
    private float hudY;
    private float baseHudW;
    private float baseHudH;

    // Sous-paramètres universels pour les widgets HUD
    public final MashSetting.NumberSetting hudScale;
    public final MashSetting.BooleanSetting hudShowBg;
    public final MashSetting.NumberSetting hudBgOpacity;
    public final MashSetting.NumberSetting hudCornerRadius;
    public final MashSetting.BooleanSetting hudShowBorder;
    public final MashSetting.BooleanSetting hudTextShadow;
    public final MashSetting.ColorSetting hudTextColor;
    public final MashSetting.ColorSetting hudAccentColor;

    private final List<MashSetting> settings = new ArrayList<>();

    public MashModule(
            String id,
            String name,
            String description,
            String keywords,
            MashCategory category,
            boolean defaultEnabled,
            boolean hudWidget,
            float defaultHudX,
            float defaultHudY,
            float initialW,
            float initialH
    ) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
        this.defaultEnabled = defaultEnabled;
        this.enabled = defaultEnabled;
        this.hudWidget = hudWidget;
        this.defaultHudX = defaultHudX;
        this.defaultHudY = defaultHudY;
        this.hudX = defaultHudX;
        this.hudY = defaultHudY;
        this.baseHudW = Math.max(24.0F, initialW);
        this.baseHudH = Math.max(14.0F, initialH);

        String combined = (name + " " + description + " " + (keywords != null ? keywords : "") + " " + id)
                .toLowerCase(Locale.ROOT);
        this.searchIndex = stripAccents(combined);

        if (hudWidget) {
            this.hudScale = addSetting(new MashSetting.NumberSetting("hud_scale", "Échelle du widget", "Taille globale de l'élément sur l'écran", 1.0F, 0.50F, 2.00F, 0.05F, "x"));
            this.hudShowBg = addSetting(new MashSetting.BooleanSetting("hud_bg", "Afficher l'arrière-plan", "Carte obsidienne fumée derrière le texte", true));
            this.hudBgOpacity = addSetting(new MashSetting.NumberSetting("hud_bg_alpha", "Opacité du fond", "Transparence de la carte HUD", 80.0F, 0.0F, 100.0F, 5.0F, "%"));
            this.hudCornerRadius = addSetting(new MashSetting.NumberSetting("hud_radius", "Rayon des coins", "Arrondi vectoriel SDF des angles", 5.5F, 0.0F, 12.0F, 0.5F, "px"));
            this.hudShowBorder = addSetting(new MashSetting.BooleanSetting("hud_border", "Bordure subtile", "Fine bordure lumineuse autour du widget", true));
            this.hudTextShadow = addSetting(new MashSetting.BooleanSetting("hud_shadow", "Ombre du texte", "Améliore la lisibilité sur ciel clair", true));
            this.hudTextColor = addSetting(new MashSetting.ColorSetting("hud_text_col", "Couleur du texte", "Couleur principale et option Chroma", 0xFFF2F4F8, false));
            this.hudAccentColor = addSetting(new MashSetting.ColorSetting("hud_accent_col", "Couleur d'accent", "Pastille et indicateurs du widget", 0xFFF5B85C, false));
        } else {
            this.hudScale = null;
            this.hudShowBg = null;
            this.hudBgOpacity = null;
            this.hudCornerRadius = null;
            this.hudShowBorder = null;
            this.hudTextShadow = null;
            this.hudTextColor = null;
            this.hudAccentColor = null;
        }
    }

    public <T extends MashSetting> T addSetting(T setting) {
        this.settings.add(setting);
        return setting;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public MashCategory getCategory() {
        return category;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        if (this.enabled != value) {
            this.enabled = value;
            MashModules.onModuleToggled(this);
        }
    }

    public void setEnabledSilent(boolean value) {
        this.enabled = value;
    }

    public void toggle() {
        setEnabled(!this.enabled);
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void toggleFavorite() {
        this.favorite = !this.favorite;
        MashModules.markDirty();
    }

    public boolean isHudWidget() {
        return hudWidget;
    }

    public float getHudX() {
        return hudX;
    }

    public float getHudY() {
        return hudY;
    }

    public void setHudPos(float x, float y) {
        this.hudX = x;
        this.hudY = y;
        MashModules.markDirty();
    }

    public void setHudPosSilent(float x, float y) {
        this.hudX = x;
        this.hudY = y;
    }

    public float getScale() {
        return hudScale != null ? hudScale.get() : 1.0F;
    }

    public void setBaseHudSize(float w, float h) {
        this.baseHudW = Math.max(18.0F, w);
        this.baseHudH = Math.max(12.0F, h);
    }

    public float getBaseHudW() {
        return baseHudW;
    }

    public float getBaseHudH() {
        return baseHudH;
    }

    public float getScaledHudW() {
        return baseHudW * getScale();
    }

    public float getScaledHudH() {
        return baseHudH * getScale();
    }

    public List<MashSetting> getSettings() {
        return settings;
    }

    public MashSetting findSetting(String settingId) {
        for (int i = 0; i < settings.size(); i++) {
            MashSetting s = settings.get(i);
            if (s.getId().equals(settingId)) {
                return s;
            }
        }
        return null;
    }

    public boolean matchesQuery(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isEmpty()) {
            return true;
        }
        return searchIndex.contains(normalizedQuery);
    }

    public void resetDefaults() {
        this.enabled = this.defaultEnabled;
        this.hudX = this.defaultHudX;
        this.hudY = this.defaultHudY;
        for (int i = 0; i < settings.size(); i++) {
            settings.get(i).resetToDefault();
        }
        MashModules.onModuleToggled(this);
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("favorite", favorite);
        if (hudWidget) {
            obj.addProperty("x", hudX);
            obj.addProperty("y", hudY);
        }
        JsonObject cfg = new JsonObject();
        for (int i = 0; i < settings.size(); i++) {
            settings.get(i).writeJson(cfg);
        }
        obj.add("settings", cfg);
        return obj;
    }

    public void fromJson(JsonObject obj) {
        if (obj == null) {
            return;
        }
        JsonElement en = obj.get("enabled");
        if (en != null && en.isJsonPrimitive()) {
            try {
                this.enabled = en.getAsBoolean();
            } catch (Throwable ignored) {
            }
        }
        JsonElement fav = obj.get("favorite");
        if (fav != null && fav.isJsonPrimitive()) {
            try {
                this.favorite = fav.getAsBoolean();
            } catch (Throwable ignored) {
            }
        }
        if (hudWidget) {
            JsonElement xEl = obj.get("x");
            JsonElement yEl = obj.get("y");
            if (xEl != null && xEl.isJsonPrimitive()) {
                try {
                    this.hudX = Math.max(0.0F, Math.min(3840.0F, xEl.getAsFloat()));
                } catch (Throwable ignored) {
                }
            }
            if (yEl != null && yEl.isJsonPrimitive()) {
                try {
                    this.hudY = Math.max(0.0F, Math.min(2160.0F, yEl.getAsFloat()));
                } catch (Throwable ignored) {
                }
            }
        }
        JsonElement cfgEl = obj.get("settings");
        if (cfgEl != null && cfgEl.isJsonObject()) {
            JsonObject cfg = cfgEl.getAsJsonObject();
            for (int i = 0; i < settings.size(); i++) {
                settings.get(i).readJson(cfg);
            }
        }
    }

    public static String stripAccents(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = Character.toLowerCase(input.charAt(i));
            switch (c) {
                case 'à': case 'â': case 'ä': sb.append('a'); break;
                case 'é': case 'è': case 'ê': case 'ë': sb.append('e'); break;
                case 'î': case 'ï': sb.append('i'); break;
                case 'ô': case 'ö': sb.append('o'); break;
                case 'ù': case 'û': case 'ü': sb.append('u'); break;
                case 'ç': sb.append('c'); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }
}

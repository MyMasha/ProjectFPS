package dev.purefps.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Représentation d'un module MashClient 4.1 (HUD déplaçable ou Module Client/PvP/Visuel/Hypixel/Monde/Moteur) :
 * - Chaque module dispose d'un nom français et d'un nom officiel anglais (Lunar / Badlion) pour une recherche bilingue.
 * - Chaque module dispose de ses propres sous-paramètres accessibles par clic droit dans le menu RSHIFT.
 * - Chaque module HUD possède sa position (hudX, hudY), sa boîte englobante en direct (hudW, hudH) et ses réglages
 *   universels de style (échelle, fond, opacité, arrondi, bordure, ombre texte, couleur texte/accent, chroma).
 */
public final class MashModule {

    private final String id;
    private final String name;
    private final String englishName;
    private final String description;
    private final MashCategory category;
    private final boolean defaultEnabled;
    private final boolean hudWidget;

    // Index bilingues pré-calculés pour la recherche FR + EN pertinente
    private final String normEnglish;
    private final String compactEnglish;
    private final String[] englishWords;
    private final String normFrench;
    private final String compactFrench;
    private final String[] frenchWords;
    private final String normAliases;
    private final String compactAliases;
    private final String[] aliasWords;
    private final String normId;
    private final String compactId;
    private final String normFull;
    private final String compactFull;
    private final String[] fullWords;

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

        MashEnglishCatalog.Entry catEntry = MashEnglishCatalog.lookup(id);
        String resolvedEn = catEntry != null ? catEntry.englishName : humanizeIdFallback(id);
        String extraEn = catEntry != null ? catEntry.englishAliases : "";
        String descEn = catEntry != null ? catEntry.englishDesc : "";
        this.englishName = resolvedEn;

        String strippedId = stripCategoryPrefix(id);
        this.normEnglish = normalizeSearch(resolvedEn);
        this.compactEnglish = removeSpaces(this.normEnglish);
        this.englishWords = splitWords(this.normEnglish);

        this.normFrench = normalizeSearch(name);
        this.compactFrench = removeSpaces(this.normFrench);
        this.frenchWords = splitWords(this.normFrench);

        this.normAliases = normalizeSearch(extraEn + " " + (keywords != null ? keywords : "") + " " + strippedId);
        this.compactAliases = removeSpaces(this.normAliases);
        this.aliasWords = splitWords(this.normAliases);

        this.normId = normalizeSearch(strippedId);
        this.compactId = removeSpaces(this.normId);

        String catKeywords = category == MashCategory.HUD ? "hud widget display overlay"
                : (category == MashCategory.PVP ? "pvp combat camera"
                : (category == MashCategory.VISUAL ? "visual render ui"
                : (category == MashCategory.HYPIXEL ? "hypixel bedwars skywars chat"
                : (category == MashCategory.WORLD ? "world window audio"
                : "fps engine gpu cpu boost performance"))));

        this.normFull = normalizeSearch(
                resolvedEn + " " + extraEn + " " + descEn + " " + name + " "
                        + (keywords != null ? keywords : "") + " "
                        + strippedId + " " + id + " " + description + " " + catKeywords
        );
        this.compactFull = removeSpaces(this.normFull);
        this.fullWords = splitWords(this.normFull);

        if (hudWidget) {
            this.hudScale = addSetting(new MashSetting.NumberSetting("hud_scale", "Échelle", "Taille de l'élément à l'écran", 1.0F, 0.50F, 2.00F, 0.05F, "x"));
            this.hudShowBg = addSetting(new MashSetting.BooleanSetting("hud_bg", "Arrière-plan", "Fond sombre derrière le texte", true));
            this.hudBgOpacity = addSetting(new MashSetting.NumberSetting("hud_bg_alpha", "Opacité du fond", "Transparence du fond", 80.0F, 0.0F, 100.0F, 5.0F, "%"));
            this.hudCornerRadius = addSetting(new MashSetting.NumberSetting("hud_radius", "Arrondi", "Rayon des angles", 5.5F, 0.0F, 12.0F, 0.5F, "px"));
            this.hudShowBorder = addSetting(new MashSetting.BooleanSetting("hud_border", "Bordure", "Contour fin autour du widget", true));
            this.hudTextShadow = addSetting(new MashSetting.BooleanSetting("hud_shadow", "Ombre du texte", "Ombre portée sous les caractères", true));
            this.hudTextColor = addSetting(new MashSetting.ColorSetting("hud_text_col", "Couleur du texte", "Couleur principale ou mode Chroma", 0xFFF2F4F8, false));
            this.hudAccentColor = addSetting(new MashSetting.ColorSetting("hud_accent_col", "Couleur d'accent", "Couleur des repères du widget", 0xFFF5B85C, false));
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

    public String getEnglishName() {
        return englishName;
    }

    public String getDisplayName() {
        return dev.purefps.config.PureFPSConfig.isEnglish() ? englishName : name;
    }

    public String getDescription() {
        return dev.purefps.config.PureFPSConfig.isEnglish()
                ? MashEnglishCatalog.translateDescription(id, description)
                : description;
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

    public boolean matchesQuery(String rawQuery) {
        return scoreQuery(rawQuery) > 0;
    }

    /**
     * Calcule un score de pertinence bilingue (Anglais Lunar/Badlion + Français) :
     * - 0 = aucune correspondance
     * - Plus le score est élevé, plus le module correspond directement à la recherche (exact > préfixe > mots > alias).
     */
    public int scoreQuery(String rawQuery) {
        if (rawQuery == null) {
            return 1;
        }
        String q = normalizeSearch(rawQuery);
        if (q.isEmpty()) {
            return 1;
        }
        String qCompact = removeSpaces(q);
        int score = 0;

        // 1. Correspondances directes sur les titres anglais, français et l'identifiant
        if (normEnglish.equals(q) || (!qCompact.isEmpty() && compactEnglish.equals(qCompact))) {
            score += 1500;
        } else if (normEnglish.startsWith(q) || (qCompact.length() >= 2 && compactEnglish.startsWith(qCompact))) {
            score += 950;
        } else if (normEnglish.contains(q) || (qCompact.length() >= 3 && compactEnglish.contains(qCompact))) {
            score += 680;
        }

        if (normFrench.equals(q) || (!qCompact.isEmpty() && compactFrench.equals(qCompact))) {
            score += 1450;
        } else if (normFrench.startsWith(q) || (qCompact.length() >= 2 && compactFrench.startsWith(qCompact))) {
            score += 900;
        } else if (normFrench.contains(q) || (qCompact.length() >= 3 && compactFrench.contains(qCompact))) {
            score += 640;
        }

        if (normId.equals(q) || (!qCompact.isEmpty() && compactId.equals(qCompact))) {
            score += 1350;
        } else if (qCompact.length() >= 3 && compactId.contains(qCompact)) {
            score += 520;
        }

        boolean compoundInFull = qCompact.length() >= 4 && compactFull.contains(qCompact);
        if (compoundInFull) {
            score += 360;
        }

        // 2. Analyse multi-mots (indépendante de l'ordre, tolérante aux pluriels, mots soudés et fautes de frappe)
        String[] qTokens = splitWords(q);
        int matchedCoreTokens = 0;
        int requiredCoreTokens = 0;

        for (int i = 0; i < qTokens.length; i++) {
            String tok = qTokens[i];
            if (tok.isEmpty()) {
                continue;
            }
            boolean optionalModifier = qTokens.length > 1 && isGenericUiModifier(tok);
            if (!optionalModifier) {
                requiredCoreTokens++;
            }

            int tokScore = matchSingleToken(tok);
            if (tokScore > 0) {
                score += tokScore;
                if (!optionalModifier) {
                    matchedCoreTokens++;
                }
            } else if (!optionalModifier && !compoundInFull) {
                return 0;
            }
        }

        if (requiredCoreTokens == 0 && score == 0) {
            return 0;
        }
        if (requiredCoreTokens > 0 && matchedCoreTokens == 0 && !compoundInFull) {
            return 0;
        }
        return Math.max(score, 1);
    }

    private int matchSingleToken(String tok) {
        int len = tok.length();
        // 1. Correspondances exactes sur mot entier (priorité absolue : Titre EN > Titre FR > Alias/Mots-clés EN/FR)
        if (containsExactWord(tok, englishWords)) {
            return 280;
        }
        if (containsExactWord(tok, frenchWords)) {
            return 260;
        }
        if (containsExactWord(tok, aliasWords)) {
            return 230;
        }

        // 2. Préfixe de mot ou mot composé soudé dans le titre anglais / français / alias
        int enPrefix = matchPrefixInWordList(tok, englishWords, 195);
        if (enPrefix > 0) {
            return enPrefix;
        }
        if (len >= 3 && compactEnglish.contains(tok)) {
            return 175;
        }

        int frPrefix = matchPrefixInWordList(tok, frenchWords, 180);
        if (frPrefix > 0) {
            return frPrefix;
        }
        if (len >= 3 && compactFrench.contains(tok)) {
            return 155;
        }

        int aliasPrefix = matchPrefixInWordList(tok, aliasWords, 145);
        if (aliasPrefix > 0) {
            return aliasPrefix;
        }
        if (len >= 3 && compactAliases.contains(tok)) {
            return 130;
        }

        // 3. Description et index complet
        if (containsExactWord(tok, fullWords)) {
            return 105;
        }
        int fullPrefix = matchPrefixInWordList(tok, fullWords, 75);
        if (fullPrefix > 0) {
            return fullPrefix;
        }
        if (len >= 3 && (normFull.contains(tok) || compactId.contains(tok))) {
            return 60;
        }

        // 4. Racine singulier / pluriel anglais & français ("hitboxes" -> "hitbox", "potions" -> "potion")
        if (len >= 4 && tok.charAt(len - 1) == 's') {
            String stem1 = tok.substring(0, len - 1);
            if (containsExactWord(stem1, englishWords) || matchPrefixInWordList(stem1, englishWords, 1) > 0) {
                return 165;
            }
            if (containsExactWord(stem1, frenchWords) || containsExactWord(stem1, aliasWords)) {
                return 135;
            }
            if (containsExactWord(stem1, fullWords)) {
                return 90;
            }
            if (len >= 5 && tok.endsWith("es")) {
                String stem2 = tok.substring(0, len - 2);
                if (containsExactWord(stem2, englishWords) || matchPrefixInWordList(stem2, englishWords, 1) > 0) {
                    return 165;
                }
                if (containsExactWord(stem2, frenchWords) || containsExactWord(stem2, aliasWords)) {
                    return 135;
                }
            }
        }

        // 5. Tolérance d'une faute de frappe (distance d'édition <= 1) sur les mots d'au moins 5 lettres
        if (len >= 5) {
            for (int i = 0; i < englishWords.length; i++) {
                if (englishWords[i].length() >= 4 && isWithinOneEdit(tok, englishWords[i])) {
                    return 115;
                }
            }
            for (int i = 0; i < frenchWords.length; i++) {
                if (frenchWords[i].length() >= 4 && isWithinOneEdit(tok, frenchWords[i])) {
                    return 105;
                }
            }
            for (int i = 0; i < aliasWords.length; i++) {
                if (aliasWords[i].length() >= 5 && isWithinOneEdit(tok, aliasWords[i])) {
                    return 85;
                }
            }
            for (int i = 0; i < fullWords.length; i++) {
                if (fullWords[i].length() >= 5 && isWithinOneEdit(tok, fullWords[i])) {
                    return 50;
                }
            }
        }
        return 0;
    }

    private static boolean containsExactWord(String tok, String[] words) {
        for (int i = 0; i < words.length; i++) {
            if (words[i].equals(tok)) {
                return true;
            }
        }
        return false;
    }

    private static int matchPrefixInWordList(String tok, String[] words, int prefixScore) {
        for (int i = 0; i < words.length; i++) {
            if (words[i].startsWith(tok)) {
                return prefixScore;
            }
        }
        return 0;
    }

    private static boolean isGenericUiModifier(String tok) {
        switch (tok) {
            case "mod":
            case "mods":
            case "module":
            case "hud":
            case "display":
            case "counter":
            case "status":
            case "overlay":
            case "changer":
            case "pro":
            case "custom":
            case "indicator":
                return true;
            default:
                return false;
        }
    }

    private static boolean isWithinOneEdit(String a, String b) {
        int la = a.length();
        int lb = b.length();
        int diff = la - lb;
        if (diff > 1 || diff < -1) {
            return false;
        }
        if (la == lb) {
            int mismatches = 0;
            for (int i = 0; i < la; i++) {
                if (a.charAt(i) != b.charAt(i)) {
                    // Transposition adjacente (ex: "croshiar" / "perspetcive")
                    if (i + 1 < la && a.charAt(i) == b.charAt(i + 1) && a.charAt(i + 1) == b.charAt(i)) {
                        i++;
                        mismatches++;
                    } else {
                        mismatches++;
                    }
                    if (mismatches > 1) {
                        return false;
                    }
                }
            }
            return mismatches <= 1;
        }
        String shorter = la < lb ? a : b;
        String longer = la < lb ? b : a;
        int si = 0;
        int li = 0;
        boolean skipped = false;
        while (si < shorter.length() && li < longer.length()) {
            if (shorter.charAt(si) == longer.charAt(li)) {
                si++;
                li++;
            } else {
                if (skipped) {
                    return false;
                }
                skipped = true;
                li++;
            }
        }
        return true;
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
            } catch (Exception | LinkageError ignored) {
            }
        }
        JsonElement fav = obj.get("favorite");
        if (fav != null && fav.isJsonPrimitive()) {
            try {
                this.favorite = fav.getAsBoolean();
            } catch (Exception | LinkageError ignored) {
            }
        }
        if (hudWidget) {
            JsonElement xEl = obj.get("x");
            JsonElement yEl = obj.get("y");
            if (xEl != null && xEl.isJsonPrimitive()) {
                try {
                    this.hudX = Math.max(0.0F, Math.min(3840.0F, xEl.getAsFloat()));
                } catch (Exception | LinkageError ignored) {
                }
            }
            if (yEl != null && yEl.isJsonPrimitive()) {
                try {
                    this.hudY = Math.max(0.0F, Math.min(2160.0F, yEl.getAsFloat()));
                } catch (Exception | LinkageError ignored) {
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

    private static String stripCategoryPrefix(String id) {
        if (id == null) {
            return "";
        }
        int idx = id.indexOf('_');
        return (idx >= 0 && idx + 1 < id.length()) ? id.substring(idx + 1) : id;
    }

    private static String humanizeIdFallback(String id) {
        String base = stripCategoryPrefix(id).replace('_', ' ').trim();
        if (base.isEmpty()) {
            return "Module";
        }
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    private static String removeSpaces(String s) {
        if (s == null || s.isEmpty() || s.indexOf(' ') < 0) {
            return s != null ? s : "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String[] splitWords(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return new String[0];
        }
        return normalized.split(" ");
    }

    /**
     * Normalise une chaîne de recherche FR/EN :
     * - minuscules + retrait des accents français (é->e, œ->oe, ç->c...)
     * - harmonisation orthographique UK -> US (armour->armor, colour->color, centre->center...)
     * - ponctuation/séparateurs convertis en espaces uniques.
     */
    public static String normalizeSearch(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String folded = stripAccents(input);
        StringBuilder sb = new StringBuilder(folded.length());
        boolean prevSpace = true;
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                prevSpace = false;
            } else if (!prevSpace) {
                sb.append(' ');
                prevSpace = true;
            }
        }
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == ' ') {
            sb.setLength(len - 1);
        }
        String clean = sb.toString();
        if (clean.isEmpty()) {
            return "";
        }
        clean = clean
                .replace("armour", "armor")
                .replace("colour", "color")
                .replace("centred", "center")
                .replace("centered", "center")
                .replace("centre", "center")
                .replace("minimise", "minimize")
                .replace("customise", "customize")
                .replace("optimise", "optimize")
                .replace("favourite", "favorite");
        return clean;
    }

    public static String stripAccents(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length() + 4);
        for (int i = 0; i < input.length(); i++) {
            char c = Character.toLowerCase(input.charAt(i));
            switch (c) {
                case 'à': case 'â': case 'ä': case 'á': sb.append('a'); break;
                case 'é': case 'è': case 'ê': case 'ë': sb.append('e'); break;
                case 'î': case 'ï': case 'í': sb.append('i'); break;
                case 'ô': case 'ö': case 'ó': sb.append('o'); break;
                case 'ù': case 'û': case 'ü': case 'ú': sb.append('u'); break;
                case 'ç': sb.append('c'); break;
                case 'œ': sb.append("oe"); break;
                case 'æ': sb.append("ae"); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }
}

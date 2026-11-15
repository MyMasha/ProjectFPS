package dev.purefps.core;

import java.util.Arrays;

/**
 * Optimisations critiques zéro-allocation pour le texte et les entités nommées :
 * 1. Table LUT O(1) remplaçant text.toLowerCase(Locale.ENGLISH).charAt(i+1) sur chaque code couleur '§'.
 * 2. Cache 2-way de 2048 entrées pour FontRenderer.getStringWidth(String).
 * 3. Détecteur O(1) zéro-Regex remplaçant EnumChatFormatting.getTextWithoutFormattingCodes dans RendererLivingEntity.rotateCorpse.
 */
public final class FastFontCaches {

    private static final byte[] FORMAT_LUT = new byte[128];
    private static final String DINNERBONE = "Dinnerbone";
    private static final String GRUMM = "Grumm";

    static {
        Arrays.fill(FORMAT_LUT, (byte) -1);
        String codes = "0123456789abcdefklmnor";
        for (int i = 0; i < codes.length(); i++) {
            char c = codes.charAt(i);
            FORMAT_LUT[c] = (byte) i;
            if (c >= 'a' && c <= 'z') {
                FORMAT_LUT[c - 32] = (byte) i;
            }
        }
    }

    private FastFontCaches() {
    }

    /**
     * Équivalent zéro-allocation de "0123456789abcdefklmnor".indexOf(Character.toLowerCase(ch)).
     */
    public static int lookupFormatIndex(int ch) {
        if ((ch & ~127) != 0) {
            return -1;
        }
        return FORMAT_LUT[ch];
    }

    /**
     * Remplace EnumChatFormatting.getTextWithoutFormattingCodes(entity.getName()) dans rotateCorpse :
     * Vanilla compile et exécute un Pattern/Matcher Regex sur CHAQUE entité à CHAQUE frame juste pour tester
     * si le nom vaut "Dinnerbone" ou "Grumm". Cette méthode le vérifie en quelques instructions CPU avec 0 allocation.
     */
    public static String resolveDinnerboneToken(String raw) {
        if (raw == null) {
            return null;
        }
        int len = raw.length();
        if (len < 5) {
            return "";
        }

        String candidate = null;
        int targetLen = 0;
        int matched = 0;

        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if (c == '\u00a7' && i + 1 < len) {
                char code = raw.charAt(i + 1);
                if (lookupFormatIndex(code) >= 0) {
                    i++;
                    continue;
                }
            }
            if (matched == 0) {
                if (c == 'D') {
                    candidate = DINNERBONE;
                    targetLen = 10;
                    matched = 1;
                } else if (c == 'G') {
                    candidate = GRUMM;
                    targetLen = 5;
                    matched = 1;
                } else {
                    return "";
                }
            } else {
                if (matched >= targetLen || candidate.charAt(matched) != c) {
                    return "";
                }
                matched++;
            }
        }

        return (candidate != null && matched == targetLen) ? candidate : "";
    }

    /**
     * Remplace le pseudo du joueur par l'alias configuré dans le module Nick Hider (hyp_nick_hider)
     * avec un chemin rapide sans allocation lorsque le pseudo n'est pas présent dans la chaîne.
     */
    public static String applyNickHider(String text) {
        if (text == null || !dev.purefps.config.PureFPSConfig.enabled || !dev.purefps.module.MashFlags.nickHiderEnabled) {
            return text;
        }
        int len = text.length();
        if (len < 2) {
            return text;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc == null || mc.getSession() == null) {
            return text;
        }
        String username = mc.getSession().getUsername();
        if (username == null) {
            return text;
        }
        int uLen = username.length();
        if (uLen < 2 || len < uLen) {
            return text;
        }
        int idx = indexOfIgnoreCase(text, username, 0);
        if (idx < 0) {
            return text;
        }
        String replacement = dev.purefps.module.MashFlags.nickReplacement;
        if (replacement == null || replacement.isEmpty()) {
            replacement = "MashPlayer";
        }
        StringBuilder sb = new StringBuilder(len + 16);
        int pos = 0;
        while (idx >= 0) {
            sb.append(text, pos, idx);
            sb.append(replacement);
            pos = idx + uLen;
            idx = indexOfIgnoreCase(text, username, pos);
        }
        sb.append(text, pos, len);
        return sb.toString();
    }

    private static int indexOfIgnoreCase(String str, String target, int fromIndex) {
        int strLen = str.length();
        int targetLen = target.length();
        int max = strLen - targetLen;
        if (fromIndex > max) {
            return -1;
        }
        char first = target.charAt(0);
        char firstUp = Character.toUpperCase(first);
        char firstLow = Character.toLowerCase(first);
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            char c = str.charAt(i);
            if (c != first && c != firstUp && c != firstLow) {
                continue;
            }
            if (str.regionMatches(true, i, target, 0, targetLen)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Cache 2-way de 2048 entrées pour FontRenderer.getStringWidth(String).
     */
    public static final class WidthCache {
        private static final int SIZE = 2048;
        private static final int MASK = SIZE - 1;

        private final String[] keys = new String[SIZE];
        private final int[] widths = new int[SIZE];

        public int get(String text) {
            int len = text.length();
            if (len > 128) {
                return -1;
            }
            int h = text.hashCode();
            int slot0 = (h ^ (h >>> 11)) & MASK;
            String k0 = keys[slot0];
            if (k0 != null && (k0 == text || (k0.length() == len && k0.equals(text)))) {
                return widths[slot0];
            }
            int slot1 = (slot0 + 1) & MASK;
            String k1 = keys[slot1];
            if (k1 != null && (k1 == text || (k1.length() == len && k1.equals(text)))) {
                return widths[slot1];
            }
            return -1;
        }

        public void put(String text, int width) {
            if (text.length() > 128 || width < 0) {
                return;
            }
            int h = text.hashCode();
            int slot0 = (h ^ (h >>> 11)) & MASK;
            if (keys[slot0] != null) {
                int slot1 = (slot0 + 1) & MASK;
                keys[slot1] = keys[slot0];
                widths[slot1] = widths[slot0];
            }
            keys[slot0] = text;
            widths[slot0] = width;
        }

        public void clear() {
            Arrays.fill(keys, null);
        }
    }
}

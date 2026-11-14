package dev.purefps.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.lwjgl.input.Keyboard;

import java.awt.Color;
import java.util.Locale;

/**
 * Sous-paramètres typés MashClient 4.0 (clic droit sur n'importe quel module dans le menu RSHIFT) :
 * - BooleanSetting (interrupteur ON/OFF)
 * - NumberSetting (curseur continu ou entier avec min, max, pas, unité)
 * - ModeSetting (sélecteur multi-options)
 * - ColorSetting (sélecteur HSB + Opacité + Mode Chroma arc-en-ciel)
 * - KeybindSetting (touche clavier ou bouton souris configurable)
 */
public abstract class MashSetting {

    protected final String id;
    protected final String name;
    protected final String description;

    protected MashSetting(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description != null ? description : "";
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

    public abstract void resetToDefault();

    public abstract void writeJson(JsonObject out);

    public abstract void readJson(JsonObject in);

    public static final class BooleanSetting extends MashSetting {
        private final boolean defaultValue;
        private boolean value;

        public BooleanSetting(String id, String name, String description, boolean defaultValue) {
            super(id, name, description);
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        public boolean get() {
            return value;
        }

        public void set(boolean v) {
            if (this.value != v) {
                this.value = v;
                MashModules.onSettingMutated();
            }
        }

        public void toggle() {
            set(!this.value);
        }

        public void setSilent(boolean v) {
            this.value = v;
        }

        @Override
        public void resetToDefault() {
            this.value = this.defaultValue;
        }

        @Override
        public void writeJson(JsonObject out) {
            out.addProperty(id, value);
        }

        @Override
        public void readJson(JsonObject in) {
            JsonElement el = in.get(id);
            if (el != null && el.isJsonPrimitive()) {
                try {
                    this.value = el.getAsBoolean();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static final class NumberSetting extends MashSetting {
        private final float min;
        private final float max;
        private final float step;
        private final float defaultValue;
        private final String suffix;
        private float value;

        public NumberSetting(String id, String name, String description, float defaultValue, float min, float max, float step, String suffix) {
            super(id, name, description);
            this.min = min;
            this.max = Math.max(min + 0.0001F, max);
            this.step = step <= 0.0F ? 0.01F : step;
            this.suffix = suffix != null ? suffix : "";
            this.defaultValue = snapAndClamp(defaultValue);
            this.value = this.defaultValue;
        }

        public float get() {
            return value;
        }

        public int getInt() {
            return Math.round(value);
        }

        public float getMin() {
            return min;
        }

        public float getMax() {
            return max;
        }

        public float getStep() {
            return step;
        }

        public float getFraction() {
            return (value - min) / (max - min);
        }

        public void setFraction(float frac) {
            float clamped = frac < 0.0F ? 0.0F : (frac > 1.0F ? 1.0F : frac);
            set(min + clamped * (max - min));
        }

        public void set(float raw) {
            float snapped = snapAndClamp(raw);
            if (Math.abs(this.value - snapped) > 0.0001F) {
                this.value = snapped;
                MashModules.onSettingMutated();
            }
        }

        public void setSilent(float raw) {
            this.value = snapAndClamp(raw);
        }

        private float snapAndClamp(float raw) {
            float c = raw < min ? min : (raw > max ? max : raw);
            float steps = Math.round((c - min) / step);
            float s = min + steps * step;
            return s < min ? min : (s > max ? max : s);
        }

        public String formatValue() {
            if (step >= 0.99F) {
                return Math.round(value) + suffix;
            }
            if (step >= 0.09F) {
                return String.format(Locale.US, "%.1f", value) + suffix;
            }
            return String.format(Locale.US, "%.2f", value) + suffix;
        }

        @Override
        public void resetToDefault() {
            this.value = this.defaultValue;
        }

        @Override
        public void writeJson(JsonObject out) {
            out.addProperty(id, value);
        }

        @Override
        public void readJson(JsonObject in) {
            JsonElement el = in.get(id);
            if (el != null && el.isJsonPrimitive()) {
                try {
                    this.value = snapAndClamp(el.getAsFloat());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static final class ModeSetting extends MashSetting {
        private final String[] options;
        private final int defaultIndex;
        private int index;

        public ModeSetting(String id, String name, String description, int defaultIndex, String... options) {
            super(id, name, description);
            this.options = (options != null && options.length > 0) ? options : new String[]{"Standard"};
            this.defaultIndex = clampIdx(defaultIndex);
            this.index = this.defaultIndex;
        }

        public int getIndex() {
            return index;
        }

        public String getMode() {
            return options[index];
        }

        public String[] getOptions() {
            return options;
        }

        public void setIndex(int idx) {
            int c = clampIdx(idx);
            if (this.index != c) {
                this.index = c;
                MashModules.onSettingMutated();
            }
        }

        public void cycleNext() {
            setIndex((index + 1) % options.length);
        }

        public void cyclePrev() {
            setIndex((index - 1 + options.length) % options.length);
        }

        private int clampIdx(int i) {
            return i < 0 ? 0 : (i >= options.length ? options.length - 1 : i);
        }

        @Override
        public void resetToDefault() {
            this.index = this.defaultIndex;
        }

        @Override
        public void writeJson(JsonObject out) {
            out.addProperty(id, index);
        }

        @Override
        public void readJson(JsonObject in) {
            JsonElement el = in.get(id);
            if (el != null && el.isJsonPrimitive()) {
                try {
                    this.index = clampIdx(el.getAsInt());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static final class ColorSetting extends MashSetting {
        private final int defaultArgb;
        private final boolean defaultChroma;
        private int argb;
        private boolean chroma;
        private float hue;
        private float saturation;
        private float brightness;

        public ColorSetting(String id, String name, String description, int defaultArgb, boolean defaultChroma) {
            super(id, name, description);
            this.defaultArgb = defaultArgb;
            this.defaultChroma = defaultChroma;
            setSilent(defaultArgb, defaultChroma);
        }

        public int getRawArgb() {
            return argb;
        }

        public boolean isChroma() {
            return chroma;
        }

        public float getHue() {
            return hue;
        }

        public float getSaturation() {
            return saturation;
        }

        public float getBrightness() {
            return brightness;
        }

        public int getAlpha() {
            return (argb >>> 24) & 0xFF;
        }

        public int resolveArgb(long nowMs, int phaseOffsetMs) {
            if (!chroma) {
                return argb;
            }
            float h = ((nowMs + phaseOffsetMs) % 3600L) / 3600.0F;
            int rgb = Color.HSBtoRGB(h, 0.78F, 0.98F) & 0x00FFFFFF;
            return (argb & 0xFF000000) | rgb;
        }

        public void setChroma(boolean c) {
            if (this.chroma != c) {
                this.chroma = c;
                MashModules.onSettingMutated();
            }
        }

        public void setHsbAndAlpha(float h, float s, float b, int alpha) {
            this.hue = clamp01(h);
            this.saturation = clamp01(s);
            this.brightness = clamp01(b);
            int a = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
            int rgb = Color.HSBtoRGB(this.hue, this.saturation, this.brightness) & 0x00FFFFFF;
            int next = (a << 24) | rgb;
            if (this.argb != next) {
                this.argb = next;
                MashModules.onSettingMutated();
            }
        }

        public void setArgb(int nextArgb) {
            if (this.argb != nextArgb) {
                syncHsbFromArgb(nextArgb);
                MashModules.onSettingMutated();
            }
        }

        private void setSilent(int c, boolean ch) {
            this.chroma = ch;
            syncHsbFromArgb(c);
        }

        private void syncHsbFromArgb(int c) {
            this.argb = c;
            int r = (c >>> 16) & 0xFF;
            int g = (c >>> 8) & 0xFF;
            int b = c & 0xFF;
            float[] hsb = Color.RGBtoHSB(r, g, b, null);
            this.hue = hsb[0];
            this.saturation = hsb[1];
            this.brightness = hsb[2];
        }

        private static float clamp01(float v) {
            return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
        }

        @Override
        public void resetToDefault() {
            setSilent(defaultArgb, defaultChroma);
        }

        @Override
        public void writeJson(JsonObject out) {
            out.addProperty(id, argb);
            out.addProperty(id + "_chroma", chroma);
        }

        @Override
        public void readJson(JsonObject in) {
            JsonElement el = in.get(id);
            if (el != null && el.isJsonPrimitive()) {
                try {
                    syncHsbFromArgb(el.getAsInt());
                } catch (Throwable ignored) {
                }
            }
            JsonElement ch = in.get(id + "_chroma");
            if (ch != null && ch.isJsonPrimitive()) {
                try {
                    this.chroma = ch.getAsBoolean();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static final class KeybindSetting extends MashSetting {
        private final int defaultKeyCode;
        private int keyCode; // 0 = Aucun, >0 = LWJGL Keyboard, <0 = Bouton souris (-100 + btn)

        public KeybindSetting(String id, String name, String description, int defaultKeyCode) {
            super(id, name, description);
            this.defaultKeyCode = defaultKeyCode;
            this.keyCode = defaultKeyCode;
        }

        public int getKeyCode() {
            return keyCode;
        }

        public void setKeyCode(int code) {
            if (this.keyCode != code) {
                this.keyCode = code;
                MashModules.onSettingMutated();
            }
        }

        public String getDisplayString() {
            if (keyCode == 0) {
                return "AUCUNE";
            }
            if (keyCode <= -85) {
                int mouseBtn = keyCode + 100;
                return "SOURIS " + (mouseBtn + 1);
            }
            try {
                String name = Keyboard.getKeyName(keyCode);
                return (name != null && !name.isEmpty()) ? name.toUpperCase(Locale.ROOT) : ("KEY " + keyCode);
            } catch (Throwable ignored) {
                return "KEY " + keyCode;
            }
        }

        @Override
        public void resetToDefault() {
            this.keyCode = this.defaultKeyCode;
        }

        @Override
        public void writeJson(JsonObject out) {
            out.addProperty(id, keyCode);
        }

        @Override
        public void readJson(JsonObject in) {
            JsonElement el = in.get(id);
            if (el != null && el.isJsonPrimitive()) {
                try {
                    this.keyCode = el.getAsInt();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}

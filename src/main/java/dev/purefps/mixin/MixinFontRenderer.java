package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastFontCaches;
import dev.purefps.core.FastSkullCache;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

/**
 * Optimise massivement FontRenderer sans AUCUNE allocation CallbackInfo par chaîne/caractère :
 * 1. @Overwrite zéro-allocation sur getStringWidth(String) (cache O(1) ~2 ns sur Scoreboard, Tab, Chat, Nametags).
 * 2. @Redirect sur renderDefaultChar liant directement l'ID OpenGL de la police ASCII via GlStateManager.bindTexture(int)
 *    (élimine 1000+ HashMap.get(locationFontTexture) par frame sur Hypixel).
 * 3. Supprime l'allocation String.toLowerCase(Locale.ENGLISH) exécutée à chaque code couleur '§'.
 * 4. Applique le module Nick Hider (hyp_nick_hider) globalement sur tous les textes rendus.
 */
@Mixin(FontRenderer.class)
public abstract class MixinFontRenderer {

    @Shadow
    @Final
    protected ResourceLocation locationFontTexture;

    @Shadow
    @Final
    private TextureManager renderEngine;

    @Shadow(remap = false)
    protected abstract void bindTexture(ResourceLocation location);

    @Shadow
    public abstract int getCharWidth(char character);

    @Unique
    private final FastFontCaches.WidthCache purefps$widthCache = new FastFontCaches.WidthCache();

    @Unique
    private int purefps$asciiGlTextureId = -1;

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void purefps$clearCacheOnReload(IResourceManager resourceManager, CallbackInfo ci) {
        this.purefps$widthCache.clear();
        this.purefps$asciiGlTextureId = -1;
        FastSkullCache.clear();
    }

    @Inject(method = "setUnicodeFlag", at = @At("HEAD"))
    private void purefps$clearCacheOnUnicodeChange(boolean flag, CallbackInfo ci) {
        this.purefps$widthCache.clear();
    }

    @ModifyVariable(method = "renderStringAtPos", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String purefps$applyNickHiderInRender(String text) {
        return FastFontCaches.applyNickHider(text);
    }

    /**
     * @author PureFPS
     * @reason Cache O(1) zéro-allocation (supprime les 2 CallbackInfoReturnable + Integer.valueOf par appel)
     */
    @Overwrite
    public int getStringWidth(String text) {
        if (text == null) {
            return 0;
        }
        text = FastFontCaches.applyNickHider(text);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        final boolean useCache = PureFPSConfig.enabled
                && PureFPSConfig.fastFontRenderer
                && mc != null
                && mc.isCallingFromMinecraftThread();
        if (useCache) {
            int cached = this.purefps$widthCache.get(text);
            if (cached >= 0) {
                return cached;
            }
        }

        int width = 0;
        boolean bold = false;
        int len = text.length();

        for (int i = 0; i < len; ++i) {
            char ch = text.charAt(i);
            int charWidth = this.getCharWidth(ch);

            if (charWidth < 0 && i < len - 1) {
                ++i;
                ch = text.charAt(i);
                if (ch != 'l' && ch != 'L') {
                    if (ch == 'r' || ch == 'R') {
                        bold = false;
                    }
                } else {
                    bold = true;
                }
                charWidth = 0;
            }

            width += charWidth;
            if (bold && charWidth > 0) {
                ++width;
            }
        }

        if (useCache) {
            this.purefps$widthCache.put(text, width);
        }
        return width;
    }

    @Redirect(
            method = "renderDefaultChar",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/FontRenderer;bindTexture(Lnet/minecraft/util/ResourceLocation;)V",
                    remap = false
            )
    )
    private void purefps$fastAsciiFontBind(FontRenderer self, ResourceLocation location) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastFontRenderer && location == this.locationFontTexture) {
            int texId = this.purefps$asciiGlTextureId;
            if (texId >= 0) {
                GlStateManager.bindTexture(texId);
                return;
            }
            if (this.renderEngine != null) {
                ITextureObject tex = this.renderEngine.getTexture(location);
                if (tex == null) {
                    this.bindTexture(location);
                    tex = this.renderEngine.getTexture(location);
                }
                if (tex != null) {
                    texId = tex.getGlTextureId();
                    this.purefps$asciiGlTextureId = texId;
                    GlStateManager.bindTexture(texId);
                    return;
                }
            }
        }
        this.bindTexture(location);
    }

    @Redirect(
            method = "renderStringAtPos",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/String;toLowerCase(Ljava/util/Locale;)Ljava/lang/String;",
                    remap = false
            )
    )
    private String purefps$avoidToLowerCaseAllocation(String text, Locale locale) {
        return (PureFPSConfig.enabled && PureFPSConfig.fastFontRenderer) ? text : text.toLowerCase(locale);
    }

    @Redirect(
            method = "renderStringAtPos",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/String;indexOf(I)I",
                    ordinal = 0,
                    remap = false
            )
    )
    private int purefps$fastFormatCodeIndex(String formatAlphabet, int ch) {
        return (PureFPSConfig.enabled && PureFPSConfig.fastFontRenderer)
                ? FastFontCaches.lookupFormatIndex(ch)
                : formatAlphabet.indexOf(ch);
    }
}

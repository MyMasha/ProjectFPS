package dev.purefps.mixin;

import dev.purefps.core.SignTextCacheAccess;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiUtilRenderComponents;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Mémorise les lignes formatées et leurs demi-largeurs directement sur chaque instance TileEntitySign,
 * avec invalidation automatique dès que la référence IChatComponent change ou que readFromNBT est appelé.
 */
@Mixin(TileEntitySign.class)
public class MixinTileEntitySign implements SignTextCacheAccess {

    @Unique
    private final IChatComponent[] purefps$lastRaw = new IChatComponent[4];

    @Unique
    private final String[] purefps$cachedFormatted = new String[4];

    @Unique
    private final int[] purefps$cachedHalfWidths = new int[4];

    @Unique
    private FontRenderer purefps$lastFont;

    @Inject(method = "readFromNBT", at = @At("RETURN"))
    private void purefps$onReadFromNbt(NBTTagCompound compound, CallbackInfo ci) {
        this.purefps$invalidateSignCache();
    }

    @Override
    public String purefps$getCachedLine(int index, IChatComponent rawComponent, FontRenderer fontRenderer) {
        if (rawComponent == null) {
            this.purefps$lastRaw[index] = null;
            this.purefps$cachedFormatted[index] = "";
            this.purefps$cachedHalfWidths[index] = 0;
            return "";
        }
        if (this.purefps$lastFont != fontRenderer) {
            this.purefps$invalidateSignCache();
            this.purefps$lastFont = fontRenderer;
        }
        if (this.purefps$lastRaw[index] == rawComponent && this.purefps$cachedFormatted[index] != null) {
            return this.purefps$cachedFormatted[index];
        }
        List<IChatComponent> list = GuiUtilRenderComponents.splitText(rawComponent, 90, fontRenderer, false, true);
        String formatted = (list != null && !list.isEmpty()) ? list.get(0).getFormattedText() : "";
        this.purefps$lastRaw[index] = rawComponent;
        this.purefps$cachedFormatted[index] = formatted;
        this.purefps$cachedHalfWidths[index] = formatted.isEmpty() ? 0 : (fontRenderer.getStringWidth(formatted) / 2);
        return formatted;
    }

    @Override
    public int purefps$getCachedHalfWidth(int index) {
        return this.purefps$cachedHalfWidths[index];
    }

    @Override
    public void purefps$invalidateSignCache() {
        for (int i = 0; i < 4; i++) {
            this.purefps$lastRaw[i] = null;
            this.purefps$cachedFormatted[i] = null;
            this.purefps$cachedHalfWidths[i] = 0;
        }
    }
}

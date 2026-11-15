package dev.purefps.mixin;

import dev.purefps.client.audio.LowLatencyAudioConfigurator;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenOptionsSounds;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ajoute un sélecteur de périphérique de sortie audio (Sortie Audio / Audio Output)
 * directement dans le menu Options -> Musique & Sons de Minecraft 1.8.9.
 */
@Mixin(GuiScreenOptionsSounds.class)
public abstract class MixinGuiScreenOptionsSounds extends GuiScreen {

    @Unique
    private static final int PUREFPS_AUDIO_DEVICE_BTN_ID = 28190;

    @Inject(method = "initGui", at = @At("RETURN"))
    private void purefps$addAudioOutputDeviceButton(CallbackInfo ci) {
        int btnX = this.width / 2 - 155;
        int btnY = this.height / 6 + 138;
        // Décaler légèrement le bouton "Done" (id = 200) s'il chevauche la ligne 138
        for (GuiButton btn : this.buttonList) {
            if (btn != null && btn.id == 200 && Math.abs(btn.yPosition - btnY) < 22) {
                btn.yPosition = this.height / 6 + 168;
            }
        }
        this.buttonList.add(new GuiButton(
                PUREFPS_AUDIO_DEVICE_BTN_ID,
                btnX,
                btnY,
                310,
                20,
                LowLatencyAudioConfigurator.getCurrentAudioOutputDisplayLabel()
        ));
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void purefps$onAudioOutputDeviceClicked(GuiButton button, CallbackInfo ci) {
        if (button != null && button.enabled && button.id == PUREFPS_AUDIO_DEVICE_BTN_ID) {
            LowLatencyAudioConfigurator.cycleAudioOutputDevice(this.mc);
            button.displayString = LowLatencyAudioConfigurator.getCurrentAudioOutputDisplayLabel();
            if (this.mc != null && this.mc.getSoundHandler() != null) {
                this.mc.getSoundHandler().playSound(
                        PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F)
                );
            }
            ci.cancel();
        }
    }
}

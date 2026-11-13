package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.gui.GuiPureFPSSettings;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiVideoSettings.class)
public abstract class MixinGuiVideoSettings extends GuiScreen {

    private static final int PUREFPS_BUTTON_ID = 28189;

    @Inject(method = "initGui", at = @At("RETURN"))
    private void purefps$addVideoSettingsButton(CallbackInfo ci) {
        for (GuiButton btn : this.buttonList) {
            if (btn.id == 200) {
                btn.xPosition = this.width / 2 - 155;
                btn.width = 150;
                break;
            }
        }
        this.buttonList.add(new GuiButton(
                PUREFPS_BUTTON_ID,
                this.width / 2 + 5,
                this.height - 27,
                150,
                20,
                "\u00a7bPureClient: " + PureFPSConfig.activeProfile.getColoredName() + "\u00a77..."
        ));
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void purefps$onPureFPSButtonClicked(GuiButton button, CallbackInfo ci) {
        if (button.enabled && button.id == PUREFPS_BUTTON_ID) {
            this.mc.gameSettings.saveOptions();
            this.mc.displayGuiScreen(new GuiPureFPSSettings(this));
            ci.cancel();
        }
    }
}

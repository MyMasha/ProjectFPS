package dev.purefps.client;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.SeamlessLaunchTransition;
import dev.purefps.gui.GuiPureFPSSettings;
import dev.purefps.gui.GuiPureMainMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Pont d'événements client PureClient 3.5 (100 % conforme Hypixel Watchdog) :
 * - Right Shift : ouvre le tableau de bord PureClient 3.5
 * - ToggleSprint : pilote uniquement l'état de la KeyBinding Sprint Vanilla sans écriture disque synchrone
 * - CPS & Molette Zoom : enregistrement sans allocation
 * - Menu Principal Custom PureClient 3.5 + émergence sans couture depuis le Trou Noir du Launcher
 */
public final class ClientEventBridge {

    private boolean wasSprintHeldByToggle = false;

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (event.gui != null) {
            PureFPSConfig.saveIfDirty();
        }
        if (event.gui != null && event.gui.getClass() == GuiMainMenu.class) {
            if (PureFPSConfig.enabled && PureFPSConfig.customMainMenu) {
                if (GuiPureMainMenu.consumeBypassFlag()) {
                    return;
                }
                event.gui = new GuiPureMainMenu();
            }
        }
    }

    @SubscribeEvent
    public void onGuiDrawPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        SeamlessLaunchTransition.renderEmergenceCurtainIfNeeded(event.gui);
    }

    @SubscribeEvent
    public void onRenderOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        PureClientHud.render(mc, event.resolution, false);
    }

    @SubscribeEvent
    public void onMouseInput(MouseEvent event) {
        if (event.buttonstate) {
            long now = System.currentTimeMillis();
            if (event.button == 0) {
                CpsTracker.recordLeftClick(now);
            } else if (event.button == 1) {
                CpsTracker.recordRightClick(now);
            }
        }
        if (event.dwheel != 0 && ZoomController.isZooming()) {
            ZoomController.onMouseScroll(event.dwheel);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (!Keyboard.getEventKeyState()) {
            return;
        }
        int key = Keyboard.getEventKey();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        if (key == Keyboard.KEY_RSHIFT && mc.currentScreen == null) {
            mc.displayGuiScreen(new GuiPureFPSSettings(null));
            return;
        }
        if (mc.currentScreen == null && PureFPSConfig.enabled && PureFPSConfig.toggleSprint && mc.gameSettings != null) {
            if (key == mc.gameSettings.keyBindSprint.getKeyCode()) {
                PureFPSConfig.sprintToggledActive = !PureFPSConfig.sprintToggledActive;
                PureFPSConfig.markDirty();
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }

        int sprintKey = mc.gameSettings.keyBindSprint.getKeyCode();
        boolean shouldHoldSprint = PureFPSConfig.enabled
                && PureFPSConfig.toggleSprint
                && PureFPSConfig.sprintToggledActive
                && mc.thePlayer != null
                && mc.currentScreen == null;

        if (shouldHoldSprint) {
            KeyBinding.setKeyBindState(sprintKey, true);
            wasSprintHeldByToggle = true;
        } else if (wasSprintHeldByToggle) {
            boolean physicallyDown = sprintKey > 0 && sprintKey < 256 && Keyboard.isCreated() && Keyboard.isKeyDown(sprintKey);
            KeyBinding.setKeyBindState(sprintKey, physicallyDown);
            wasSprintHeldByToggle = false;
        }
    }
}

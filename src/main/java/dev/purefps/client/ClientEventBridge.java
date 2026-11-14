package dev.purefps.client;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.SeamlessLaunchTransition;
import dev.purefps.gui.GuiMashClientMenu;
import dev.purefps.gui.GuiPureMainMenu;
import dev.purefps.module.MashFlags;
import dev.purefps.module.MashHudRenderer;
import dev.purefps.module.MashModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Pont d'événements client MashClient 4.0 (100 % conforme Hypixel Watchdog) :
 * - Right Shift : ouvre le Studio MashClient 4.0 (200 modules + HUD translucide magnétique intégré)
 * - Molette Zoom : capture uniquement la molette si l'option "Zoomer avec la molette" est activée ;
 *   sinon laisse la molette changer normalement de slot dans la hotbar !
 * - ToggleSprint & ToggleSneak sécurisés (désactive le sneak dès qu'un inventaire s'ouvre)
 * - Freelook 360° purement local (caméra uniquement, 0 paquet serveur altéré)
 * - Télémétrie PvP (Reach, Combo, Coups, Chronomètre) sans allocation.
 */
public final class ClientEventBridge {

    private boolean wasSprintHeldByToggle = false;
    private boolean wasSneakHeldByToggle = false;
    private int savedThirdPersonBeforeFreelook = 0;
    private int saveDebounceTicks = 0;

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (event.gui != null) {
            MashFlags.sneakerActive = false;
            MashModules.saveAsyncIfDirty();
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
    public void onAttackEntity(AttackEntityEvent event) {
        if (event != null && event.entityPlayer != null && event.target != null) {
            MashHudRenderer.onAttackEntity(event.entityPlayer, event.target);
        }
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
        if (event.dwheel != 0 && ZoomController.shouldCaptureMouseWheel()) {
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
            dev.purefps.client.audio.MashAudioEngine.playMenuOpen();
            mc.displayGuiScreen(new GuiMashClientMenu(null));
            return;
        }
        if (mc.currentScreen == null && PureFPSConfig.enabled && mc.gameSettings != null) {
            if (MashFlags.toggleSprintEnabled && key == mc.gameSettings.keyBindSprint.getKeyCode()) {
                PureFPSConfig.sprintToggledActive = !PureFPSConfig.sprintToggledActive;
                PureFPSConfig.markDirty();
            }
            if (MashFlags.toggleSneakEnabled && key == mc.gameSettings.keyBindSneak.getKeyCode()) {
                MashFlags.sneakerActive = !MashFlags.sneakerActive;
            }
            if (MashModules.isOn("hud_stopwatch")) {
                int startKey = MashModules.key("hud_stopwatch", "toggle_key", Keyboard.KEY_P);
                int resetKey = MashModules.key("hud_stopwatch", "reset_key", Keyboard.KEY_O);
                if (startKey != 0 && key == startKey) {
                    MashHudRenderer.toggleStopwatch();
                } else if (resetKey != 0 && key == resetKey) {
                    MashHudRenderer.resetStopwatch();
                }
            }
        }
    }

    private static boolean isKeybindPhysicallyDown(int code) {
        if (code == 0) return false;
        if (code <= -85) {
            int btn = code + 100;
            return btn >= 0 && btn < 16 && Mouse.isCreated() && Mouse.isButtonDown(btn);
        }
        return code > 0 && code < 256 && Keyboard.isCreated() && Keyboard.isKeyDown(code);
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

        MashHudRenderer.onClientTick(mc);

        if (++saveDebounceTicks >= 100) {
            saveDebounceTicks = 0;
            MashModules.saveAsyncIfDirty();
        }

        // 1. Freelook 360° (caméra locale uniquement)
        boolean wantFreelook = PureFPSConfig.enabled
                && MashFlags.freelookEnabled
                && mc.thePlayer != null
                && mc.currentScreen == null
                && isKeybindPhysicallyDown(MashFlags.freelookKeyCode);

        if (wantFreelook && !MashFlags.freelookActive) {
            savedThirdPersonBeforeFreelook = mc.gameSettings.thirdPersonView;
            mc.gameSettings.thirdPersonView = 1;
            MashFlags.freelookYaw = mc.thePlayer.rotationYaw;
            MashFlags.freelookPitch = mc.thePlayer.rotationPitch;
            MashFlags.freelookActive = true;
        } else if (!wantFreelook && MashFlags.freelookActive) {
            mc.gameSettings.thirdPersonView = savedThirdPersonBeforeFreelook;
            MashFlags.freelookActive = false;
        }

        // 2. Toggle Sprint
        int sprintKey = mc.gameSettings.keyBindSprint.getKeyCode();
        boolean shouldHoldSprint = PureFPSConfig.enabled
                && MashFlags.toggleSprintEnabled
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

        // 3. Toggle Sneak (100% safe : se coupe dès qu'un écran s'ouvre)
        int sneakKey = mc.gameSettings.keyBindSneak.getKeyCode();
        boolean shouldHoldSneak = PureFPSConfig.enabled
                && MashFlags.toggleSneakEnabled
                && MashFlags.sneakerActive
                && mc.thePlayer != null
                && mc.currentScreen == null
                && !mc.thePlayer.capabilities.isFlying;

        if (shouldHoldSneak) {
            KeyBinding.setKeyBindState(sneakKey, true);
            wasSneakHeldByToggle = true;
        } else if (wasSneakHeldByToggle) {
            boolean physicallyDown = sneakKey > 0 && sneakKey < 256 && Keyboard.isCreated() && Keyboard.isKeyDown(sneakKey);
            KeyBinding.setKeyBindState(sneakKey, physicallyDown);
            wasSneakHeldByToggle = false;
        }
    }
}

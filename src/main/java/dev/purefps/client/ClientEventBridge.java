package dev.purefps.client;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.SeamlessLaunchTransition;
import dev.purefps.gui.GuiMashClientMenu;
import dev.purefps.gui.GuiPureMainMenu;
import dev.purefps.module.MashFlags;
import dev.purefps.module.MashHudRenderer;
import dev.purefps.module.MashModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.settings.GameSettings;
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
import org.lwjgl.opengl.Display;

/**
 * Pont d'événements client MashClient 4.5.1 (100 % conforme Hypixel Watchdog) :
 * - Right Shift : ouvre le Studio MashClient (modules + HUD translucide magnétique intégré)
 * - Zéro délai clavier : filtre les répétitions parasites, resynchronise ZQSD/Saut/Sprint dès la sortie de GUI
 *   et interroge Freelook / Snaplook à chaque frame de rendu (240+ Hz)
 * - Molette Zoom : capture uniquement la molette si l'option "Zoomer avec la molette" est activée
 * - ToggleSprint & ToggleSneak sécurisés (désactive le sneak dès qu'un inventaire s'ouvre)
 * - Freelook 360° & Snaplook purement locaux (caméra uniquement, 0 paquet serveur altéré)
 * - Reconnexion rapide en 1 clic sur l'écran GuiDisconnected + mémorisation du dernier serveur
 */
public final class ClientEventBridge {

    private static final int RECONNECT_BTN_ID = 9450;

    private boolean wasSprintHeldByToggle = false;
    private boolean wasSneakHeldByToggle = false;
    private int savedThirdPersonBeforeFreelook = 0;
    private int savedThirdPersonBeforeSnaplook = 0;
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
    public void onGuiInitPost(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!PureFPSConfig.enabled || !(event.gui instanceof GuiDisconnected) || !MashModules.isOn("wld_server_reconnect")) {
            return;
        }
        if (PureFPSConfig.lastServerIp == null || PureFPSConfig.lastServerIp.trim().isEmpty()) {
            return;
        }
        int baseY = event.gui.height / 2 + 24;
        if (event.buttonList != null) {
            for (Object obj : event.buttonList) {
                if (obj instanceof GuiButton) {
                    GuiButton btn = (GuiButton) obj;
                    if (btn.id == 0) {
                        baseY = Math.min(event.gui.height - 26, btn.yPosition + 24);
                        break;
                    }
                }
            }
            String labelName = (PureFPSConfig.lastServerName != null && !PureFPSConfig.lastServerName.isEmpty())
                    ? PureFPSConfig.lastServerName
                    : PureFPSConfig.lastServerIp;
            if (labelName.length() > 24) {
                labelName = labelName.substring(0, 22) + "..";
            }
            //noinspection unchecked
            event.buttonList.add(new GuiButton(
                    RECONNECT_BTN_ID,
                    event.gui.width / 2 - 100,
                    baseY,
                    200,
                    20,
                    "\u00a7eReconnecter \u00a77(" + labelName + ")"
            ));
        }
    }

    @SubscribeEvent
    public void onGuiActionPre(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (!PureFPSConfig.enabled || !(event.gui instanceof GuiDisconnected) || event.button == null) {
            return;
        }
        if (event.button.id == RECONNECT_BTN_ID && PureFPSConfig.lastServerIp != null && !PureFPSConfig.lastServerIp.isEmpty()) {
            event.setCanceled(true);
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                String name = (PureFPSConfig.lastServerName != null && !PureFPSConfig.lastServerName.isEmpty())
                        ? PureFPSConfig.lastServerName
                        : PureFPSConfig.lastServerIp;
                ServerData data = new ServerData(name, PureFPSConfig.lastServerIp, false);
                mc.displayGuiScreen(new GuiConnecting(new GuiMultiplayer(new GuiMainMenu()), mc, data));
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
        MashHudRenderer.renderOverlay(mc, event.resolution, false, null, false);
    }

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (event != null && event.entityPlayer != null && event.target != null) {
            MashHudRenderer.onAttackEntity(event.entityPlayer, event.target);
        }
    }

    @SubscribeEvent
    public void onMouseInput(MouseEvent event) {
        if (event.buttonstate && event.button >= 0) {
            long now = System.currentTimeMillis();
            if (event.button == 0) {
                CpsTracker.recordLeftClick(now);
            } else if (event.button == 1) {
                CpsTracker.recordRightClick(now);
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.currentScreen == null && PureFPSConfig.enabled && mc.gameSettings != null) {
                int mouseKeyCode = -100 + event.button;
                handleIngameBindPress(mc, mouseKeyCode);
            }
        }
        if (event.dwheel != 0 && ZoomController.shouldCaptureMouseWheel()) {
            ZoomController.onMouseScroll(event.dwheel);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (!Keyboard.getEventKeyState() || Keyboard.isRepeatEvent()) {
            return;
        }
        int key = Keyboard.getEventKey();
        if (key <= 0) {
            return;
        }
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
            handleIngameBindPress(mc, key);
        }
    }

    private static void handleIngameBindPress(Minecraft mc, int code) {
        if (MashFlags.toggleSprintEnabled && code == mc.gameSettings.keyBindSprint.getKeyCode()) {
            PureFPSConfig.sprintToggledActive = !PureFPSConfig.sprintToggledActive;
            PureFPSConfig.markDirty();
        }
        if (MashFlags.toggleSneakEnabled && code == mc.gameSettings.keyBindSneak.getKeyCode()) {
            MashFlags.sneakerActive = !MashFlags.sneakerActive;
        }
        if (MashModules.isOn("hud_stopwatch")) {
            int startKey = MashModules.key("hud_stopwatch", "toggle_key", Keyboard.KEY_P);
            int resetKey = MashModules.key("hud_stopwatch", "reset_key", Keyboard.KEY_O);
            if (startKey != 0 && code == startKey) {
                MashHudRenderer.toggleStopwatch();
            } else if (resetKey != 0 && code == resetKey) {
                MashHudRenderer.resetStopwatch();
            }
        }
    }

    public static boolean isKeybindPhysicallyDown(int code) {
        if (code == 0) return false;
        if (code <= -85) {
            int btn = code + 100;
            return btn >= 0 && btn < 16 && Mouse.isCreated() && Mouse.isButtonDown(btn);
        }
        return code > 0 && code < 256 && Keyboard.isCreated() && Keyboard.isKeyDown(code);
    }

    /**
     * Resynchronise immédiatement l'état réel des touches physiques (ZQSD, Saut, Sprint, Sneak)
     * après l'appel Vanilla {@code KeyBinding.unPressAllKeys()} lors de la fermeture d'un inventaire,
     * coffre, boutique BedWars, chat ou menu RSHIFT. Élimine 100 % du délai/blocage de touche en sortie de GUI.
     */
    public static void resyncMovementKeys(GameSettings gs) {
        if (gs == null || !Display.isCreated() || !Display.isActive()) {
            return;
        }
        syncSingleBinding(gs.keyBindForward);
        syncSingleBinding(gs.keyBindBack);
        syncSingleBinding(gs.keyBindLeft);
        syncSingleBinding(gs.keyBindRight);
        syncSingleBinding(gs.keyBindJump);

        int sprintCode = gs.keyBindSprint.getKeyCode();
        boolean holdSprint = (PureFPSConfig.enabled && MashFlags.toggleSprintEnabled && PureFPSConfig.sprintToggledActive)
                || isKeybindPhysicallyDown(sprintCode);
        KeyBinding.setKeyBindState(sprintCode, holdSprint);

        int sneakCode = gs.keyBindSneak.getKeyCode();
        boolean holdSneak = (PureFPSConfig.enabled && MashFlags.toggleSneakEnabled && MashFlags.sneakerActive)
                || isKeybindPhysicallyDown(sneakCode);
        KeyBinding.setKeyBindState(sneakCode, holdSneak);
    }

    private static void syncSingleBinding(KeyBinding kb) {
        if (kb == null) {
            return;
        }
        int code = kb.getKeyCode();
        if (code != 0) {
            KeyBinding.setKeyBindState(code, isKeybindPhysicallyDown(code));
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.gameSettings != null) {
            updateCameraPerspectiveKeys(mc);
        }
    }

    private void updateCameraPerspectiveKeys(Minecraft mc) {
        boolean inGame = PureFPSConfig.enabled
                && mc.thePlayer != null
                && mc.currentScreen == null
                && Display.isCreated()
                && Display.isActive();

        // 1. Freelook 360° (caméra locale uniquement, réactivité 0 ms sur la frame de rendu)
        boolean wantFreelook = inGame
                && MashFlags.freelookEnabled
                && isKeybindPhysicallyDown(MashFlags.freelookKeyCode);

        if (wantFreelook && !MashFlags.freelookActive) {
            if (MashFlags.snaplookActive) {
                mc.gameSettings.thirdPersonView = savedThirdPersonBeforeSnaplook;
                MashFlags.snaplookActive = false;
            }
            savedThirdPersonBeforeFreelook = mc.gameSettings.thirdPersonView;
            mc.gameSettings.thirdPersonView = 1;
            MashFlags.freelookYaw = mc.thePlayer.rotationYaw;
            MashFlags.freelookPitch = mc.thePlayer.rotationPitch;
            MashFlags.freelookActive = true;
        } else if (!wantFreelook && MashFlags.freelookActive) {
            mc.gameSettings.thirdPersonView = savedThirdPersonBeforeFreelook;
            MashFlags.freelookActive = false;
        }

        // 2. Snaplook / Vue arrière instantanée (thirdPersonView = 2 tant que la touche est maintenue)
        boolean wantSnaplook = inGame
                && !MashFlags.freelookActive
                && MashFlags.snaplookEnabled
                && isKeybindPhysicallyDown(MashFlags.snaplookKeyCode);

        if (wantSnaplook && !MashFlags.snaplookActive) {
            savedThirdPersonBeforeSnaplook = mc.gameSettings.thirdPersonView;
            mc.gameSettings.thirdPersonView = 2;
            MashFlags.snaplookActive = true;
        } else if (!wantSnaplook && MashFlags.snaplookActive) {
            mc.gameSettings.thirdPersonView = savedThirdPersonBeforeSnaplook;
            MashFlags.snaplookActive = false;
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

        MashHudRenderer.onClientTick(mc);

        if (mc.theWorld != null && mc.getCurrentServerData() != null) {
            ServerData sd = mc.getCurrentServerData();
            if (sd.serverIP != null && !sd.serverIP.isEmpty()) {
                PureFPSConfig.recordJoinedServer(sd.serverName, sd.serverIP);
            }
        }

        if (++saveDebounceTicks >= 100) {
            saveDebounceTicks = 0;
            MashModules.saveAsyncIfDirty();
        }

        // 1. Freelook & Snaplook
        updateCameraPerspectiveKeys(mc);

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
            KeyBinding.setKeyBindState(sprintKey, isKeybindPhysicallyDown(sprintKey));
            wasSprintHeldByToggle = false;
        }

        // 4. Toggle Sneak (100% safe : se coupe dès qu'un écran s'ouvre)
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
            KeyBinding.setKeyBindState(sneakKey, isKeybindPhysicallyDown(sneakKey));
            wasSneakHeldByToggle = false;
        }
    }
}

package dev.purefps.gui;

import dev.purefps.client.audio.MashAudioEngine;
import dev.purefps.client.ui.NanoUiRenderer;
import dev.purefps.client.ui.SmoothFontRenderer;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.BlackHoleEmergenceRenderer;
import dev.purefps.core.QuickServerLaunchBridge;
import dev.purefps.core.VulkanBackendHelper;
import dev.purefps.module.MashModules;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.client.GuiModList;
import org.lwjgl.opengl.Display;

import java.io.IOException;

/**
 * Menu principal MashClient 4.5 :
 * - Colonne compacte style Lunar Client avec icônes vectorielles SDF.
 * - Connexion automatique aux serveurs rapides du Launcher (.purefps_quick_server) et bouton Dernier serveur.
 * - Trou noir 3D dégagé et ambiance sonore gravitationnelle activable/désactivable.
 */
public class GuiPureMainMenu extends GuiScreen {

    private static boolean bypassOnce = false;

    private static final int ACT_MULTI = 1;
    private static final int ACT_SOLO = 2;
    private static final int ACT_MODULES = 3;
    private static final int ACT_HYPIXEL = 4;
    private static final int ACT_OPTIONS = 5;
    private static final int ACT_MODS = 6;
    private static final int ACT_SOUND = 7;
    private static final int ACT_QUIT = 8;
    private static final int ACT_BH_SOUND = 9;
    private static final int ACT_LAST_SERVER = 10;

    private static final float STACK_X = 50.0F;
    private static final float STACK_W = 188.0F;
    private static final float MULTI_H = 34.0F;
    private static final float SOLO_H = 30.0F;
    private static final float MOD_H = 30.0F;
    private static final float SUB_H = 26.0F;
    private static final float SUB_GAP = 6.0F;
    private static final float LAST_SRV_H = 25.0F;

    private static final float TOP_Y = 20.0F;
    private static final float TOP_BTN_SZ = 26.0F;

    private float lastUiAlpha = 1.0F;
    private int lastHoveredAction = -1;

    public static boolean consumeBypassFlag() {
        if (bypassOnce) {
            bypassOnce = false;
            return true;
        }
        return false;
    }

    private static boolean hasLastServer() {
        return PureFPSConfig.lastServerIp != null && !PureFPSConfig.lastServerIp.trim().isEmpty();
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.lastHoveredAction = -1;
        MashModules.ensureRegistered();
        MashAudioEngine.ensurePrewarmed();
        if (Display.isCreated()) {
            Display.setTitle(VulkanBackendHelper.getWindowTitle("4.5.7"));
        }
    }

    @Override
    public void onGuiClosed() {
        MashAudioEngine.updateMenuAmbientLoop(false);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (this.mc != null) {
            QuickServerLaunchBridge.TargetServer pending =
                    QuickServerLaunchBridge.consumePendingServer(this.mc.mcDataDir, System.currentTimeMillis());
            if (pending != null) {
                MashAudioEngine.updateMenuAmbientLoop(false);
                PureFPSConfig.recordJoinedServer(pending.name, pending.hostPort);
                ServerData data = new ServerData(pending.name, pending.hostPort, false);
                this.mc.displayGuiScreen(new GuiConnecting(this, this.mc, data));
                return;
            }
        }

        MashAudioEngine.updateMenuAmbientLoop(!Display.isCreated() || Display.isActive());

        float uiAlpha = BlackHoleEmergenceRenderer.renderMenuBackground(this.mc, this.width, this.height, mouseX, mouseY);
        this.lastUiAlpha = uiAlpha;
        if (uiAlpha <= 0.015F) {
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        float dw = NanoUiRenderer.desktopWidth(this.mc);
        float dh = NanoUiRenderer.desktopHeight(this.mc);
        float mx = NanoUiRenderer.desktopMouseX(this.mc);
        float my = NanoUiRenderer.desktopMouseY(this.mc);
        boolean interactive = uiAlpha > 0.45F;

        if (interactive) {
            int hov = hitTestDesktopAction(dw, dh, mx, my);
            if (hov != this.lastHoveredAction) {
                if (hov > 0) {
                    MashAudioEngine.playHover();
                }
                this.lastHoveredAction = hov;
            }
        }

        NanoUiRenderer.beginDesktopViewport(this.mc);
        try {
            renderCompactMenu(dw, dh, mx, my, interactive, uiAlpha);
        } finally {
            NanoUiRenderer.endDesktopViewport();
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private String getSessionName() {
        return (this.mc != null && this.mc.getSession() != null && this.mc.getSession().getUsername() != null)
                ? this.mc.getSession().getUsername()
                : "Player";
    }

    private static float computeStackTopY(float dh) {
        return Math.max(146.0F, dh * 0.50F - 46.0F);
    }

    private void renderCompactMenu(float dw, float dh, float mx, float my, boolean interactive, float uiAlpha) {
        SmoothFontRenderer heroF = SmoothFontRenderer.uiHero();
        SmoothFontRenderer bodyF = SmoothFontRenderer.uiBody();
        SmoothFontRenderer smallF = SmoothFontRenderer.uiSmall();
        SmoothFontRenderer boldSmallF = SmoothFontRenderer.uiBoldSmall();

        String sessionName = getSessionName();
        float sessW = Math.max(88.0F, smallF.getStringWidth(sessionName) + 32.0F);

        float quitX = dw - 48.0F;
        float soundX = quitX - 32.0F;
        float bhSoundX = soundX - 32.0F;
        float sessX = bhSoundX - sessW - 8.0F;

        float stackTopY = computeStackTopY(dh);
        float multiY = stackTopY;
        float soloY = multiY + MULTI_H + 7.0F;
        float modY = soloY + SOLO_H + 7.0F;
        float subY = modY + MOD_H + 8.0F;
        float subW = (STACK_W - SUB_GAP * 2.0F) / 3.0F;
        float lastSrvY = subY + SUB_H + 6.0F;
        boolean showLastSrv = hasLastServer();

        boolean multiHov = interactive && inside(mx, my, STACK_X, multiY, STACK_W, MULTI_H);
        boolean soloHov = interactive && inside(mx, my, STACK_X, soloY, STACK_W, SOLO_H);
        boolean modHov = interactive && inside(mx, my, STACK_X, modY, STACK_W, MOD_H);
        boolean hypHov = interactive && inside(mx, my, STACK_X, subY, subW, SUB_H);
        boolean optHov = interactive && inside(mx, my, STACK_X + subW + SUB_GAP, subY, subW, SUB_H);
        boolean fmlHov = interactive && inside(mx, my, STACK_X + (subW + SUB_GAP) * 2.0F, subY, subW, SUB_H);
        boolean lastSrvHov = showLastSrv && interactive && inside(mx, my, STACK_X, lastSrvY, STACK_W, LAST_SRV_H);
        boolean bhSoundHov = interactive && inside(mx, my, bhSoundX, TOP_Y, TOP_BTN_SZ, TOP_BTN_SZ);
        boolean soundHov = interactive && inside(mx, my, soundX, TOP_Y, TOP_BTN_SZ, TOP_BTN_SZ);
        boolean quitHov = interactive && inside(mx, my, quitX, TOP_Y, TOP_BTN_SZ, TOP_BTN_SZ);

        boolean vulkanMode = VulkanBackendHelper.isVulkanActive();

        NanoUiRenderer.beginBatch();

        // Halo sombre discret sous la colonne gauche
        NanoUiRenderer.drawRoundedGradient(
                STACK_X - 22.0F, stackTopY - 68.0F, STACK_W + 44.0F, showLastSrv ? 270.0F : 240.0F, 22.0F,
                mulAlpha(0x00020306, uiAlpha), mulAlpha(0x4C020306, uiAlpha), 0, 0.0F
        );

        // Logo haut-gauche
        NanoUiRenderer.drawBrandLogo(this.mc, 40.0F, 33.0F, 22.0F, uiAlpha);

        // Pilule pseudo haut-droite
        NanoUiRenderer.drawRoundedRect(
                sessX, TOP_Y, sessW, TOP_BTN_SZ, 13.0F,
                mulAlpha(0xB80A0D14, uiAlpha), mulAlpha(0x1FFFFFFF, uiAlpha), 1.0F
        );
        NanoUiRenderer.drawCircle(sessX + 12.0F, TOP_Y + 13.0F, 2.7F, mulAlpha(0xFF34D399, uiAlpha));

        // Bouton Son du Trou Noir ON/OFF
        boolean bhOn = PureFPSConfig.blackHoleMenuSound && PureFPSConfig.sfxEnabled;
        NanoUiRenderer.drawRoundedRect(
                bhSoundX, TOP_Y, TOP_BTN_SZ, TOP_BTN_SZ, 13.0F,
                mulAlpha(bhSoundHov ? 0xDC151A26 : 0xB80A0D14, uiAlpha),
                mulAlpha(PureFPSConfig.blackHoleMenuSound ? (bhSoundHov ? 0x72F5B85C : 0x34F5B85C) : 0x22FFFFFF, uiAlpha),
                1.0F
        );
        NanoUiRenderer.drawVortexIcon(
                bhSoundX + 13.0F, TOP_Y + 13.0F, 12.0F,
                PureFPSConfig.blackHoleMenuSound,
                mulAlpha(bhOn ? 0xFFF5C878 : 0xFF6E778C, uiAlpha)
        );

        // Bouton Sons UI ON/OFF
        NanoUiRenderer.drawRoundedRect(
                soundX, TOP_Y, TOP_BTN_SZ, TOP_BTN_SZ, 13.0F,
                mulAlpha(soundHov ? 0xDC151A26 : 0xB80A0D14, uiAlpha),
                mulAlpha(PureFPSConfig.sfxEnabled ? (soundHov ? 0x66F5B85C : 0x26FFFFFF) : 0x22FFFFFF, uiAlpha),
                1.0F
        );
        NanoUiRenderer.drawSoundIcon(
                soundX + 13.0F, TOP_Y + 13.0F, 11.5F,
                PureFPSConfig.sfxEnabled,
                mulAlpha(PureFPSConfig.sfxEnabled ? 0xFFECEFF6 : 0xFF6E778C, uiAlpha)
        );

        // Bouton Quitter
        NanoUiRenderer.drawRoundedRect(
                quitX, TOP_Y, TOP_BTN_SZ, TOP_BTN_SZ, 13.0F,
                mulAlpha(quitHov ? 0xDD3B1720 : 0xB80A0D14, uiAlpha),
                mulAlpha(quitHov ? 0x88F87171 : 0x24FFFFFF, uiAlpha),
                1.0F
        );
        NanoUiRenderer.drawCloseIcon(
                quitX + 13.0F, TOP_Y + 13.0F, 10.5F,
                mulAlpha(quitHov ? 0xFFFF9B9B : 0xFFD4DCEB, uiAlpha)
        );

        // 1. MULTIJOUEUR (compact 34px)
        NanoUiRenderer.drawShadow(STACK_X, multiY, STACK_W, MULTI_H, 8.5F, 12.0F, mulAlpha(multiHov ? 0x54F5B85C : 0x30F5B85C, uiAlpha));
        NanoUiRenderer.drawRoundedGradient(
                STACK_X, multiY, STACK_W, MULTI_H, 8.5F,
                mulAlpha(multiHov ? 0xFFFAD38A : 0xFFF2C26D, uiAlpha),
                mulAlpha(multiHov ? 0xFFE4A03D : 0xFFD58E2F, uiAlpha),
                mulAlpha(0xB8FFE9BE, uiAlpha),
                1.0F
        );
        NanoUiRenderer.drawPlayIcon(STACK_X + 18.0F, multiY + MULTI_H * 0.5F, 9.5F, mulAlpha(0xFF0A0B0E, uiAlpha));

        // 2. SOLO (compact 30px + icône joueur)
        drawGlassButton(STACK_X, soloY, STACK_W, SOLO_H, 8.0F, soloHov, false, uiAlpha);
        NanoUiRenderer.drawUserIcon(STACK_X + 18.0F, soloY + SOLO_H * 0.5F, 10.5F, mulAlpha(soloHov ? 0xFFFFFFFF : 0xFFB8C2D6, uiAlpha));

        // 3. MODULES (compact 30px + icône grille)
        drawGlassButton(STACK_X, modY, STACK_W, MOD_H, 8.0F, modHov, true, uiAlpha);
        NanoUiRenderer.drawGridIcon(STACK_X + 18.0F, modY + MOD_H * 0.5F, 10.0F, mulAlpha(modHov ? 0xFFFFDF9E : 0xFFF2C879, uiAlpha));

        // 4. Ligne secondaire compacte (26px) : Hypixel · Options · Mods
        drawGlassButton(STACK_X, subY, subW, SUB_H, 7.0F, hypHov, false, uiAlpha);
        drawGlassButton(STACK_X + subW + SUB_GAP, subY, subW, SUB_H, 7.0F, optHov, false, uiAlpha);
        drawGlassButton(STACK_X + (subW + SUB_GAP) * 2.0F, subY, subW, SUB_H, 7.0F, fmlHov, false, uiAlpha);

        // 5. Dernier serveur (compact 25px) lorsqu'un serveur récent est mémorisé
        if (showLastSrv) {
            drawGlassButton(STACK_X, lastSrvY, STACK_W, LAST_SRV_H, 7.0F, lastSrvHov, true, uiAlpha);
            NanoUiRenderer.drawBoltIcon(STACK_X + 14.0F, lastSrvY + LAST_SRV_H * 0.5F, 9.5F, mulAlpha(lastSrvHov ? 0xFFFFDF9E : 0xFFF5B85C, uiAlpha));
        }

        // Infobulle discrète au survol des contrôles audio haut-droite
        boolean en = PureFPSConfig.isEnglish();
        String topHint = null;
        float hintCenterX = 0.0F;
        if (bhSoundHov) {
            topHint = PureFPSConfig.blackHoleMenuSound
                    ? (en ? "Black Hole Audio: ON" : "Son trou noir : Activé")
                    : (en ? "Black Hole Audio: OFF" : "Son trou noir : Désactivé");
            hintCenterX = bhSoundX + TOP_BTN_SZ * 0.5F;
        } else if (soundHov) {
            topHint = PureFPSConfig.sfxEnabled
                    ? (en ? "UI Sounds: ON" : "Sons interface : Activés")
                    : (en ? "UI Sounds: Muted" : "Sons interface : Coupés");
            hintCenterX = soundX + TOP_BTN_SZ * 0.5F;
        }
        float hintW = 0.0F;
        float hintX = 0.0F;
        if (topHint != null) {
            hintW = smallF.getStringWidth(topHint) + 16.0F;
            hintX = Math.min(dw - hintW - 14.0F, Math.max(14.0F, hintCenterX - hintW * 0.5F));
            NanoUiRenderer.drawRoundedRect(
                    hintX, TOP_Y + TOP_BTN_SZ + 6.0F, hintW, 20.0F, 6.0F,
                    mulAlpha(0xEA090C14, uiAlpha), mulAlpha(0x30FFFFFF, uiAlpha), 1.0F
            );
        }

        NanoUiRenderer.endBatch();

        heroF.drawStringWithShadow("MASHCLIENT", STACK_X - 1.0F, stackTopY - 46.0F, mulAlpha(0xFFFFFFFF, uiAlpha));

        boldSmallF.beginBatch();
        boldSmallF.drawString("MASHCLIENT", 56.0F, 28.0F, mulAlpha(0xFFF5F3EE, uiAlpha));
        float topBrandW = boldSmallF.getStringWidth("MASHCLIENT");
        String verSubtitle = vulkanMode
                ? (en ? "4.5.7  \u00B7  1.8.9  \u00B7  VULKAN BETA" : "4.5.7  \u00B7  1.8.9  \u00B7  VULKAN BÊTA")
                : "4.5.7  \u00B7  1.8.9";
        boldSmallF.drawString(verSubtitle, 56.0F + topBrandW + 7.0F, 28.0F, mulAlpha(vulkanMode ? 0xFFE9C46A : 0xFF7D869C, uiAlpha));
        boldSmallF.drawCentered(en ? "MULTIPLAYER" : "MULTIJOUEUR", STACK_X + STACK_W * 0.5F + 4.0F, multiY + (MULTI_H - boldSmallF.getHeight()) * 0.5F + 0.5F, mulAlpha(0xFF0A0B0E, uiAlpha), false);
        boldSmallF.endBatch();

        bodyF.beginBatch();
        bodyF.drawString(en ? "Singleplayer" : "Solo", STACK_X + 32.0F, soloY + (SOLO_H - bodyF.getHeight()) * 0.5F + 0.5F, mulAlpha(soloHov ? 0xFFFFFFFF : 0xFFE6EAF4, uiAlpha));
        bodyF.drawString("Modules", STACK_X + 32.0F, modY + (MOD_H - bodyF.getHeight()) * 0.5F + 0.5F, mulAlpha(modHov ? 0xFFFFDF9E : 0xFFF2C879, uiAlpha));
        bodyF.endBatch();

        smallF.beginBatch();
        smallF.drawString(sessionName, sessX + 21.0F, TOP_Y + 8.0F, mulAlpha(0xFFECEFF6, uiAlpha));
        smallF.drawString("RSHIFT", STACK_X + STACK_W - smallF.getStringWidth("RSHIFT") - 10.0F, modY + (MOD_H - smallF.getHeight()) * 0.5F + 0.5F, mulAlpha(0xFF7D869C, uiAlpha));
        smallF.drawCentered("Hypixel", STACK_X + subW * 0.5F, subY + (SUB_H - smallF.getHeight()) * 0.5F + 0.5F, mulAlpha(hypHov ? 0xFFFFDF9E : 0xFFB8C1D4, uiAlpha), false);
        smallF.drawCentered("Options", STACK_X + subW + SUB_GAP + subW * 0.5F, subY + (SUB_H - smallF.getHeight()) * 0.5F + 0.5F, mulAlpha(optHov ? 0xFFFFFFFF : 0xFFB8C1D4, uiAlpha), false);
        smallF.drawCentered("Mods", STACK_X + (subW + SUB_GAP) * 2.0F + subW * 0.5F, subY + (SUB_H - smallF.getHeight()) * 0.5F + 0.5F, mulAlpha(fmlHov ? 0xFFFFFFFF : 0xFFB8C1D4, uiAlpha), false);
        if (showLastSrv) {
            String srvLabel = (PureFPSConfig.lastServerName != null && !PureFPSConfig.lastServerName.isEmpty())
                    ? PureFPSConfig.lastServerName
                    : PureFPSConfig.lastServerIp;
            String trimmedSrv = smallF.trimToWidth((en ? "Join " : "Rejoindre ") + srvLabel, STACK_W - 34.0F);
            smallF.drawString(trimmedSrv, STACK_X + 26.0F, lastSrvY + (LAST_SRV_H - smallF.getHeight()) * 0.5F + 0.5F, mulAlpha(lastSrvHov ? 0xFFFFDF9E : 0xFFD8DFEC, uiAlpha));
        }
        if (topHint != null) {
            smallF.drawCentered(topHint, hintX + hintW * 0.5F, TOP_Y + TOP_BTN_SZ + 11.0F, mulAlpha(0xFFECEFF6, uiAlpha), false);
        }
        smallF.endBatch();
    }

    private static void drawGlassButton(float x, float y, float w, float h, float r, boolean hov, boolean goldTint, float uiAlpha) {
        int top = hov ? (goldTint ? 0xE61D1A18 : 0xE6161B28) : 0xC80B0E16;
        int bot = hov ? (goldTint ? 0xE6151314 : 0xE610141E) : 0xC8080A10;
        int brd = hov ? (goldTint ? 0x70F5B85C : 0x44FFFFFF) : (goldTint ? 0x34F5B85C : 0x20FFFFFF);
        NanoUiRenderer.drawRoundedGradient(x, y, w, h, r, mulAlpha(top, uiAlpha), mulAlpha(bot, uiAlpha), mulAlpha(brd, uiAlpha), 1.0F);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton != 0 || this.lastUiAlpha < 0.35F) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        float dw = NanoUiRenderer.desktopWidth(this.mc);
        float dh = NanoUiRenderer.desktopHeight(this.mc);
        float mx = NanoUiRenderer.desktopEventMouseX(this.mc);
        float my = NanoUiRenderer.desktopEventMouseY(this.mc);

        int act = hitTestDesktopAction(dw, dh, mx, my);
        if (act < 0) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        switch (act) {
            case ACT_MULTI:
                MashAudioEngine.playJoinAction();
                this.mc.displayGuiScreen(new GuiMultiplayer(this));
                break;
            case ACT_SOLO:
                MashAudioEngine.playJoinAction();
                this.mc.displayGuiScreen(new GuiSelectWorld(this));
                break;
            case ACT_MODULES:
                MashAudioEngine.playMenuOpen();
                this.mc.displayGuiScreen(new GuiMashClientMenu(this));
                break;
            case ACT_HYPIXEL:
                MashAudioEngine.playJoinAction();
                PureFPSConfig.recordJoinedServer("Hypixel", "mc.hypixel.net");
                ServerData hypixel = new ServerData("Hypixel", "mc.hypixel.net", false);
                this.mc.displayGuiScreen(new GuiConnecting(this, this.mc, hypixel));
                break;
            case ACT_LAST_SERVER:
                if (hasLastServer()) {
                    MashAudioEngine.playJoinAction();
                    String name = (PureFPSConfig.lastServerName != null && !PureFPSConfig.lastServerName.isEmpty())
                            ? PureFPSConfig.lastServerName
                            : PureFPSConfig.lastServerIp;
                    ServerData last = new ServerData(name, PureFPSConfig.lastServerIp, false);
                    this.mc.displayGuiScreen(new GuiConnecting(this, this.mc, last));
                }
                break;
            case ACT_OPTIONS:
                MashAudioEngine.playClick();
                this.mc.displayGuiScreen(new GuiOptions(this, this.mc.gameSettings));
                break;
            case ACT_MODS:
                MashAudioEngine.playClick();
                this.mc.displayGuiScreen(new GuiModList(this));
                break;
            case ACT_BH_SOUND:
                PureFPSConfig.blackHoleMenuSound = !PureFPSConfig.blackHoleMenuSound;
                MashModules.syncFromPureConfig();
                MashModules.markDirty();
                MashModules.saveAsyncIfDirty();
                PureFPSConfig.save();
                MashAudioEngine.playToggle(PureFPSConfig.blackHoleMenuSound);
                MashAudioEngine.updateMenuAmbientLoop(!Display.isCreated() || Display.isActive());
                break;
            case ACT_SOUND:
                boolean next = !PureFPSConfig.sfxEnabled;
                PureFPSConfig.sfxEnabled = next;
                PureFPSConfig.save();
                if (next) {
                    MashAudioEngine.playToggle(true);
                }
                MashAudioEngine.updateMenuAmbientLoop(!Display.isCreated() || Display.isActive());
                break;
            case ACT_QUIT:
                MashAudioEngine.updateMenuAmbientLoop(false);
                MashAudioEngine.playClick();
                this.mc.shutdown();
                break;
            default:
                break;
        }
    }

    private static int hitTestDesktopAction(float dw, float dh, float mx, float my) {
        float quitX = dw - 48.0F;
        float soundX = quitX - 32.0F;
        float bhSoundX = soundX - 32.0F;
        if (inside(mx, my, quitX, TOP_Y, TOP_BTN_SZ, TOP_BTN_SZ)) return ACT_QUIT;
        if (inside(mx, my, soundX, TOP_Y, TOP_BTN_SZ, TOP_BTN_SZ)) return ACT_SOUND;
        if (inside(mx, my, bhSoundX, TOP_Y, TOP_BTN_SZ, TOP_BTN_SZ)) return ACT_BH_SOUND;

        float stackTopY = computeStackTopY(dh);
        float multiY = stackTopY;
        if (inside(mx, my, STACK_X, multiY, STACK_W, MULTI_H)) return ACT_MULTI;

        float soloY = multiY + MULTI_H + 7.0F;
        if (inside(mx, my, STACK_X, soloY, STACK_W, SOLO_H)) return ACT_SOLO;

        float modY = soloY + SOLO_H + 7.0F;
        if (inside(mx, my, STACK_X, modY, STACK_W, MOD_H)) return ACT_MODULES;

        float subY = modY + MOD_H + 8.0F;
        float subW = (STACK_W - SUB_GAP * 2.0F) / 3.0F;
        if (inside(mx, my, STACK_X, subY, subW, SUB_H)) return ACT_HYPIXEL;
        if (inside(mx, my, STACK_X + subW + SUB_GAP, subY, subW, SUB_H)) return ACT_OPTIONS;
        if (inside(mx, my, STACK_X + (subW + SUB_GAP) * 2.0F, subY, subW, SUB_H)) return ACT_MODS;

        if (hasLastServer()) {
            float lastSrvY = subY + SUB_H + 6.0F;
            if (inside(mx, my, STACK_X, lastSrvY, STACK_W, LAST_SRV_H)) return ACT_LAST_SERVER;
        }

        return -1;
    }

    private static boolean inside(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= (x + w) && my >= y && my <= (y + h);
    }

    private static int mulAlpha(int argb, float factor) {
        if (factor >= 0.995F) {
            return argb;
        }
        int a = (argb >>> 24) & 0xFF;
        int na = Math.max(0, Math.min(255, Math.round(a * factor)));
        return (na << 24) | (argb & 0x00FFFFFF);
    }
}

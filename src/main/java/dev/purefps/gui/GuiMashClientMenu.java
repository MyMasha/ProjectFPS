package dev.purefps.gui;

import dev.purefps.client.audio.MashAudioEngine;
import dev.purefps.client.ui.NanoUiRenderer;
import dev.purefps.client.ui.SmoothFontRenderer;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import dev.purefps.module.HudSnapEngine;
import dev.purefps.module.MashCategory;
import dev.purefps.module.MashFlags;
import dev.purefps.module.MashHudRenderer;
import dev.purefps.module.MashModule;
import dev.purefps.module.MashModules;
import dev.purefps.module.MashSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Menu des modules MashClient 4.4 (touche RSHIFT) :
 * - Grille compacte 3 colonnes style Lunar Client avec badges d'icônes vectorielles SDF.
 * - Recherche FR/EN instantanée, tiroir de réglages clic droit et éditeur HUD magnétique.
 */
public class GuiMashClientMenu extends GuiScreen {

    private final GuiScreen parentScreen;

    private enum NavTab {
        ALL("All", "Tous", "Modules", "Modules", null),
        ACTIVE("Active", "Actifs", "Active", "Actifs", null),
        FAVORITES("Favorites", "Favoris", "Favorites", "Favoris", null),
        HUD("HUD", "HUD", "HUD", "HUD", MashCategory.HUD),
        PVP("PvP", "PvP", "PvP", "PvP", MashCategory.PVP),
        VISUAL("Visual", "Visuel", "Visual", "Visuel", MashCategory.VISUAL),
        HYPIXEL("Hypixel", "Hypixel", "Hypixel", "Hypixel", MashCategory.HYPIXEL),
        WORLD("World", "Monde", "World", "Monde", MashCategory.WORLD),
        ENGINE("FPS", "FPS", "Performance", "Performance", MashCategory.ENGINE);

        final String pillLabelEn;
        final String pillLabelFr;
        final String headerTitleEn;
        final String headerTitleFr;
        final MashCategory category;

        NavTab(String pillLabelEn, String pillLabelFr, String headerTitleEn, String headerTitleFr, MashCategory category) {
            this.pillLabelEn = pillLabelEn;
            this.pillLabelFr = pillLabelFr;
            this.headerTitleEn = headerTitleEn;
            this.headerTitleFr = headerTitleFr;
            this.category = category;
        }

        String pillLabel() {
            return PureFPSConfig.isEnglish() ? pillLabelEn : pillLabelFr;
        }

        String headerTitle() {
            return PureFPSConfig.isEnglish() ? headerTitleEn : headerTitleFr;
        }
    }

    private static NavTab persistedTab = NavTab.ALL;
    private static String persistedSearch = "";

    private NavTab currentTab = persistedTab;
    private String searchQuery = persistedSearch;
    private boolean searchFocused = false;
    private List<MashModule> filteredModules = new ArrayList<>();

    private float gridScroll = 0.0F;
    private float gridScrollTarget = 0.0F;
    private float gridMaxScroll = 0.0F;

    private float inspectorScroll = 0.0F;
    private float inspectorScrollTarget = 0.0F;
    private float inspectorMaxScroll = 0.0F;

    private MashModule inspectedModule = null;
    private MashSetting.NumberSetting activeDraggingSlider = null;
    private float activeSliderX = 0.0F;
    private float activeSliderW = 120.0F;

    private MashSetting.ColorSetting expandedColorSetting = null;
    private int activeColorDragChannel = -1;
    private float activeColorBarX = 0.0F;
    private float activeColorBarW = 120.0F;

    private MashSetting.KeybindSetting listeningKeybind = null;

    private boolean magneticSnapEnabled = true;
    private boolean hudCanvasMode = false;
    private MashModule selectedHud = null;
    private boolean draggingHud = false;
    private boolean resizingHud = false;
    private float dragOffsetX = 0.0F;
    private float dragOffsetY = 0.0F;
    private float dragOriginX = 0.0F;
    private float dragOriginY = 0.0F;
    private float resizeStartMouseX = 0.0F;
    private float resizeStartScale = 1.0F;
    private HudSnapEngine.SnapResult activeSnap = null;
    private final List<HudSnapEngine.Box> snapScratchBoxes = new ArrayList<>(68);

    private float panelX;
    private float panelY;
    private float panelW;
    private float panelH;
    private float panelAlpha = 1.0F;
    private int lastHoveredCard = -1;
    private long openedAtMs = 0L;

    private static final float TOP_BAR_H = 46.0F;
    private static final float SUB_HEADER_H = 46.0F;
    private static final float FOOTER_H = 36.0F;
    private static final float INSPECTOR_W = 336.0F;

    private static final float CARD_H = 50.0F;
    private static final float CARD_GAP_X = 9.0F;
    private static final float CARD_GAP_Y = 8.0F;

    public GuiMashClientMenu(GuiScreen parentScreen) {
        this.parentScreen = parentScreen;
    }

    public GuiMashClientMenu(GuiScreen parentScreen, boolean focusHudTab) {
        this.parentScreen = parentScreen;
        if (focusHudTab) {
            this.currentTab = NavTab.HUD;
            persistedTab = NavTab.HUD;
        }
    }

    @Override
    public void initGui() {
        this.openedAtMs = System.currentTimeMillis();
        Keyboard.enableRepeatEvents(true);
        MashModules.ensureRegistered();
        rebuildFilteredList();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        persistedTab = this.currentTab;
        persistedSearch = this.searchQuery;
        MashModules.saveAsyncIfDirty();
        PureFPSConfig.saveIfDirty();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    public boolean isCanvasPeekActive() {
        return this.hudCanvasMode || this.draggingHud || this.resizingHud || Keyboard.isKeyDown(Keyboard.KEY_TAB);
    }

    private void rebuildFilteredList() {
        boolean onlyEn = this.currentTab == NavTab.ACTIVE;
        boolean onlyFav = this.currentTab == NavTab.FAVORITES;
        this.filteredModules = MashModules.filter(this.currentTab.category, onlyEn, onlyFav, this.searchQuery);
        this.gridScroll = 0.0F;
        this.gridScrollTarget = 0.0F;
    }

    private void computeDesktopLayout(float dw, float dh) {
        this.panelW = Math.min(1040.0F, Math.max(760.0F, dw - 84.0F));
        this.panelH = Math.min(596.0F, Math.max(450.0F, dh - 72.0F));
        this.panelX = (dw - this.panelW) * 0.5F;
        this.panelY = (dh - this.panelH) * 0.5F;
    }

    private static float computeTabWidth(NavTab tab, SmoothFontRenderer smallF) {
        return smallF.getStringWidth(tab.pillLabel) + 30.0F;
    }

    private static void drawNavTabIcon(NavTab tab, float cx, float cy, float size, int argb) {
        switch (tab) {
            case ALL:
            case HUD:
                NanoUiRenderer.drawGridIcon(cx, cy, size, argb);
                break;
            case ACTIVE:
            case HYPIXEL:
                NanoUiRenderer.drawBoltIcon(cx, cy, size, argb);
                break;
            case FAVORITES:
                NanoUiRenderer.drawStarIcon(cx, cy, size, true, argb);
                break;
            case PVP:
                NanoUiRenderer.drawCrosshairIcon(cx, cy, size, argb);
                break;
            case VISUAL:
                NanoUiRenderer.drawEyeIcon(cx, cy, size, argb);
                break;
            case WORLD:
                NanoUiRenderer.drawGlobeIcon(cx, cy, size, argb);
                break;
            case ENGINE:
                NanoUiRenderer.drawCpuIcon(cx, cy, size, argb);
                break;
            default:
                NanoUiRenderer.drawGridIcon(cx, cy, size, argb);
                break;
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && !draggingHud && !resizingHud && !hudCanvasMode) {
            float dw = NanoUiRenderer.desktopWidth(this.mc);
            float dh = NanoUiRenderer.desktopHeight(this.mc);
            computeDesktopLayout(dw, dh);
            float dmx = NanoUiRenderer.desktopEventMouseX(this.mc);
            float dmy = NanoUiRenderer.desktopEventMouseY(this.mc);
            float delta = (wheel > 0 ? -52.0F : 52.0F);

            float bodyTop = panelY + TOP_BAR_H + SUB_HEADER_H;
            float bodyBot = panelY + panelH - FOOTER_H;
            if (inspectedModule != null && inside(dmx, dmy, getInspectorX(), panelY + TOP_BAR_H, INSPECTOR_W, panelH - TOP_BAR_H - FOOTER_H)) {
                inspectorScrollTarget = clamp(inspectorScrollTarget + delta, 0.0F, inspectorMaxScroll);
            } else if (inside(dmx, dmy, panelX, bodyTop, panelW, bodyBot - bodyTop)) {
                gridScrollTarget = clamp(gridScrollTarget + delta, 0.0F, gridMaxScroll);
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ScaledResolution sr = new ScaledResolution(this.mc);
        NanoUiRenderer.setScaleFactor(sr.getScaleFactor());

        float dw = NanoUiRenderer.desktopWidth(this.mc);
        float dh = NanoUiRenderer.desktopHeight(this.mc);
        float dmx = NanoUiRenderer.desktopMouseX(this.mc);
        float dmy = NanoUiRenderer.desktopMouseY(this.mc);
        computeDesktopLayout(dw, dh);

        if (selectedHud != null && !selectedHud.isEnabled()) {
            selectedHud = null;
            draggingHud = false;
            resizingHud = false;
            activeSnap = null;
        }
        if (draggingHud && selectedHud != null) {
            updateHudMagneticDrag(mouseX, mouseY);
        } else if (resizingHud && selectedHud != null && selectedHud.hudScale != null) {
            float dx = (mouseX - resizeStartMouseX) / 80.0F;
            selectedHud.hudScale.set(resizeStartScale + dx);
        }

        if (activeDraggingSlider != null) {
            float frac = (dmx - activeSliderX) / Math.max(1.0F, activeSliderW);
            activeDraggingSlider.setFraction(frac);
        } else if (activeColorDragChannel >= 0 && expandedColorSetting != null) {
            float frac = clamp((dmx - activeColorBarX) / Math.max(1.0F, activeColorBarW), 0.0F, 1.0F);
            float h = expandedColorSetting.getHue();
            float s = expandedColorSetting.getSaturation();
            float b = expandedColorSetting.getBrightness();
            int a = expandedColorSetting.getAlpha();
            if (activeColorDragChannel == 0) h = frac;
            else if (activeColorDragChannel == 1) s = frac;
            else if (activeColorDragChannel == 2) b = frac;
            else if (activeColorDragChannel == 3) a = Math.round(frac * 255.0F);
            expandedColorSetting.setHsbAndAlpha(h, s, b, a);
        }

        gridScroll += (gridScrollTarget - gridScroll) * 0.32F;
        inspectorScroll += (inspectorScrollTarget - inspectorScroll) * 0.32F;

        boolean canvasPeek = hudCanvasMode || draggingHud || resizingHud || Keyboard.isKeyDown(Keyboard.KEY_TAB);
        float targetAlpha = hudCanvasMode ? 0.0F : ((draggingHud || resizingHud || Keyboard.isKeyDown(Keyboard.KEY_TAB)) ? 0.10F : 1.0F);
        panelAlpha += (targetAlpha - panelAlpha) * 0.30F;

        NanoUiRenderer.beginBatch();
        int backdropAlpha = canvasPeek ? 0x2404060B : 0x7204060B;
        NanoUiRenderer.drawRoundedRect(-2.0F, -2.0F, this.width + 4.0F, this.height + 4.0F, 0.0F, backdropAlpha, 0, 0.0F);
        NanoUiRenderer.endBatch();

        String selId = selectedHud != null ? selectedHud.getId() : null;
        MashHudRenderer.renderOverlay(this.mc, sr, true, selId, draggingHud || resizingHud);
        renderHudInteractiveSelectionAndSnapGuides(mouseX, mouseY, canvasPeek);

        NanoUiRenderer.beginDesktopViewport(this.mc);
        try {
            if (panelAlpha > 0.025F) {
                renderDesktopStudioWindow(dw, dh, dmx, dmy, panelAlpha);
            }
            if (hudCanvasMode) {
                renderHudCanvasFloatingBar(dw, dmx, dmy);
            }
        } finally {
            NanoUiRenderer.endDesktopViewport();
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void renderHudCanvasFloatingBar(float dw, float dmx, float dmy) {
        float bw = 428.0F;
        float bh = 34.0F;
        float bx = (dw - bw) * 0.5F;
        float by = 14.0F;
        boolean backHov = inside(dmx, dmy, bx + 5.0F, by + 4.0F, 84.0F, 26.0F);
        boolean snapHov = inside(dmx, dmy, bx + bw - 128.0F, by + 4.0F, 123.0F, 26.0F);

        NanoUiRenderer.beginBatch();
        NanoUiRenderer.drawShadow(bx, by, bw, bh, 17.0F, 14.0F, 0xAA000000);
        NanoUiRenderer.drawRoundedRect(bx, by, bw, bh, 17.0F, 0xE8080A11, 0x34FFFFFF, 1.0F);
        NanoUiRenderer.drawRoundedGradient(
                bx + 5.0F, by + 4.0F, 84.0F, 26.0F, 13.0F,
                backHov ? 0xFFF7CB7B : 0xFFEBB65B,
                backHov ? 0xFFDF9938 : 0xFFCE8B2E,
                0x88FFE6B3, 1.0F
        );
        NanoUiRenderer.drawRoundedRect(
                bx + bw - 128.0F, by + 4.0F, 123.0F, 26.0F, 13.0F,
                magneticSnapEnabled ? 0x2238BDF8 : 0xFF121622,
                magneticSnapEnabled ? (snapHov ? 0x9938BDF8 : 0x5838BDF8) : 0x24FFFFFF,
                1.0F
        );
        NanoUiRenderer.endBatch();

        SmoothFontRenderer boldSmallF = SmoothFontRenderer.uiBoldSmall();
        SmoothFontRenderer smallF = SmoothFontRenderer.uiSmall();
        boolean en = PureFPSConfig.isEnglish();
        boldSmallF.drawCentered(en ? "Back" : "Retour", bx + 47.0F, by + 11.5F, 0xFF0A0B0E, false);
        smallF.drawCentered(en ? "Drag HUD widgets  \u00B7  SHIFT: Axis" : "Glisser les éléments  \u00B7  SHIFT : Axe", bx + 194.0F, by + 11.5F, 0xFFC5CEE0, false);
        smallF.drawCentered(
                magneticSnapEnabled ? (en ? "Snap: ON" : "Aimantation : ON") : (en ? "Snap: OFF" : "Aimantation : OFF"),
                bx + bw - 66.5F, by + 11.5F,
                magneticSnapEnabled ? 0xFF7DD3FC : 0xFF949CB0, false
        );
    }

    private void updateHudMagneticDrag(int mouseX, int mouseY) {
        float rawX = mouseX - dragOffsetX;
        float rawY = mouseY - dragOffsetY;
        float w = selectedHud.getScaledHudW();
        float h = selectedHud.getScaledHudH();
        boolean shiftAxis = isShiftKeyDown();

        if (!magneticSnapEnabled && !shiftAxis) {
            float cx = clamp(rawX, 1.0F, Math.max(1.0F, this.width - w - 1.0F));
            float cy = clamp(rawY, 1.0F, Math.max(1.0F, this.height - h - 1.0F));
            selectedHud.setHudPos(cx, cy);
            activeSnap = null;
            return;
        }

        snapScratchBoxes.clear();
        if (magneticSnapEnabled) {
            List<MashModule> hudMods = MashModules.getHudModules();
            for (int i = 0; i < hudMods.size(); i++) {
                MashModule m = hudMods.get(i);
                if (m.isEnabled() && m != selectedHud) {
                    snapScratchBoxes.add(new HudSnapEngine.Box(m.getId(), m.getHudX(), m.getHudY(), m.getScaledHudW(), m.getScaledHudH()));
                }
            }
        }

        float radius = magneticSnapEnabled ? HudSnapEngine.DEFAULT_SNAP_RADIUS : 0.0F;
        activeSnap = HudSnapEngine.computeSnap(
                selectedHud.getId(),
                rawX,
                rawY,
                w,
                h,
                dragOriginX,
                dragOriginY,
                shiftAxis,
                this.width,
                this.height,
                snapScratchBoxes,
                radius
        );
        selectedHud.setHudPos(activeSnap.x, activeSnap.y);
    }

    private void renderHudInteractiveSelectionAndSnapGuides(int mouseX, int mouseY, boolean showAllOutlines) {
        List<MashModule> hudMods = MashModules.getHudModules();
        MashModule hoveredHud = (!draggingHud && !resizingHud) ? hitTestHudOutsideOrCanvas(mouseX, mouseY) : null;

        NanoUiRenderer.beginBatch();

        if ((draggingHud || resizingHud) && activeSnap != null) {
            if (activeSnap.hasVerticalGuide) {
                int glow = activeSnap.verticalIsGlueOrCenter ? 0x44F5B85C : 0x3A38BDF8;
                int core = activeSnap.verticalIsGlueOrCenter ? 0xF5F5B85C : 0xEE38BDF8;
                NanoUiRenderer.drawLine(activeSnap.verticalLineX, activeSnap.vMinY, activeSnap.verticalLineX, activeSnap.vMaxY, 3.2F, glow);
                NanoUiRenderer.drawLine(activeSnap.verticalLineX, activeSnap.vMinY, activeSnap.verticalLineX, activeSnap.vMaxY, 1.0F, core);
            }
            if (activeSnap.hasHorizontalGuide) {
                int glow = activeSnap.horizontalIsGlueOrCenter ? 0x44F5B85C : 0x3A38BDF8;
                int core = activeSnap.horizontalIsGlueOrCenter ? 0xF5F5B85C : 0xEE38BDF8;
                NanoUiRenderer.drawLine(activeSnap.hMinX, activeSnap.horizontalLineY, activeSnap.hMaxX, activeSnap.horizontalLineY, 3.2F, glow);
                NanoUiRenderer.drawLine(activeSnap.hMinX, activeSnap.horizontalLineY, activeSnap.hMaxX, activeSnap.horizontalLineY, 1.0F, core);
            }
        }

        if (showAllOutlines) {
            for (int i = 0; i < hudMods.size(); i++) {
                MashModule m = hudMods.get(i);
                if (!m.isEnabled() || m == selectedHud) {
                    continue;
                }
                NanoUiRenderer.drawRoundedRect(
                        m.getHudX() - 1.0F,
                        m.getHudY() - 1.0F,
                        m.getScaledHudW() + 2.0F,
                        m.getScaledHudH() + 2.0F,
                        5.0F,
                        0x00000000,
                        0x4C94A3B8,
                        1.0F
                );
            }
        }

        if (hoveredHud != null && hoveredHud != selectedHud) {
            NanoUiRenderer.drawRoundedRect(
                    hoveredHud.getHudX() - 1.5F,
                    hoveredHud.getHudY() - 1.5F,
                    hoveredHud.getScaledHudW() + 3.0F,
                    hoveredHud.getScaledHudH() + 3.0F,
                    5.5F,
                    0x16F5B85C,
                    0xBB38BDF8,
                    1.2F
            );
        }

        String coordBadge = null;
        float badgeX = 0.0F;
        float badgeY = 0.0F;
        float badgeW = 0.0F;

        if (selectedHud != null && selectedHud.isEnabled()) {
            float sx = selectedHud.getHudX();
            float sy = selectedHud.getHudY();
            float sw = selectedHud.getScaledHudW();
            float sh = selectedHud.getScaledHudH();

            NanoUiRenderer.drawRoundedRect(
                    sx - 2.0F,
                    sy - 2.0F,
                    sw + 4.0F,
                    sh + 4.0F,
                    6.0F,
                    0x14F5B85C,
                    0xEEF5B85C,
                    1.4F
            );
            NanoUiRenderer.drawCircle(sx + sw + 1.5F, sy + sh + 1.5F, 3.3F, 0xFFF5B85C);
            NanoUiRenderer.drawCircle(sx + sw + 1.5F, sy + sh + 1.5F, 1.4F, 0xFF0A0B0E);

            coordBadge = selectedHud.getDisplayName() + "  \u00B7  " + Math.round(sx) + ", " + Math.round(sy)
                    + String.format(Locale.US, "  (%.2fx)", selectedHud.getScale());
            badgeW = SmoothFontRenderer.small().getStringWidth(coordBadge) + 12.0F;
            badgeX = clamp(sx + sw * 0.5F - badgeW * 0.5F, 4.0F, this.width - badgeW - 4.0F);
            badgeY = (sy > 20.0F) ? (sy - 16.5F) : (sy + sh + 5.0F);
            NanoUiRenderer.drawRoundedRect(badgeX, badgeY, badgeW, 13.5F, 4.5F, 0xEA090C14, 0x88F5B85C, 1.0F);
        }

        NanoUiRenderer.endBatch();

        if (coordBadge != null) {
            SmoothFontRenderer smallHud = SmoothFontRenderer.small();
            smallHud.beginBatch();
            smallHud.drawCentered(coordBadge, badgeX + badgeW * 0.5F, badgeY + 3.0F, 0xFFF7CE86, false);
            smallHud.endBatch();
        }
    }

    private void renderDesktopStudioWindow(float dw, float dh, float dmx, float dmy, float alpha) {
        float px = panelX;
        float py = panelY;
        float pw = panelW;
        float ph = panelH;
        boolean hasInspector = inspectedModule != null;
        float inspectorW = hasInspector ? INSPECTOR_W : 0.0F;
        boolean interactive = alpha > 0.72F;
        boolean en = PureFPSConfig.isEnglish();

        SmoothFontRenderer headerF = SmoothFontRenderer.uiHeader();
        SmoothFontRenderer cardTitleF = SmoothFontRenderer.uiCardTitle();
        SmoothFontRenderer bodyF = SmoothFontRenderer.uiBody();
        SmoothFontRenderer smallF = SmoothFontRenderer.uiSmall();
        SmoothFontRenderer boldSmallF = SmoothFontRenderer.uiBoldSmall();

        NavTab[] tabs = NavTab.values();
        float[] tabWidths = new float[tabs.length];
        float tabsTotalW = 8.0F;
        for (int i = 0; i < tabs.length; i++) {
            float tw = computeTabWidth(tabs[i], smallF);
            tabWidths[i] = tw;
            tabsTotalW += tw;
        }
        float closeBtnX = px + pw - 38.0F;
        float tabsPillX = closeBtnX - tabsTotalW - 10.0F;
        float tabsPillY = py + 8.0F;

        float subY = py + TOP_BAR_H;
        float hudBtnW = 106.0F;
        float hudBtnX = px + pw - hudBtnW - 18.0F;
        float hudBtnY = subY + 8.0F;
        float searchW = 276.0F;
        float searchX = hudBtnX - searchW - 8.0F;
        float searchY = subY + 8.0F;

        NanoUiRenderer.beginBatch();

        NanoUiRenderer.drawShadow(px, py, pw, ph, 14.0F, 24.0F, mulAlpha(0xC8000000, alpha));
        NanoUiRenderer.drawRoundedGradient(
                px, py, pw, ph, 14.0F,
                mulAlpha(0xEA090B11, alpha),
                mulAlpha(0xF206080D, alpha),
                mulAlpha(0x22FFFFFF, alpha),
                1.0F
        );

        NanoUiRenderer.drawLine(px + 1.0F, py + TOP_BAR_H, px + pw - 1.0F, py + TOP_BAR_H, 1.0F, mulAlpha(0x16FFFFFF, alpha));
        NanoUiRenderer.drawLine(px + 18.0F, subY + SUB_HEADER_H, px + pw - 18.0F, subY + SUB_HEADER_H, 1.0F, mulAlpha(0x14FFFFFF, alpha));
        NanoUiRenderer.drawLine(px + 1.0F, py + ph - FOOTER_H, px + pw - 1.0F, py + ph - FOOTER_H, 1.0F, mulAlpha(0x16FFFFFF, alpha));

        NanoUiRenderer.drawBrandLogo(this.mc, px + 27.0F, py + 23.0F, 19.0F, alpha);

        // Barre d'onglets compacte avec icônes vectorielles
        NanoUiRenderer.drawRoundedRect(
                tabsPillX, tabsPillY, tabsTotalW, 30.0F, 15.0F,
                mulAlpha(0xD80C0F17, alpha), mulAlpha(0x1EFFFFFF, alpha), 1.0F
        );
        float curTabX = tabsPillX + 4.0F;
        for (int i = 0; i < tabs.length; i++) {
            float tw = tabWidths[i];
            boolean sel = currentTab == tabs[i];
            boolean hov = interactive && inside(dmx, dmy, curTabX, tabsPillY + 3.0F, tw, 24.0F);
            if (sel || hov) {
                NanoUiRenderer.drawRoundedRect(
                        curTabX, tabsPillY + 3.0F, tw, 24.0F, 12.0F,
                        mulAlpha(sel ? 0x2AF5B85C : 0x16FFFFFF, alpha),
                        mulAlpha(sel ? 0x6EF5B85C : 0x00000000, alpha),
                        sel ? 1.0F : 0.0F
                );
            }
            int iconCol = sel ? 0xFFFFDF9E : (hov ? 0xFFF2F4F8 : 0xFF7F889E);
            drawNavTabIcon(tabs[i], curTabX + 11.5F, tabsPillY + 15.0F, 9.5F, mulAlpha(iconCol, alpha));
            curTabX += tw;
        }

        // Bouton Fermer compact (24x24)
        boolean closeHov = interactive && inside(dmx, dmy, closeBtnX, py + 11.0F, 24.0F, 24.0F);
        NanoUiRenderer.drawRoundedRect(
                closeBtnX, py + 11.0F, 24.0F, 24.0F, 12.0F,
                mulAlpha(closeHov ? 0xDD3C1820 : 0xFF10131C, alpha),
                mulAlpha(closeHov ? 0x88F87171 : 0x24FFFFFF, alpha),
                1.0F
        );
        NanoUiRenderer.drawCloseIcon(closeBtnX + 12.0F, py + 23.0F, 10.0F, mulAlpha(closeHov ? 0xFFFFB4B4 : 0xFFB4BCD0, alpha));

        // Barre de recherche compacte (30px)
        boolean searchHov = interactive && inside(dmx, dmy, searchX, searchY, searchW, 30.0F);
        NanoUiRenderer.drawRoundedRect(
                searchX, searchY, searchW, 30.0F, 8.0F,
                mulAlpha(0xFF0D1018, alpha),
                mulAlpha(searchFocused ? 0xBBF5B85C : (searchHov ? 0x42FFFFFF : 0x22FFFFFF), alpha),
                1.0F
        );
        NanoUiRenderer.drawRing(searchX + 14.0F, searchY + 13.8F, 4.2F, 1.35F, mulAlpha(searchFocused ? 0xFFF5B85C : 0xFF7D869C, alpha));
        NanoUiRenderer.drawLine(searchX + 17.2F, searchY + 17.0F, searchX + 20.5F, searchY + 20.3F, 1.4F, mulAlpha(searchFocused ? 0xFFF5B85C : 0xFF7D869C, alpha));

        if (!searchQuery.isEmpty()) {
            boolean clrHov = interactive && inside(dmx, dmy, searchX + searchW - 24.0F, searchY + 5.0F, 20.0F, 20.0F);
            NanoUiRenderer.drawRoundedRect(
                    searchX + searchW - 24.0F, searchY + 5.0F, 20.0F, 20.0F, 10.0F,
                    mulAlpha(clrHov ? 0x36FFFFFF : 0x1AFFFFFF, alpha), 0, 0.0F
            );
            NanoUiRenderer.drawCloseIcon(searchX + searchW - 14.0F, searchY + 15.0F, 8.5F, mulAlpha(0xFFD5DCEB, alpha));
        }

        // Bouton Éditer HUD compact (30px + icône grille)
        boolean hudHov = interactive && inside(dmx, dmy, hudBtnX, hudBtnY, hudBtnW, 30.0F);
        NanoUiRenderer.drawRoundedRect(
                hudBtnX, hudBtnY, hudBtnW, 30.0F, 8.0F,
                mulAlpha(hudHov ? 0x2C38BDF8 : 0x1838BDF8, alpha),
                mulAlpha(hudHov ? 0x8838BDF8 : 0x4C38BDF8, alpha),
                1.0F
        );
        NanoUiRenderer.drawGridIcon(hudBtnX + 15.0F, hudBtnY + 15.0F, 9.5F, mulAlpha(0xFF93DDFF, alpha));

        // Pied de page compact (36px)
        float footY = py + ph - FOOTER_H;
        PureFPSProfile activeProf = PureFPSConfig.activeProfile;
        PureFPSProfile[] profs = {PureFPSProfile.FPS, PureFPSProfile.BALANCED, PureFPSProfile.QUALITY};
        NanoUiRenderer.drawRoundedRect(
                px + 18.0F, footY + 5.0F, 214.0F, 26.0F, 7.0F,
                mulAlpha(0xFF0C0F17, alpha), mulAlpha(0x1EFFFFFF, alpha), 1.0F
        );
        float fpW = 68.0F;
        for (int i = 0; i < 3; i++) {
            float bx = px + 21.0F + i * (fpW + 2.0F);
            boolean sel = activeProf == profs[i];
            boolean hov = interactive && inside(dmx, dmy, bx, footY + 7.5F, fpW, 21.0F);
            if (sel || hov) {
                NanoUiRenderer.drawRoundedRect(
                        bx, footY + 7.5F, fpW, 21.0F, 5.5F,
                        mulAlpha(sel ? 0x2EF5B85C : 0x16FFFFFF, alpha),
                        mulAlpha(sel ? 0x6EEAB354 : 0x00000000, alpha),
                        sel ? 1.0F : 0.0F
                );
            }
        }

        float snapPillX = px + 240.0F;
        boolean snapHov = interactive && inside(dmx, dmy, snapPillX, footY + 5.0F, 126.0F, 26.0F);
        NanoUiRenderer.drawRoundedRect(
                snapPillX, footY + 5.0F, 126.0F, 26.0F, 7.0F,
                mulAlpha(magneticSnapEnabled ? 0x1C38BDF8 : 0xFF0C0F17, alpha),
                mulAlpha(magneticSnapEnabled ? (snapHov ? 0x8838BDF8 : 0x4E38BDF8) : 0x20FFFFFF, alpha),
                1.0F
        );

        float langPillX = snapPillX + 132.0F;
        boolean langHov = interactive && inside(dmx, dmy, langPillX, footY + 5.0F, 72.0F, 26.0F);
        NanoUiRenderer.drawRoundedRect(
                langPillX, footY + 5.0F, 72.0F, 26.0F, 7.0F,
                mulAlpha(langHov ? 0x28F5B85C : 0xFF0C0F17, alpha),
                mulAlpha(langHov ? 0x88F5B85C : 0x2EFFFFFF, alpha),
                1.0F
        );

        if (hasInspector) {
            float ix = getInspectorX();
            NanoUiRenderer.drawRoundedRect(
                    ix, subY + SUB_HEADER_H + 1.0F, inspectorW - 1.0F, ph - TOP_BAR_H - SUB_HEADER_H - FOOTER_H - 2.0F, 0.0F,
                    mulAlpha(0x8805070C, alpha), 0, 0.0F
            );
            NanoUiRenderer.drawLine(ix, subY + SUB_HEADER_H, ix, py + ph - FOOTER_H, 1.0F, mulAlpha(0x20FFFFFF, alpha));
        }

        NanoUiRenderer.endBatch();

        String secTitle = currentTab.headerTitle() + "  \u00B7  " + filteredModules.size();
        headerF.drawString(secTitle, px + 20.0F, subY + 13.5F, mulAlpha(0xFFF7F4EC, alpha));

        boldSmallF.beginBatch();
        boldSmallF.drawString("MASHCLIENT", px + 43.0F, py + 17.5F, mulAlpha(0xFFF7F4EC, alpha));
        boldSmallF.endBatch();

        smallF.beginBatch();
        curTabX = tabsPillX + 4.0F;
        for (int i = 0; i < tabs.length; i++) {
            float tw = tabWidths[i];
            boolean sel = currentTab == tabs[i];
            boolean hov = interactive && inside(dmx, dmy, curTabX, tabsPillY + 3.0F, tw, 24.0F);
            int col = sel ? 0xFFFFDF9E : (hov ? 0xFFF7F4EC : 0xFF9AA3B8);
            smallF.drawString(tabs[i].pillLabel(), curTabX + 21.0F, tabsPillY + 9.5F, mulAlpha(col, alpha));
            curTabX += tw;
        }

        String searchDisplay;
        int searchCol;
        if (searchQuery.isEmpty() && !searchFocused) {
            searchDisplay = en ? "Search modules..." : "Rechercher un module...";
            searchCol = 0xFF6E778D;
        } else {
            String caret = (searchFocused && ((System.currentTimeMillis() / 450L) & 1L) == 0L) ? "|" : "";
            searchDisplay = searchQuery + caret;
            searchCol = 0xFFF2F4F8;
        }
        float maxSearchTxtW = searchW - (searchQuery.isEmpty() ? 36.0F : 58.0F);
        smallF.drawString(smallF.trimToWidth(searchDisplay, maxSearchTxtW), searchX + 27.0F, searchY + 9.5F, mulAlpha(searchCol, alpha));

        smallF.drawString(en ? "Edit HUD" : "Éditer HUD", hudBtnX + 27.0F, hudBtnY + 9.5F, mulAlpha(0xFF93DDFF, alpha));

        String[] profNames = en ? new String[]{"FPS", "Balanced", "Quality"} : new String[]{"FPS", "Équilibré", "Qualité"};
        for (int i = 0; i < 3; i++) {
            float bx = px + 21.0F + i * (fpW + 2.0F);
            boolean sel = activeProf == profs[i];
            smallF.drawCentered(profNames[i], bx + fpW * 0.5F, footY + 13.0F, mulAlpha(sel ? 0xFFFFDF9E : 0xFF949CB0, alpha), false);
        }

        smallF.drawCentered(
                magneticSnapEnabled ? (en ? "Snap: ON" : "Aimantation : ON") : (en ? "Snap: OFF" : "Aimantation : OFF"),
                snapPillX + 63.0F, footY + 13.0F,
                mulAlpha(magneticSnapEnabled ? 0xFF7DD3FC : 0xFF8E96AA, alpha), false
        );

        smallF.drawCentered(
                en ? "Lang: EN" : "Lang: FR",
                langPillX + 36.0F, footY + 13.0F,
                mulAlpha(0xFFFFDF9E, alpha), false
        );

        String rightTele = MashModules.getActiveCount() + (en ? " active  \u00B7  " : " actifs  \u00B7  ") + Minecraft.getDebugFPS() + " FPS";
        smallF.drawString(rightTele, px + pw - smallF.getStringWidth(rightTele) - 20.0F, footY + 13.0F, mulAlpha(0xFF7D869C, alpha));
        smallF.endBatch();

        // Grille 3 colonnes compacte (2 colonnes lorsque le tiroir d'options est ouvert)
        float gridX = px + 18.0F;
        float gridY = subY + SUB_HEADER_H + 8.0F;
        float gridW = pw - inspectorW - (hasInspector ? 28.0F : 36.0F);
        float gridH = ph - TOP_BAR_H - SUB_HEADER_H - FOOTER_H - 14.0F;

        NanoUiRenderer.enableDesktopScissor(this.mc, gridX - 2.0F, gridY, gridW + 4.0F, gridH);
        renderModuleCardsGrid(gridX, gridY, gridW, gridH, dmx, dmy, alpha, boldSmallF, bodyF, smallF);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        if (hasInspector) {
            float ix = getInspectorX();
            float iy = subY + SUB_HEADER_H + 4.0F;
            float iw = inspectorW - 6.0F;
            float ih = ph - TOP_BAR_H - SUB_HEADER_H - FOOTER_H - 8.0F;
            NanoUiRenderer.enableDesktopScissor(this.mc, ix, iy, iw, ih);
            renderSubSettingsInspector(ix, iy, iw, ih, dmx, dmy, alpha, cardTitleF, bodyF, smallF);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    private void renderModuleCardsGrid(
            float gx,
            float gy,
            float gw,
            float gh,
            float dmx,
            float dmy,
            float alpha,
            SmoothFontRenderer boldSmallF,
            SmoothFontRenderer bodyF,
            SmoothFontRenderer smallF
    ) {
        int cols = (inspectedModule != null) ? 2 : 3;
        float cardW = (gw - CARD_GAP_X * (cols - 1)) / (float) cols;

        int total = filteredModules.size();
        int rows = (total + cols - 1) / cols;
        float contentH = rows * (CARD_H + CARD_GAP_Y);
        gridMaxScroll = Math.max(0.0F, contentH - gh + 6.0F);
        gridScroll = clamp(gridScroll, 0.0F, gridMaxScroll);

        float rowStride = CARD_H + CARD_GAP_Y;
        int firstRow = Math.max(0, (int) Math.floor((gridScroll - 2.0F) / rowStride));
        int lastRow = Math.min(Math.max(0, rows - 1), (int) Math.ceil((gridScroll + gh + 2.0F) / rowStride));
        int startIdx = Math.min(total, firstRow * cols);
        int endIdx = Math.min(total, (lastRow + 1) * cols);

        boolean mouseInViewport = alpha > 0.72F && inside(dmx, dmy, gx, gy, gw, gh);
        int hoveredIdx = -1;

        NanoUiRenderer.beginBatch();
        for (int i = startIdx; i < endIdx; i++) {
            int col = i % cols;
            int row = i / cols;
            float cx = gx + col * (cardW + CARD_GAP_X);
            float cy = gy + row * rowStride - gridScroll;
            if (cy + CARD_H < gy - 2.0F || cy > gy + gh + 2.0F) {
                continue;
            }

            MashModule m = filteredModules.get(i);
            boolean on = m.isEnabled();
            boolean inspected = inspectedModule == m;
            boolean hov = mouseInViewport && inside(dmx, dmy, cx, cy, cardW, CARD_H);
            if (hov) {
                hoveredIdx = i;
            }

            int topBg = inspected ? 0xEE18161E : (on ? (hov ? 0xEA151822 : 0xE210131C) : (hov ? 0xE411141D : 0xD80B0E15));
            int botBg = inspected ? 0xEE121118 : (on ? 0xE20C0F17 : 0xD8090B11);
            int border = inspected ? 0xEEF5B85C : (on ? (hov ? 0x7AEAB354 : 0x44EAB354) : (hov ? 0x36FFFFFF : 0x1BFFFFFF));

            NanoUiRenderer.drawRoundedGradient(
                    cx, cy, cardW, CARD_H, 8.5F,
                    mulAlpha(topBg, alpha), mulAlpha(botBg, alpha), mulAlpha(border, alpha), 1.0F
            );

            // Badge icône de catégorie à gauche (28x28)
            int badgeBg = on ? 0x26F5B85C : 0xFF10141F;
            int badgeBrd = on ? 0x4CF5B85C : 0x1FFFFFFF;
            int iconCol = on ? 0xFFFFDF9E : (hov ? 0xFFD5DCEB : 0xFF7E889E);
            NanoUiRenderer.drawRoundedRect(
                    cx + 9.0F, cy + 11.0F, 28.0F, 28.0F, 7.0F,
                    mulAlpha(badgeBg, alpha), mulAlpha(badgeBrd, alpha), 1.0F
            );
            NanoUiRenderer.drawCategoryIcon(m.getCategory(), cx + 23.0F, cy + 25.0F, 12.0F, mulAlpha(iconCol, alpha));

            // Étoile favori compacte
            boolean starHov = hov && inside(dmx, dmy, cx + cardW - 86.0F, cy + 13.0F, 20.0F, 24.0F);
            NanoUiRenderer.drawStarIcon(
                    cx + cardW - 76.0F, cy + 25.0F, 10.5F,
                    m.isFavorite(),
                    mulAlpha(m.isFavorite() ? 0xFFF5B85C : (starHov ? 0xFFAAB4C8 : 0xFF4E576B), alpha)
            );

            // Bouton réglages compact (22x20)
            float gearX = cx + cardW - 62.0F;
            float gearY = cy + 15.0F;
            boolean gearHov = hov && inside(dmx, dmy, gearX, gearY, 22.0F, 20.0F);
            NanoUiRenderer.drawRoundedRect(
                    gearX, gearY, 22.0F, 20.0F, 5.5F,
                    mulAlpha(gearHov || inspected ? 0x36F5B85C : 0xFF131722, alpha),
                    mulAlpha(gearHov || inspected ? 0x88F5B85C : 0x20FFFFFF, alpha),
                    1.0F
            );
            NanoUiRenderer.drawSlidersIcon(
                    gearX + 11.0F, gearY + 10.0F, 10.5F,
                    mulAlpha(inspected || gearHov ? 0xFFFFDF9E : 0xFF9AA3B8, alpha)
            );

            // Interrupteur compact style Lunar (28x15)
            NanoUiRenderer.drawCompactSwitch(cx + cardW - 36.0F, cy + 17.5F, on, hov, alpha);
        }

        if (hoveredIdx != this.lastHoveredCard) {
            if (hoveredIdx >= 0) {
                MashAudioEngine.playHover();
            }
            this.lastHoveredCard = hoveredIdx;
        }

        if (gridMaxScroll > 4.0F) {
            float sbTrackX = gx + gw + 1.0F;
            float thumbH = Math.max(24.0F, gh * (gh / (contentH + 1.0F)));
            float thumbY = gy + (gh - thumbH) * (gridScroll / gridMaxScroll);
            NanoUiRenderer.drawRoundedRect(sbTrackX, thumbY, 3.0F, thumbH, 1.5F, mulAlpha(0x66F5B85C, alpha), 0, 0.0F);
        }
        NanoUiRenderer.endBatch();

        boldSmallF.beginBatch();
        for (int i = startIdx; i < endIdx; i++) {
            int col = i % cols;
            int row = i / cols;
            float cx = gx + col * (cardW + CARD_GAP_X);
            float cy = gy + row * rowStride - gridScroll;
            if (cy + CARD_H < gy - 2.0F || cy > gy + gh + 2.0F) {
                continue;
            }
            MashModule m = filteredModules.get(i);
            String title = boldSmallF.trimToWidth(m.getDisplayName(), cardW - 134.0F);
            boldSmallF.drawString(title, cx + 44.0F, cy + 11.0F, mulAlpha(m.isEnabled() ? 0xFFFFFFFF : 0xFFD8DFEC, alpha));
        }
        boldSmallF.endBatch();

        if (total == 0) {
            bodyF.drawCentered(
                    (PureFPSConfig.isEnglish() ? "No results for \"" : "Aucun résultat pour \"") + searchQuery + "\"",
                    gx + gw * 0.5F, gy + gh * 0.44F, mulAlpha(0xFF949CB0, alpha), false
            );
        }

        smallF.beginBatch();
        for (int i = startIdx; i < endIdx; i++) {
            int col = i % cols;
            int row = i / cols;
            float cx = gx + col * (cardW + CARD_GAP_X);
            float cy = gy + row * rowStride - gridScroll;
            if (cy + CARD_H < gy - 2.0F || cy > gy + gh + 2.0F) {
                continue;
            }
            MashModule m = filteredModules.get(i);
            String desc = smallF.trimToWidth(m.getDescription(), cardW - 134.0F);
            smallF.drawString(desc, cx + 44.0F, cy + 28.0F, mulAlpha(0xFF848EA4, alpha));
        }
        smallF.endBatch();
    }

    private void renderSubSettingsInspector(
            float ix,
            float iy,
            float iw,
            float ih,
            float dmx,
            float dmy,
            float alpha,
            SmoothFontRenderer cardTitleF,
            SmoothFontRenderer bodyF,
            SmoothFontRenderer smallF
    ) {
        MashModule mod = inspectedModule;
        if (mod == null) {
            return;
        }

        boolean isCrosshair = (mod == MashModules.MOD_CROSSHAIR);
        float headerBlockH = isCrosshair ? 108.0F : 66.0F;
        float listY = iy + headerBlockH;
        float listH = ih - headerBlockH - 38.0F;

        NanoUiRenderer.beginBatch();
        NanoUiRenderer.drawCompactSwitch(
                ix + iw - 64.0F, iy + 11.5F, mod.isEnabled(),
                inside(dmx, dmy, ix + iw - 66.0F, iy + 9.0F, 32.0F, 20.0F), alpha
        );
        boolean clsHov = inside(dmx, dmy, ix + iw - 28.0F, iy + 8.0F, 22.0F, 22.0F);
        NanoUiRenderer.drawRoundedRect(
                ix + iw - 28.0F, iy + 8.0F, 22.0F, 22.0F, 11.0F,
                mulAlpha(clsHov ? 0xDD3A1820 : 0xFF141824, alpha),
                mulAlpha(clsHov ? 0x88F87171 : 0x28FFFFFF, alpha), 1.0F
        );
        NanoUiRenderer.drawCloseIcon(ix + iw - 17.0F, iy + 19.0F, 9.0F, mulAlpha(clsHov ? 0xFFFFB4B4 : 0xFFB8C0D4, alpha));

        if (isCrosshair) {
            float prevX = ix + 12.0F;
            float prevY = iy + 64.0F;
            float prevW = iw - 20.0F;
            float prevH = 36.0F;
            NanoUiRenderer.drawRoundedRect(prevX, prevY, prevW, prevH, 7.5F, mulAlpha(0xFF0E121C, alpha), mulAlpha(0x28FFFFFF, alpha), 1.0F);
            drawMiniCrosshairPreview(prevX + prevW * 0.5F, prevY + prevH * 0.5F, alpha);
        }

        NanoUiRenderer.drawLine(ix + 12.0F, listY - 3.0F, ix + iw - 8.0F, listY - 3.0F, 1.0F, mulAlpha(0x1EFFFFFF, alpha));
        NanoUiRenderer.endBatch();

        cardTitleF.drawString(
                cardTitleF.trimToWidth(mod.getDisplayName(), iw - 84.0F),
                ix + 12.0F, iy + 9.5F, mulAlpha(0xFFFFDF9E, alpha)
        );
        String altName = PureFPSConfig.isEnglish() ? mod.getName() : mod.getEnglishName();
        smallF.drawString(
                smallF.trimToWidth(altName + "  \u00B7  " + mod.getCategory().getDisplayName(), iw - 84.0F),
                ix + 12.0F, iy + 27.5F, mulAlpha(0xFFC89B54, alpha)
        );
        smallF.drawString(
                smallF.trimToWidth(mod.getDescription(), iw - 20.0F),
                ix + 12.0F, iy + 43.5F, mulAlpha(0xFF8E97AC, alpha)
        );

        NanoUiRenderer.enableDesktopScissor(this.mc, ix, listY, iw, listH);

        List<MashSetting> settings = mod.getSettings();
        float curY = listY + 5.0F - inspectorScroll;
        float totalH = 10.0F;

        NanoUiRenderer.beginBatch();
        for (int i = 0; i < settings.size(); i++) {
            MashSetting s = settings.get(i);
            float rowH = getSettingRowHeight(s);
            if (curY + rowH >= listY - 4.0F && curY <= listY + listH + 4.0F) {
                NanoUiRenderer.drawRoundedRect(
                        ix + 12.0F, curY, iw - 18.0F, rowH - 5.0F, 7.0F,
                        mulAlpha(0xFF0E111B, alpha), mulAlpha(0x1DFFFFFF, alpha), 1.0F
                );
                drawSettingShapes(s, ix + 12.0F, curY, iw - 18.0F, dmx, dmy, alpha);
            }
            curY += rowH;
            totalH += rowH;
        }
        NanoUiRenderer.endBatch();

        inspectorMaxScroll = Math.max(0.0F, totalH - listH);
        inspectorScroll = clamp(inspectorScroll, 0.0F, inspectorMaxScroll);

        smallF.beginBatch();
        curY = listY + 5.0F - inspectorScroll;
        for (int i = 0; i < settings.size(); i++) {
            MashSetting s = settings.get(i);
            float rowH = getSettingRowHeight(s);
            if (curY + rowH >= listY - 4.0F && curY <= listY + listH + 4.0F) {
                drawSettingLabels(smallF, s, ix + 12.0F, curY, iw - 18.0F, alpha);
            }
            curY += rowH;
        }
        smallF.endBatch();

        NanoUiRenderer.enableDesktopScissor(this.mc, ix, iy, iw, ih);
        float resetY = iy + ih - 31.0F;
        boolean resetHov = alpha > 0.72F && inside(dmx, dmy, ix + 12.0F, resetY, iw - 18.0F, 25.0F);
        NanoUiRenderer.beginBatch();
        NanoUiRenderer.drawRoundedRect(
                ix + 12.0F, resetY, iw - 18.0F, 25.0F, 7.0F,
                mulAlpha(resetHov ? 0xFF281720 : 0xFF101420, alpha),
                mulAlpha(resetHov ? 0x88F87171 : 0x24FFFFFF, alpha),
                1.0F
        );
        NanoUiRenderer.endBatch();

        smallF.drawCentered(
                PureFPSConfig.isEnglish() ? "Reset to Defaults" : "Réinitialiser",
                ix + 12.0F + (iw - 18.0F) * 0.5F, resetY + 7.5F,
                mulAlpha(resetHov ? 0xFFFFB4B4 : 0xFFA2AABF, alpha), false
        );
    }

    private void drawMiniCrosshairPreview(float cx, float cy, float alpha) {
        int col = mulAlpha(MashModules.col("pvp_crosshair", "color", System.currentTimeMillis(), 0xFFFFFFFF), alpha);
        float sz = Math.min(11.0F, MashFlags.crosshairSize * 1.4F);
        float gap = Math.min(7.0F, MashFlags.crosshairGap * 1.4F);
        float th = Math.min(3.2F, MashFlags.crosshairThickness * 1.3F);
        int shape = MashFlags.crosshairShape;
        if (shape == 0 || shape == 1) {
            NanoUiRenderer.drawRoundedRect(cx - gap - sz, cy - th * 0.5F, sz, th, 0.5F, col, 0, 0.0F);
            NanoUiRenderer.drawRoundedRect(cx + gap, cy - th * 0.5F, sz, th, 0.5F, col, 0, 0.0F);
            NanoUiRenderer.drawRoundedRect(cx - th * 0.5F, cy - gap - sz, th, sz, 0.5F, col, 0, 0.0F);
            NanoUiRenderer.drawRoundedRect(cx - th * 0.5F, cy + gap, th, sz, 0.5F, col, 0, 0.0F);
            if (shape == 1) {
                NanoUiRenderer.drawCircle(cx, cy, th * 0.7F, col);
            }
        } else if (shape == 2) {
            NanoUiRenderer.drawCircle(cx, cy, Math.max(1.8F, sz * 0.45F), col);
        } else if (shape == 3) {
            NanoUiRenderer.drawRing(cx, cy, Math.max(3.5F, sz), th * NanoUiRenderer.getScaleFactor(), col);
        } else if (shape == 4) {
            NanoUiRenderer.drawLine(cx, cy, cx - sz, cy + sz, th, col);
            NanoUiRenderer.drawLine(cx, cy, cx + sz, cy + sz, th, col);
        } else {
            NanoUiRenderer.drawRoundedRect(cx - sz, cy - sz, sz * 2.0F, sz * 2.0F, 1.0F, 0, col, th * NanoUiRenderer.getScaleFactor());
        }
    }

    private float getSettingRowHeight(MashSetting s) {
        if (s instanceof MashSetting.NumberSetting) {
            return 46.0F;
        }
        if (s instanceof MashSetting.ColorSetting) {
            return (expandedColorSetting == s) ? 118.0F : 36.0F;
        }
        return 36.0F;
    }

    private void drawSettingShapes(MashSetting s, float rx, float ry, float rw, float dmx, float dmy, float alpha) {
        if (s instanceof MashSetting.BooleanSetting) {
            MashSetting.BooleanSetting bs = (MashSetting.BooleanSetting) s;
            NanoUiRenderer.drawCompactSwitch(rx + rw - 36.0F, ry + 8.0F, bs.get(), inside(dmx, dmy, rx, ry, rw, 31.0F), alpha);
        } else if (s instanceof MashSetting.NumberSetting) {
            MashSetting.NumberSetting ns = (MashSetting.NumberSetting) s;
            float sx = rx + 10.0F;
            float sw = rw - 20.0F;
            boolean hov = inside(dmx, dmy, sx - 4.0F, ry + 21.0F, sw + 8.0F, 16.0F);
            NanoUiRenderer.drawSlider(sx, ry + 27.5F, sw, ns.getFraction(), mulAlpha(0xFFEBB65B, alpha), hov || activeDraggingSlider == ns);
        } else if (s instanceof MashSetting.ModeSetting) {
            float pillW = Math.min(126.0F, rw * 0.46F);
            float pillX = rx + rw - pillW - 8.0F;
            boolean hov = inside(dmx, dmy, pillX, ry + 5.0F, pillW, 21.0F);
            NanoUiRenderer.drawRoundedRect(
                    pillX, ry + 5.0F, pillW, 21.0F, 5.5F,
                    mulAlpha(hov ? 0xFF1B2030 : 0xFF141824, alpha),
                    mulAlpha(0x44F5B85C, alpha), 1.0F
            );
        } else if (s instanceof MashSetting.KeybindSetting) {
            MashSetting.KeybindSetting ks = (MashSetting.KeybindSetting) s;
            boolean listening = listeningKeybind == ks;
            float pillW = 86.0F;
            float pillX = rx + rw - pillW - 8.0F;
            NanoUiRenderer.drawRoundedRect(
                    pillX, ry + 5.0F, pillW, 21.0F, 5.5F,
                    mulAlpha(listening ? 0x44F5B85C : 0xFF141824, alpha),
                    mulAlpha(listening ? 0xEEF5B85C : 0x2CFFFFFF, alpha),
                    1.0F
            );
        } else if (s instanceof MashSetting.ColorSetting) {
            MashSetting.ColorSetting cs = (MashSetting.ColorSetting) s;
            int resolved = mulAlpha(cs.resolveArgb(System.currentTimeMillis(), 0), alpha);
            float chX = rx + rw - 84.0F;
            NanoUiRenderer.drawRoundedRect(
                    chX, ry + 5.5F, 48.0F, 20.0F, 5.5F,
                    mulAlpha(cs.isChroma() ? 0x38F5B85C : 0xFF141824, alpha),
                    mulAlpha(cs.isChroma() ? 0xAAF5B85C : 0x24FFFFFF, alpha),
                    1.0F
            );
            NanoUiRenderer.drawRoundedRect(rx + rw - 30.0F, ry + 5.5F, 20.0F, 20.0F, 5.5F, resolved, mulAlpha(0x88FFFFFF, alpha), 1.0F);

            if (expandedColorSetting == cs) {
                float bx = rx + 10.0F;
                float bw = rw - 20.0F;
                int pureHue = Color.HSBtoRGB(cs.getHue(), 1.0F, 1.0F) | 0xFF000000;
                NanoUiRenderer.drawSlider(bx, ry + 40.0F, bw, cs.getHue(), mulAlpha(pureHue, alpha), activeColorDragChannel == 0);
                NanoUiRenderer.drawSlider(bx, ry + 59.0F, bw, cs.getSaturation(), mulAlpha(0xFF38BDF8, alpha), activeColorDragChannel == 1);
                NanoUiRenderer.drawSlider(bx, ry + 78.0F, bw, cs.getBrightness(), mulAlpha(0xFFEBB65B, alpha), activeColorDragChannel == 2);
                NanoUiRenderer.drawSlider(bx, ry + 97.0F, bw, cs.getAlpha() / 255.0F, mulAlpha(0xFFE2E8F0, alpha), activeColorDragChannel == 3);
            }
        }
    }

    private void drawSettingLabels(SmoothFontRenderer smallF, MashSetting s, float rx, float ry, float rw, float alpha) {
        if (s instanceof MashSetting.BooleanSetting) {
            smallF.drawString(smallF.trimToWidth(s.getName(), rw - 52.0F), rx + 10.0F, ry + 10.0F, mulAlpha(0xFFECEFF6, alpha));
        } else if (s instanceof MashSetting.NumberSetting) {
            MashSetting.NumberSetting ns = (MashSetting.NumberSetting) s;
            String val = ns.formatValue();
            float vw = smallF.getStringWidth(val);
            smallF.drawString(smallF.trimToWidth(s.getName(), rw - vw - 24.0F), rx + 10.0F, ry + 7.5F, mulAlpha(0xFFECEFF6, alpha));
            smallF.drawString(val, rx + rw - vw - 10.0F, ry + 7.5F, mulAlpha(0xFFFFDF9E, alpha));
        } else if (s instanceof MashSetting.ModeSetting) {
            MashSetting.ModeSetting ms = (MashSetting.ModeSetting) s;
            float pillW = Math.min(126.0F, rw * 0.46F);
            float pillX = rx + rw - pillW - 8.0F;
            smallF.drawString(smallF.trimToWidth(s.getName(), rw - pillW - 20.0F), rx + 10.0F, ry + 10.0F, mulAlpha(0xFFECEFF6, alpha));
            smallF.drawCentered(smallF.trimToWidth(ms.getMode(), pillW - 10.0F), pillX + pillW * 0.5F, ry + 10.0F, mulAlpha(0xFFFFDF9E, alpha), false);
        } else if (s instanceof MashSetting.KeybindSetting) {
            MashSetting.KeybindSetting ks = (MashSetting.KeybindSetting) s;
            boolean listening = listeningKeybind == ks;
            float pillW = 86.0F;
            float pillX = rx + rw - pillW - 8.0F;
            smallF.drawString(smallF.trimToWidth(s.getName(), rw - pillW - 20.0F), rx + 10.0F, ry + 10.0F, mulAlpha(0xFFECEFF6, alpha));
            String txt = listening ? (PureFPSConfig.isEnglish() ? "Press key..." : "Touche...") : ks.getDisplayString();
            smallF.drawCentered(txt, pillX + pillW * 0.5F, ry + 10.0F, mulAlpha(listening ? 0xFFFFF4DC : 0xFFECEFF6, alpha), false);
        } else if (s instanceof MashSetting.ColorSetting) {
            MashSetting.ColorSetting cs = (MashSetting.ColorSetting) s;
            smallF.drawString(smallF.trimToWidth(s.getName(), rw - 98.0F), rx + 10.0F, ry + 10.0F, mulAlpha(0xFFECEFF6, alpha));
            smallF.drawCentered("Chroma", rx + rw - 60.0F, ry + 10.0F, mulAlpha(cs.isChroma() ? 0xFFFFDF9E : 0xFF8E96AA, alpha), false);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (listeningKeybind != null && mouseButton >= 2) {
            listeningKeybind.setKeyCode(-100 + mouseButton);
            listeningKeybind = null;
            playClick();
            return;
        }
        listeningKeybind = null;

        float dw = NanoUiRenderer.desktopWidth(this.mc);
        float dh = NanoUiRenderer.desktopHeight(this.mc);
        float dmx = NanoUiRenderer.desktopEventMouseX(this.mc);
        float dmy = NanoUiRenderer.desktopEventMouseY(this.mc);
        computeDesktopLayout(dw, dh);

        if (hudCanvasMode) {
            float bw = 428.0F;
            float bx = (dw - bw) * 0.5F;
            float by = 14.0F;
            if (mouseButton == 0 && inside(dmx, dmy, bx + 5.0F, by + 4.0F, 84.0F, 26.0F)) {
                hudCanvasMode = false;
                playClick();
                return;
            }
            if (mouseButton == 0 && inside(dmx, dmy, bx + bw - 128.0F, by + 4.0F, 123.0F, 26.0F)) {
                magneticSnapEnabled = !magneticSnapEnabled;
                MashAudioEngine.playToggle(magneticSnapEnabled);
                return;
            }
            if (handleHudCanvasClick(mouseX, mouseY, mouseButton)) {
                return;
            }
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        boolean overStudio = !Keyboard.isKeyDown(Keyboard.KEY_TAB)
                && inside(dmx, dmy, panelX, panelY, panelW, panelH);

        if (!overStudio) {
            if (handleHudCanvasClick(mouseX, mouseY, mouseButton)) {
                return;
            }
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        float px = panelX;
        float py = panelY;
        float pw = panelW;
        float ph = panelH;
        float subY = py + TOP_BAR_H;

        float closeBtnX = px + pw - 38.0F;
        if (mouseButton == 0 && inside(dmx, dmy, closeBtnX, py + 11.0F, 24.0F, 24.0F)) {
            playClick();
            this.mc.displayGuiScreen(this.parentScreen);
            return;
        }

        SmoothFontRenderer smallF = SmoothFontRenderer.uiSmall();
        NavTab[] tabs = NavTab.values();
        float[] tabWidths = new float[tabs.length];
        float tabsTotalW = 8.0F;
        for (int i = 0; i < tabs.length; i++) {
            tabWidths[i] = computeTabWidth(tabs[i], smallF);
            tabsTotalW += tabWidths[i];
        }
        float tabsPillX = closeBtnX - tabsTotalW - 10.0F;
        float curTabX = tabsPillX + 4.0F;
        for (int i = 0; i < tabs.length; i++) {
            float tw = tabWidths[i];
            if (mouseButton == 0 && inside(dmx, dmy, curTabX, py + 11.0F, tw, 24.0F)) {
                this.currentTab = tabs[i];
                rebuildFilteredList();
                playClick();
                return;
            }
            curTabX += tw;
        }

        float hudBtnW = 106.0F;
        float hudBtnX = px + pw - hudBtnW - 18.0F;
        float hudBtnY = subY + 8.0F;
        if (mouseButton == 0 && inside(dmx, dmy, hudBtnX, hudBtnY, hudBtnW, 30.0F)) {
            hudCanvasMode = true;
            searchFocused = false;
            playClick();
            return;
        }

        float searchW = 276.0F;
        float searchX = hudBtnX - searchW - 8.0F;
        float searchY = subY + 8.0F;
        if (!searchQuery.isEmpty() && mouseButton == 0 && inside(dmx, dmy, searchX + searchW - 24.0F, searchY + 5.0F, 20.0F, 20.0F)) {
            this.searchQuery = "";
            this.searchFocused = true;
            rebuildFilteredList();
            playClick();
            return;
        }
        this.searchFocused = inside(dmx, dmy, searchX, searchY, searchW, 30.0F);
        if (this.searchFocused && mouseButton == 1) {
            this.searchQuery = "";
            rebuildFilteredList();
            return;
        }

        float footY = py + ph - FOOTER_H;
        PureFPSProfile[] profs = {PureFPSProfile.FPS, PureFPSProfile.BALANCED, PureFPSProfile.QUALITY};
        float fpW = 68.0F;
        for (int i = 0; i < 3; i++) {
            float bx = px + 21.0F + i * (fpW + 2.0F);
            if (mouseButton == 0 && inside(dmx, dmy, bx, footY + 7.5F, fpW, 21.0F)) {
                PureFPSConfig.applyProfile(profs[i], true);
                MashModules.syncFromPureConfig();
                playClick();
                return;
            }
        }
        float snapPillX = px + 240.0F;
        if (mouseButton == 0 && inside(dmx, dmy, snapPillX, footY + 5.0F, 126.0F, 26.0F)) {
            magneticSnapEnabled = !magneticSnapEnabled;
            MashAudioEngine.playToggle(magneticSnapEnabled);
            return;
        }
        float langPillX = snapPillX + 132.0F;
        if (mouseButton == 0 && inside(dmx, dmy, langPillX, footY + 5.0F, 72.0F, 26.0F)) {
            PureFPSConfig.language = PureFPSConfig.isEnglish() ? "fr" : "en";
            PureFPSConfig.markDirty();
            PureFPSConfig.saveIfDirty();
            MashAudioEngine.playToggle(PureFPSConfig.isEnglish());
            return;
        }

        if (inspectedModule != null && dmx >= getInspectorX() && dmy >= subY + SUB_HEADER_H && dmy <= py + ph - FOOTER_H) {
            handleInspectorClick(dmx, dmy, mouseButton);
            return;
        }

        float inspectorW = inspectedModule != null ? INSPECTOR_W : 0.0F;
        float gx = px + 18.0F;
        float gy = subY + SUB_HEADER_H + 8.0F;
        float gw = pw - inspectorW - (inspectedModule != null ? 28.0F : 36.0F);
        float gh = ph - TOP_BAR_H - SUB_HEADER_H - FOOTER_H - 14.0F;

        if (inside(dmx, dmy, gx, gy, gw, gh)) {
            int cols = (inspectedModule != null) ? 2 : 3;
            float cardW = (gw - CARD_GAP_X * (cols - 1)) / (float) cols;
            float rowStride = CARD_H + CARD_GAP_Y;

            for (int i = 0; i < filteredModules.size(); i++) {
                int col = i % cols;
                int row = i / cols;
                float cx = gx + col * (cardW + CARD_GAP_X);
                float cy = gy + row * rowStride - gridScroll;
                if (inside(dmx, dmy, cx, cy, cardW, CARD_H)) {
                    MashModule m = filteredModules.get(i);
                    if (mouseButton == 0 && inside(dmx, dmy, cx + cardW - 86.0F, cy + 13.0F, 20.0F, 24.0F)) {
                        m.toggleFavorite();
                        MashAudioEngine.playToggle(m.isFavorite());
                        return;
                    }
                    boolean gearClick = mouseButton == 0 && inside(dmx, dmy, cx + cardW - 64.0F, cy + 13.0F, 25.0F, 24.0F);
                    if (mouseButton == 1 || gearClick) {
                        inspectedModule = (inspectedModule == m && gearClick) ? null : m;
                        inspectorScroll = 0.0F;
                        inspectorScrollTarget = 0.0F;
                        expandedColorSetting = null;
                        if (m.isHudWidget() && m.isEnabled()) {
                            selectedHud = m;
                        }
                        playClick();
                        return;
                    }
                    if (mouseButton == 0) {
                        m.toggle();
                        if (m.isHudWidget()) {
                            selectedHud = m.isEnabled() ? m : null;
                        }
                        MashAudioEngine.playToggle(m.isEnabled());
                        return;
                    }
                }
            }
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    private boolean handleHudCanvasClick(int mouseX, int mouseY, int mouseButton) {
        MashModule hitHud = hitTestHudOutsideOrCanvas(mouseX, mouseY);
        if (hitHud != null) {
            selectedHud = hitHud;
            if (mouseButton == 1) {
                hudCanvasMode = false;
                inspectedModule = hitHud;
                inspectorScroll = 0.0F;
                inspectorScrollTarget = 0.0F;
                playClick();
                return true;
            }
            if (mouseButton == 0) {
                float hx = hitHud.getHudX() + hitHud.getScaledHudW();
                float hy = hitHud.getHudY() + hitHud.getScaledHudH();
                if (Math.abs(mouseX - hx) <= 7.0F && Math.abs(mouseY - hy) <= 7.0F) {
                    resizingHud = true;
                    resizeStartMouseX = mouseX;
                    resizeStartScale = hitHud.getScale();
                } else {
                    draggingHud = true;
                    dragOffsetX = mouseX - hitHud.getHudX();
                    dragOffsetY = mouseY - hitHud.getHudY();
                    dragOriginX = hitHud.getHudX();
                    dragOriginY = hitHud.getHudY();
                }
                playClick();
                return true;
            }
        } else if (mouseButton == 0) {
            selectedHud = null;
        }
        return false;
    }

    private void handleInspectorClick(float dmx, float dmy, int btn) {
        MashModule mod = inspectedModule;
        if (mod == null) {
            return;
        }
        float ix = getInspectorX();
        float iy = panelY + TOP_BAR_H + SUB_HEADER_H + 4.0F;
        float iw = INSPECTOR_W - 6.0F;
        float ih = panelH - TOP_BAR_H - SUB_HEADER_H - FOOTER_H - 8.0F;

        if (btn == 0 && inside(dmx, dmy, ix + iw - 66.0F, iy + 9.0F, 32.0F, 20.0F)) {
            mod.toggle();
            MashAudioEngine.playToggle(mod.isEnabled());
            return;
        }
        if (btn == 0 && inside(dmx, dmy, ix + iw - 29.0F, iy + 7.0F, 24.0F, 24.0F)) {
            inspectedModule = null;
            expandedColorSetting = null;
            playClick();
            return;
        }
        float resetY = iy + ih - 31.0F;
        if (btn == 0 && inside(dmx, dmy, ix + 12.0F, resetY, iw - 18.0F, 25.0F)) {
            mod.resetDefaults();
            playClick();
            return;
        }

        float headerBlockH = (mod == MashModules.MOD_CROSSHAIR) ? 108.0F : 66.0F;
        float listY = iy + headerBlockH;
        float listH = ih - headerBlockH - 38.0F;
        if (!inside(dmx, dmy, ix, listY, iw, listH)) {
            return;
        }

        List<MashSetting> settings = mod.getSettings();
        float curY = listY + 5.0F - inspectorScroll;
        float rx = ix + 12.0F;
        float rw = iw - 18.0F;

        for (int i = 0; i < settings.size(); i++) {
            MashSetting s = settings.get(i);
            float rowH = getSettingRowHeight(s);
            if (inside(dmx, dmy, rx, curY, rw, rowH - 5.0F)) {
                if (s instanceof MashSetting.BooleanSetting && btn == 0) {
                    MashSetting.BooleanSetting bs = (MashSetting.BooleanSetting) s;
                    bs.toggle();
                    MashAudioEngine.playToggle(bs.get());
                    return;
                }
                if (s instanceof MashSetting.NumberSetting && btn == 0) {
                    MashSetting.NumberSetting ns = (MashSetting.NumberSetting) s;
                    activeDraggingSlider = ns;
                    activeSliderX = rx + 10.0F;
                    activeSliderW = rw - 20.0F;
                    ns.setFraction((dmx - activeSliderX) / Math.max(1.0F, activeSliderW));
                    return;
                }
                if (s instanceof MashSetting.ModeSetting) {
                    MashSetting.ModeSetting ms = (MashSetting.ModeSetting) s;
                    if (btn == 0) ms.cycleNext();
                    else if (btn == 1) ms.cyclePrev();
                    playClick();
                    return;
                }
                if (s instanceof MashSetting.KeybindSetting && btn == 0) {
                    listeningKeybind = (MashSetting.KeybindSetting) s;
                    playClick();
                    return;
                }
                if (s instanceof MashSetting.ColorSetting) {
                    MashSetting.ColorSetting cs = (MashSetting.ColorSetting) s;
                    if (btn == 0 && inside(dmx, dmy, rx + rw - 86.0F, curY + 4.0F, 52.0F, 24.0F)) {
                        cs.setChroma(!cs.isChroma());
                        playClick();
                        return;
                    }
                    if (inside(dmx, dmy, rx, curY, rw, 31.0F)) {
                        expandedColorSetting = (expandedColorSetting == cs) ? null : cs;
                        playClick();
                        return;
                    }
                    if (expandedColorSetting == cs && btn == 0) {
                        activeColorBarX = rx + 10.0F;
                        activeColorBarW = rw - 20.0F;
                        if (dmy >= curY + 34.0F && dmy < curY + 53.0F) activeColorDragChannel = 0;
                        else if (dmy >= curY + 53.0F && dmy < curY + 72.0F) activeColorDragChannel = 1;
                        else if (dmy >= curY + 72.0F && dmy < curY + 91.0F) activeColorDragChannel = 2;
                        else if (dmy >= curY + 91.0F) activeColorDragChannel = 3;
                        return;
                    }
                }
            }
            curY += rowH;
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (draggingHud || resizingHud || activeDraggingSlider != null || activeColorDragChannel >= 0) {
            MashModules.saveAsyncIfDirty();
        }
        draggingHud = false;
        resizingHud = false;
        activeSnap = null;
        activeDraggingSlider = null;
        activeColorDragChannel = -1;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (listeningKeybind != null) {
            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_BACK || keyCode == Keyboard.KEY_DELETE) {
                listeningKeybind.setKeyCode(0);
            } else {
                listeningKeybind.setKeyCode(keyCode);
            }
            listeningKeybind = null;
            playClick();
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (hudCanvasMode) {
                hudCanvasMode = false;
                playClick();
                return;
            }
            this.mc.displayGuiScreen(this.parentScreen);
            return;
        }
        if (keyCode == Keyboard.KEY_RSHIFT) {
            if (!Keyboard.isRepeatEvent()) {
                this.mc.displayGuiScreen(this.parentScreen);
            }
            return;
        }

        if (!searchFocused && selectedHud != null && selectedHud.isEnabled()) {
            float step = isShiftKeyDown() ? 5.0F : 1.0F;
            if (keyCode == Keyboard.KEY_LEFT) {
                selectedHud.setHudPos(Math.max(1.0F, selectedHud.getHudX() - step), selectedHud.getHudY());
                return;
            }
            if (keyCode == Keyboard.KEY_RIGHT) {
                selectedHud.setHudPos(Math.min(this.width - selectedHud.getScaledHudW() - 1.0F, selectedHud.getHudX() + step), selectedHud.getHudY());
                return;
            }
            if (keyCode == Keyboard.KEY_UP) {
                selectedHud.setHudPos(selectedHud.getHudX(), Math.max(1.0F, selectedHud.getHudY() - step));
                return;
            }
            if (keyCode == Keyboard.KEY_DOWN) {
                selectedHud.setHudPos(selectedHud.getHudX(), Math.min(this.height - selectedHud.getScaledHudH() - 1.0F, selectedHud.getHudY() + step));
                return;
            }
        }

        if (!hudCanvasMode) {
            if (keyCode == Keyboard.KEY_BACK) {
                if ((searchFocused || !Keyboard.isRepeatEvent()) && !searchQuery.isEmpty()) {
                    searchFocused = true;
                    searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                    rebuildFilteredList();
                }
                return;
            }
            // Empêche les touches de déplacement maintenues (ZQSD) à l'ouverture du menu RSHIFT d'écrire dans la recherche
            if (!searchFocused && (Keyboard.isRepeatEvent() || (System.currentTimeMillis() - this.openedAtMs) < 120L)) {
                return;
            }
            if (ChatAllowedCharacters.isAllowedCharacter(typedChar) && typedChar >= 32) {
                searchFocused = true;
                if (searchQuery.length() < 42) {
                    searchQuery += typedChar;
                    rebuildFilteredList();
                }
                return;
            }
        }

        super.keyTyped(typedChar, keyCode);
    }

    private MashModule hitTestHudOutsideOrCanvas(int mx, int my) {
        List<MashModule> hudMods = MashModules.getHudModules();
        for (int i = hudMods.size() - 1; i >= 0; i--) {
            MashModule m = hudMods.get(i);
            if (!m.isEnabled()) {
                continue;
            }
            float x = m.getHudX();
            float y = m.getHudY();
            float w = m.getScaledHudW();
            float h = m.getScaledHudH();
            if (mx >= x - 2.0F && mx <= x + w + 5.0F && my >= y - 2.0F && my <= y + h + 5.0F) {
                return m;
            }
        }
        return null;
    }

    private float getInspectorX() {
        return panelX + panelW - INSPECTOR_W;
    }

    private void playClick() {
        MashAudioEngine.playClick();
    }

    private static int mulAlpha(int argb, float factor) {
        if (factor >= 0.995F) {
            return argb;
        }
        int a = (argb >>> 24) & 0xFF;
        int na = Math.max(0, Math.min(255, Math.round(a * factor)));
        return (na << 24) | (argb & 0x00FFFFFF);
    }

    private static boolean inside(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}

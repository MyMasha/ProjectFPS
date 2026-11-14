package dev.purefps.gui;

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
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Studio Client Unifié MashClient 4.0 (touche RSHIFT) :
 * - Design "Singularity" professionnel en verre fumé obsidienne & or chaud (#F5B85C), cohérent avec le Launcher.
 * - 200 Modules (HUD, PvP, Visuel, Hypixel, Monde, Moteur FPS) filtrables en temps réel.
 * - Clic Gauche sur une carte = Active / Désactive • Clic Droit (ou ⚙) = Ouvre l'Inspecteur complet de Sous-Paramètres !
 * - Éditeur HUD intégré directement à l'écran (aucun sous-menu séparé en bas à gauche) :
 *   • Cliquez sur n'importe quel élément HUD à l'écran pour le sélectionner : il devient semi-transparent (~48 %)
 *     et le panneau central s'estompe automatiquement pendant le déplacement pour voir au travers !
 *   • Lignes magnétiques d'alignement et de collage bord-à-bord ("Glue & Axis Snap") + maintien Shift pour verrouiller un axe X/Y.
 */
public class GuiMashClientMenu extends GuiScreen {

    private final GuiScreen parentScreen;

    // Filtres & Navigation
    private enum NavTab {
        ALL("Tous les modules", null),
        ACTIVE("Modules Actifs", null),
        FAVORITES("Favoris \u2605", null),
        HUD("HUD & Widgets", MashCategory.HUD),
        PVP("PvP & Caméra", MashCategory.PVP),
        VISUAL("Visuel & Rendu", MashCategory.VISUAL),
        HYPIXEL("Hypixel & Jeux", MashCategory.HYPIXEL),
        WORLD("Monde & Confort", MashCategory.WORLD),
        ENGINE("Moteur FPS", MashCategory.ENGINE);

        final String label;
        final MashCategory category;

        NavTab(String label, MashCategory category) {
            this.label = label;
            this.category = category;
        }
    }

    private static NavTab persistedTab = NavTab.ALL;
    private static String persistedSearch = "";

    private NavTab currentTab = persistedTab;
    private String searchQuery = persistedSearch;
    private boolean searchFocused = false;
    private List<MashModule> filteredModules = new ArrayList<>();

    // Défilement fluide
    private float gridScroll = 0.0F;
    private float gridScrollTarget = 0.0F;
    private float gridMaxScroll = 0.0F;

    private float inspectorScroll = 0.0F;
    private float inspectorScrollTarget = 0.0F;
    private float inspectorMaxScroll = 0.0F;

    // Inspecteur de sous-paramètres (clic droit sur n'importe quel module)
    private MashModule inspectedModule = null;
    private MashSetting.NumberSetting activeDraggingSlider = null;
    private float activeSliderX = 0.0F;
    private float activeSliderW = 100.0F;

    private MashSetting.ColorSetting expandedColorSetting = null;
    private int activeColorDragChannel = -1; // 0=Hue, 1=Sat, 2=Bri, 3=Alpha
    private float activeColorBarX = 0.0F;
    private float activeColorBarW = 100.0F;

    private MashSetting.KeybindSetting listeningKeybind = null;

    // Sélection & Aimantation Magnétique HUD en direct
    private boolean magneticSnapEnabled = true;
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

    // Géométrie calculée du panneau Studio
    private float panelX;
    private float panelY;
    private float panelW;
    private float panelH;
    private float panelAlpha = 1.0F;

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

    private void rebuildFilteredList() {
        boolean onlyEn = this.currentTab == NavTab.ACTIVE;
        boolean onlyFav = this.currentTab == NavTab.FAVORITES;
        this.filteredModules = MashModules.filter(this.currentTab.category, onlyEn, onlyFav, this.searchQuery);
        this.gridScroll = 0.0F;
        this.gridScrollTarget = 0.0F;
    }

    private void computePanelLayout() {
        this.panelW = Math.min(660.0F, Math.max(430.0F, this.width - 56.0F));
        this.panelH = Math.min(390.0F, Math.max(256.0F, this.height - 54.0F));
        this.panelX = (this.width - this.panelW) * 0.5F;
        this.panelY = (this.height - this.panelH) * 0.5F;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && !draggingHud && !resizingHud) {
            int mx = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int my = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            float delta = (wheel > 0 ? -34.0F : 34.0F);

            if (inspectedModule != null && mx >= getInspectorX() && mx <= panelX + panelW && my >= panelY + 42.0F && my <= panelY + panelH) {
                inspectorScrollTarget = clamp(inspectorScrollTarget + delta, 0.0F, inspectorMaxScroll);
            } else if (mx >= panelX + 144.0F && mx <= panelX + panelW && my >= panelY + 42.0F && my <= panelY + panelH) {
                gridScrollTarget = clamp(gridScrollTarget + delta, 0.0F, gridMaxScroll);
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        computePanelLayout();
        ScaledResolution sr = new ScaledResolution(this.mc);
        NanoUiRenderer.setScaleFactor(sr.getScaleFactor());

        // Mise à jour en direct du glisser-déposer HUD avec aimantation et collage magnétique
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

        // Mise à jour continue des curseurs de sous-paramètres
        if (activeDraggingSlider != null) {
            float frac = (mouseX - activeSliderX) / Math.max(1.0F, activeSliderW);
            activeDraggingSlider.setFraction(frac);
        } else if (activeColorDragChannel >= 0 && expandedColorSetting != null) {
            float frac = clamp((mouseX - activeColorBarX) / Math.max(1.0F, activeColorBarW), 0.0F, 1.0F);
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

        // Lissage des barres de défilement et de la transparence du Studio
        gridScroll += (gridScrollTarget - gridScroll) * 0.32F;
        inspectorScroll += (inspectorScrollTarget - inspectorScroll) * 0.32F;

        boolean canvasPeek = draggingHud || resizingHud || Keyboard.isKeyDown(Keyboard.KEY_TAB);
        float targetAlpha = canvasPeek ? 0.11F : 1.0F;
        panelAlpha += (targetAlpha - panelAlpha) * 0.28F;

        // 1. Léger voile d'ambiance en arrière-plan
        NanoUiRenderer.beginBatch();
        int backdropAlpha = canvasPeek ? 0x2604060B : 0x6E04060B;
        NanoUiRenderer.drawRoundedRect(-2.0F, -2.0F, this.width + 4.0F, this.height + 4.0F, 0.0F, backdropAlpha, 0, 0.0F);
        NanoUiRenderer.endBatch();

        // 2. Rendu en direct de TOUS les éléments HUD actifs (l'élément sélectionné est rendu translucide !)
        String selId = selectedHud != null ? selectedHud.getId() : null;
        MashHudRenderer.renderOverlay(this.mc, sr, true, selId, draggingHud || resizingHud);

        // 3. Cadres HUD interactifs, poignées d'échelle et LIGNES MAGNÉTIQUES d'alignement/collage
        renderHudInteractiveSelectionAndSnapGuides(mouseX, mouseY);

        // 4. Fenêtre principale Singularity Studio (s'estompe automatiquement quand on déplace un élément HUD !)
        if (panelAlpha > 0.03F) {
            renderStudioWindow(sr, mouseX, mouseY, panelAlpha);
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        super.drawScreen(mouseX, mouseY, partialTicks);
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

    private void renderHudInteractiveSelectionAndSnapGuides(int mouseX, int mouseY) {
        List<MashModule> hudMods = MashModules.getHudModules();
        MashModule hoveredHud = (!draggingHud && !resizingHud) ? hitTestHudOutsideOrCanvas(mouseX, mouseY) : null;

        NanoUiRenderer.beginBatch();

        // Lignes magnétiques d'alignement & de collage inter-éléments
        if ((draggingHud || resizingHud) && activeSnap != null) {
            if (activeSnap.hasVerticalGuide) {
                int glow = activeSnap.verticalIsGlueOrCenter ? 0x3DF5B85C : 0x3538BDF8;
                int core = activeSnap.verticalIsGlueOrCenter ? 0xF0F5B85C : 0xE638BDF8;
                NanoUiRenderer.drawLine(activeSnap.verticalLineX, activeSnap.vMinY, activeSnap.verticalLineX, activeSnap.vMaxY, 3.2F, glow);
                NanoUiRenderer.drawLine(activeSnap.verticalLineX, activeSnap.vMinY, activeSnap.verticalLineX, activeSnap.vMaxY, 1.0F, core);
            }
            if (activeSnap.hasHorizontalGuide) {
                int glow = activeSnap.horizontalIsGlueOrCenter ? 0x3DF5B85C : 0x3538BDF8;
                int core = activeSnap.horizontalIsGlueOrCenter ? 0xF0F5B85C : 0xE638BDF8;
                NanoUiRenderer.drawLine(activeSnap.hMinX, activeSnap.horizontalLineY, activeSnap.hMaxX, activeSnap.horizontalLineY, 3.2F, glow);
                NanoUiRenderer.drawLine(activeSnap.hMinX, activeSnap.horizontalLineY, activeSnap.hMaxX, activeSnap.horizontalLineY, 1.0F, core);
            }
        }

        // Contour subtil des autres widgets pendant un déplacement pour visualiser toutes les cibles de collage
        if (draggingHud || Keyboard.isKeyDown(Keyboard.KEY_TAB)) {
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
                        0x4494A3B8,
                        1.0F
                );
            }
        }

        // Survol d'un élément HUD à l'écran
        if (hoveredHud != null && hoveredHud != selectedHud) {
            NanoUiRenderer.drawRoundedRect(
                    hoveredHud.getHudX() - 1.5F,
                    hoveredHud.getHudY() - 1.5F,
                    hoveredHud.getScaledHudW() + 3.0F,
                    hoveredHud.getScaledHudH() + 3.0F,
                    5.5F,
                    0x14F5B85C,
                    0xAA38BDF8,
                    1.2F
            );
        }

        // Élément HUD sélectionné (semi-transparent + cadre magnétique doré + poignée d'échelle)
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
                    0x12F5B85C,
                    0xEEF5B85C,
                    1.4F
            );
            // Poignée coin bas-droit (redimensionnement échelle)
            NanoUiRenderer.drawCircle(sx + sw + 1.5F, sy + sh + 1.5F, 3.3F, 0xFFF5B85C);
            NanoUiRenderer.drawCircle(sx + sw + 1.5F, sy + sh + 1.5F, 1.4F, 0xFF0A0B0E);

            coordBadge = selectedHud.getName() + "  \u2022  X:" + Math.round(sx) + " Y:" + Math.round(sy)
                    + String.format(Locale.US, "  (%.2fx)", selectedHud.getScale());
            badgeW = SmoothFontRenderer.small().getStringWidth(coordBadge) + 12.0F;
            badgeX = clamp(sx + sw * 0.5F - badgeW * 0.5F, 4.0F, this.width - badgeW - 4.0F);
            badgeY = (sy > 20.0F) ? (sy - 16.5F) : (sy + sh + 5.0F);
            NanoUiRenderer.drawRoundedRect(badgeX, badgeY, badgeW, 13.5F, 4.5F, 0xE6090C14, 0x88F5B85C, 1.0F);
        }

        // Bandeau d'aide supérieur discret
        String topHint = draggingHud
                ? "Glissez pour aimanter & coller aux autres éléments  \u2022  Maintenez SHIFT pour verrouiller l'axe X/Y"
                : "Cliquez sur n'importe quel élément HUD pour le déplacer en transparence  \u2022  Clic droit = Sous-paramètres";
        float hintW = SmoothFontRenderer.small().getStringWidth(topHint) + 18.0F;
        float hintX = (this.width - hintW) * 0.5F;
        float hintY = Math.max(4.0F, panelY - 19.0F);
        if (panelY > 20.0F || draggingHud) {
            NanoUiRenderer.drawRoundedRect(hintX, draggingHud ? 6.0F : hintY, hintW, 14.5F, 7.0F, 0xCC090C14, 0x38F5B85C, 1.0F);
        }

        NanoUiRenderer.endBatch();

        SmoothFontRenderer smallF = SmoothFontRenderer.small();
        smallF.beginBatch();
        if (coordBadge != null) {
            smallF.drawCentered(coordBadge, badgeX + badgeW * 0.5F, badgeY + 3.0F, 0xFFF7CE86, false);
        }
        if (panelY > 20.0F || draggingHud) {
            smallF.drawCentered(topHint, this.width * 0.5F, (draggingHud ? 6.0F : hintY) + 3.5F, 0xFFD6DCE8, false);
        }
        smallF.endBatch();
    }

    private void renderStudioWindow(ScaledResolution sr, int mouseX, int mouseY, float alpha) {
        float px = panelX;
        float py = panelY;
        float pw = panelW;
        float ph = panelH;
        float sidebarW = 138.0F;
        float headerH = 38.0F;
        boolean hasInspector = inspectedModule != null;
        float inspectorW = hasInspector ? Math.min(242.0F, pw * 0.40F) : 0.0F;

        SmoothFontRenderer titleF = SmoothFontRenderer.subtitle();
        SmoothFontRenderer bodyF = SmoothFontRenderer.body();
        SmoothFontRenderer smallF = SmoothFontRenderer.small();

        // =========================================================================
        // 1. CADRE PRINCIPAL, BARRE LATÉRALE & EN-TÊTE (1 passe SDF)
        // =========================================================================
        NanoUiRenderer.beginBatch();

        NanoUiRenderer.drawShadow(px, py, pw, ph, 12.0F, 22.0F, mulAlpha(0xBA000000, alpha));
        NanoUiRenderer.drawRoundedGradient(
                px, py, pw, ph, 11.0F,
                mulAlpha(0xF20A0D15, alpha),
                mulAlpha(0xF607090F, alpha),
                mulAlpha(0x2AFFFFFF, alpha),
                1.0F
        );

        // Colonne latérale gauche
        NanoUiRenderer.drawRoundedRect(
                px + 1.0F, py + 1.0F, sidebarW, ph - 2.0F, 10.0F,
                mulAlpha(0x8805070C, alpha), 0, 0.0F
        );
        NanoUiRenderer.drawLine(px + sidebarW, py + 8.0F, px + sidebarW, py + ph - 8.0F, 0.8F, mulAlpha(0x18FFFFFF, alpha));
        NanoUiRenderer.drawLine(px + sidebarW, py + headerH, px + pw - 8.0F, py + headerH, 0.8F, mulAlpha(0x18FFFFFF, alpha));

        // Emblème doré "M" dans la barre latérale
        NanoUiRenderer.drawRoundedGradient(
                px + 11.0F, py + 10.0F, 20.0F, 20.0F, 5.5F,
                mulAlpha(0xFFF5C469, alpha), mulAlpha(0xFFC88022, alpha),
                mulAlpha(0x88FFE7B3, alpha), 1.0F
        );

        // Sélecteur rapide de profils FPS (FPS / Équilibré / Qualité)
        PureFPSProfile activeProf = PureFPSConfig.activeProfile;
        PureFPSProfile[] profs = {PureFPSProfile.FPS, PureFPSProfile.BALANCED, PureFPSProfile.QUALITY};
        float profW = (sidebarW - 18.0F) / 3.0F;
        for (int i = 0; i < 3; i++) {
            float bx = px + 8.0F + i * (profW + 1.0F);
            float by = py + 37.0F;
            boolean sel = activeProf == profs[i];
            boolean hov = alpha > 0.7F && inside(mouseX, mouseY, bx, by, profW, 16.0F);
            int bg = sel ? 0x38F5B85C : (hov ? 0xFF191D2A : 0xFF11141E);
            int brd = sel ? 0xAAEEB458 : 0x1EFFFFFF;
            NanoUiRenderer.drawRoundedRect(bx, by, profW, 16.0F, 4.5F, mulAlpha(bg, alpha), mulAlpha(brd, alpha), 1.0F);
        }

        // Onglets de catégories
        NavTab[] tabs = NavTab.values();
        float tabStartY = py + 60.0F;
        float tabH = 22.0F;
        for (int i = 0; i < tabs.length; i++) {
            float ty = tabStartY + i * 24.0F;
            boolean sel = currentTab == tabs[i];
            boolean hov = alpha > 0.7F && inside(mouseX, mouseY, px + 7.0F, ty, sidebarW - 14.0F, tabH);
            if (sel || hov) {
                int bg = sel ? 0x29F5B85C : 0x14FFFFFF;
                int brd = sel ? 0x77F5B85C : 0x18FFFFFF;
                NanoUiRenderer.drawRoundedRect(px + 7.0F, ty, sidebarW - 14.0F, tabH, 5.5F, mulAlpha(bg, alpha), mulAlpha(brd, alpha), 1.0F);
                if (sel) {
                    NanoUiRenderer.drawRoundedRect(px + 9.5F, ty + 5.0F, 2.4F, tabH - 10.0F, 1.2F, mulAlpha(0xFFF5B85C, alpha), 0, 0.0F);
                }
            }
        }

        // Boutons bas de barre latérale (Lignes Magnétiques & Borderless)
        float snapBtnY = py + ph - 48.0F;
        float brdBtnY = py + ph - 25.0F;
        NanoUiRenderer.drawRoundedRect(
                px + 7.0F, snapBtnY, sidebarW - 14.0F, 19.0F, 5.0F,
                mulAlpha(magneticSnapEnabled ? 0x2238BDF8 : 0xFF11141D, alpha),
                mulAlpha(magneticSnapEnabled ? 0x6638BDF8 : 0x1FFFFFFF, alpha),
                1.0F
        );
        NanoUiRenderer.drawRoundedRect(
                px + 7.0F, brdBtnY, sidebarW - 14.0F, 19.0F, 5.0F,
                mulAlpha(PureFPSConfig.borderlessFullscreen ? 0x2234D399 : 0xFF11141D, alpha),
                mulAlpha(PureFPSConfig.borderlessFullscreen ? 0x6634D399 : 0x1FFFFFFF, alpha),
                1.0F
        );

        // Barre de recherche en haut
        float searchX = px + sidebarW + 10.0F;
        float searchY = py + 8.0F;
        float searchW = pw - sidebarW - 122.0F;
        NanoUiRenderer.drawRoundedRect(
                searchX, searchY, searchW, 22.0F, 6.0F,
                mulAlpha(0xFF10131D, alpha),
                mulAlpha(searchFocused ? 0x99F5B85C : 0x24FFFFFF, alpha),
                1.0F
        );

        // Badge FPS direct + bouton Fermer
        float fpsPillX = searchX + searchW + 8.0F;
        NanoUiRenderer.drawRoundedRect(fpsPillX, searchY, 68.0F, 22.0F, 6.0F, mulAlpha(0xFF10141E, alpha), mulAlpha(0x22FFFFFF, alpha), 1.0F);
        NanoUiRenderer.drawCircle(fpsPillX + 9.0F, searchY + 11.0F, 2.3F, mulAlpha(0xFF34D399, alpha));

        float closeX = px + pw - 30.0F;
        boolean closeHov = alpha > 0.7F && inside(mouseX, mouseY, closeX, searchY, 22.0F, 22.0F);
        NanoUiRenderer.drawRoundedRect(
                closeX, searchY, 22.0F, 22.0F, 6.0F,
                mulAlpha(closeHov ? 0xDD3F1A22 : 0xFF141824, alpha),
                mulAlpha(closeHov ? 0x88F87171 : 0x24FFFFFF, alpha),
                1.0F
        );

        if (hasInspector) {
            float ix = getInspectorX();
            NanoUiRenderer.drawLine(ix, py + headerH, ix, py + ph - 6.0F, 0.9F, mulAlpha(0x22FFFFFF, alpha));
            NanoUiRenderer.drawRoundedRect(ix + 1.0F, py + headerH + 1.0F, inspectorW - 2.0F, ph - headerH - 2.0F, 9.0F, mulAlpha(0x6606080E, alpha), 0, 0.0F);
        }

        NanoUiRenderer.endBatch();

        // Textes fixes de la barre latérale & de l'en-tête
        bodyF.beginBatch();
        bodyF.drawCentered("M", px + 21.0F, py + 15.5F, mulAlpha(0xFF0A0B0E, alpha), false);
        bodyF.drawString("MASHCLIENT", px + 36.0F, py + 11.5F, mulAlpha(0xFFF7F4EC, alpha));
        bodyF.endBatch();

        smallF.beginBatch();
        smallF.drawString("Singularity 4.0  \u2022  200 Mods", px + 36.0F, py + 22.0F, mulAlpha(0xFFF5B85C, alpha));

        String[] profShort = {"FPS", "Équil.", "Qual."};
        for (int i = 0; i < 3; i++) {
            float bx = px + 8.0F + i * (profW + 1.0F);
            boolean sel = activeProf == profs[i];
            smallF.drawCentered(profShort[i], bx + profW * 0.5F, py + 41.0F, mulAlpha(sel ? 0xFFF7CE86 : 0xFF949CB0, alpha), false);
        }

        for (int i = 0; i < tabs.length; i++) {
            float ty = tabStartY + i * 24.0F;
            boolean sel = currentTab == tabs[i];
            smallF.drawString(tabs[i].label, px + 15.5F, ty + 7.0F, mulAlpha(sel ? 0xFFF7F4EC : 0xFF9AA3B8, alpha));
        }

        smallF.drawCentered(
                magneticSnapEnabled ? "Aimantation HUD: ON" : "Aimantation HUD: OFF",
                px + sidebarW * 0.5F, snapBtnY + 5.5F,
                mulAlpha(magneticSnapEnabled ? 0xFF7DD3FC : 0xFF8E96AA, alpha), false
        );
        smallF.drawCentered(
                PureFPSConfig.borderlessFullscreen ? "Borderless F11: ON" : "Borderless F11: OFF",
                px + sidebarW * 0.5F, brdBtnY + 5.5F,
                mulAlpha(PureFPSConfig.borderlessFullscreen ? 0xFF6EE7B7 : 0xFF8E96AA, alpha), false
        );

        String searchDisplay;
        int searchCol;
        if (searchQuery.isEmpty() && !searchFocused) {
            searchDisplay = "Rechercher parmi 200 modules (curseur, fov, zoom, cps, bedwars...)";
            searchCol = 0xFF6D768C;
        } else {
            String caret = (searchFocused && ((System.currentTimeMillis() / 450L) & 1L) == 0L) ? "|" : "";
            searchDisplay = searchQuery + caret;
            searchCol = 0xFFF2F4F8;
        }
        smallF.drawString(smallF.trimToWidth(searchDisplay, searchW - 16.0F), searchX + 8.5F, searchY + 7.0F, mulAlpha(searchCol, alpha));
        smallF.drawString(Minecraft.getDebugFPS() + " FPS", fpsPillX + 16.5F, searchY + 7.0F, mulAlpha(0xFFECEFF6, alpha));
        smallF.drawCentered("X", closeX + 11.0F, searchY + 7.0F, mulAlpha(closeHov ? 0xFFFFB4B4 : 0xFF949CB0, alpha), false);
        smallF.endBatch();

        // =========================================================================
        // 2. GRILLE DES CARTES DE MODULES (AVEC DÉCOUPE GL_SCISSOR)
        // =========================================================================
        float gridX = px + sidebarW + 8.0F;
        float gridY = py + headerH + 6.0F;
        float gridW = pw - sidebarW - inspectorW - 16.0F;
        float gridH = ph - headerH - 12.0F;

        enableScissor(sr, gridX, gridY, gridW, gridH);
        renderModuleCardsGrid(gridX, gridY, gridW, gridH, mouseX, mouseY, alpha, bodyF, smallF);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        // =========================================================================
        // 3. INSPECTEUR DE SOUS-PARAMÈTRES (CLIC DROIT SUR UN MODULE)
        // =========================================================================
        if (hasInspector) {
            float ix = getInspectorX();
            float iy = py + headerH + 4.0F;
            float iw = inspectorW - 4.0F;
            float ih = ph - headerH - 8.0F;
            enableScissor(sr, ix, iy, iw, ih);
            renderSubSettingsInspector(ix, iy, iw, ih, mouseX, mouseY, alpha, titleF, bodyF, smallF);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    private void renderModuleCardsGrid(
            float gx,
            float gy,
            float gw,
            float gh,
            int mouseX,
            int mouseY,
            float alpha,
            SmoothFontRenderer bodyF,
            SmoothFontRenderer smallF
    ) {
        int cols = (inspectedModule != null) ? 2 : (gw >= 460.0F ? 3 : 2);
        float gap = 6.0F;
        float cardW = (gw - gap * (cols - 1)) / (float) cols;
        float cardH = 48.0F;

        int total = filteredModules.size();
        int rows = (total + cols - 1) / cols;
        float contentH = rows * (cardH + gap);
        gridMaxScroll = Math.max(0.0F, contentH - gh + 4.0F);
        gridScroll = clamp(gridScroll, 0.0F, gridMaxScroll);

        boolean mouseInViewport = alpha > 0.7F && inside(mouseX, mouseY, gx, gy, gw, gh);

        NanoUiRenderer.beginBatch();
        for (int i = 0; i < total; i++) {
            int col = i % cols;
            int row = i / cols;
            float cx = gx + col * (cardW + gap);
            float cy = gy + row * (cardH + gap) - gridScroll;
            if (cy + cardH < gy - 2.0F || cy > gy + gh + 2.0F) {
                continue;
            }

            MashModule m = filteredModules.get(i);
            boolean on = m.isEnabled();
            boolean inspected = inspectedModule == m;
            boolean hov = mouseInViewport && inside(mouseX, mouseY, cx, cy, cardW, cardH);

            int topBg = inspected ? 0xFF1A1922 : (on ? (hov ? 0xFF171C28 : 0xFF131722) : (hov ? 0xFF131620 : 0xFF0E1119));
            int botBg = inspected ? 0xFF13131A : (on ? 0xFF0F131C : 0xFF0B0E15);
            int border = inspected ? 0xCCF5B85C : (on ? 0x55F5B85C : (hov ? 0x30FFFFFF : 0x18FFFFFF));

            NanoUiRenderer.drawRoundedGradient(cx, cy, cardW, cardH, 6.5F, mulAlpha(topBg, alpha), mulAlpha(botBg, alpha), mulAlpha(border, alpha), 1.0F);

            // Bouton engrenage sous-paramètres (en haut à droite à côté du switch)
            float gearX = cx + cardW - 48.0F;
            float gearY = cy + 6.5F;
            boolean gearHov = hov && inside(mouseX, mouseY, gearX, gearY, 15.0F, 13.5F);
            NanoUiRenderer.drawRoundedRect(
                    gearX, gearY, 15.0F, 13.5F, 4.0F,
                    mulAlpha(gearHov || inspected ? 0x38F5B85C : 0xFF161A26, alpha),
                    mulAlpha(gearHov || inspected ? 0x88F5B85C : 0x22FFFFFF, alpha),
                    1.0F
            );

            // Interrupteur ON/OFF
            NanoUiRenderer.drawToggleSwitch(cx + cardW - 30.0F, cy + 6.5F, on, hov);

            // Pastille catégorie
            int catDot = m.isHudWidget() ? 0xFF38BDF8 : (on ? 0xFF34D399 : 0xFF64748B);
            NanoUiRenderer.drawCircle(cx + 8.5F, cy + 40.5F, 1.8F, mulAlpha(catDot, alpha));
        }
        NanoUiRenderer.endBatch();

        bodyF.beginBatch();
        for (int i = 0; i < total; i++) {
            int col = i % cols;
            int row = i / cols;
            float cx = gx + col * (cardW + gap);
            float cy = gy + row * (cardH + gap) - gridScroll;
            if (cy + cardH < gy - 2.0F || cy > gy + gh + 2.0F) {
                continue;
            }
            MashModule m = filteredModules.get(i);
            String title = bodyF.trimToWidth(m.getName(), cardW - 58.0F);
            bodyF.drawString(title, cx + 7.5F, cy + 7.5F, mulAlpha(m.isEnabled() ? 0xFFF7F4EC : 0xFFB8C0D4, alpha));
        }
        bodyF.endBatch();

        smallF.beginBatch();
        if (total == 0) {
            smallF.drawCentered("Aucun module ne correspond à votre recherche.", gx + gw * 0.5F, gy + gh * 0.45F, mulAlpha(0xFF8E96AA, alpha), false);
        }
        for (int i = 0; i < total; i++) {
            int col = i % cols;
            int row = i / cols;
            float cx = gx + col * (cardW + gap);
            float cy = gy + row * (cardH + gap) - gridScroll;
            if (cy + cardH < gy - 2.0F || cy > gy + gh + 2.0F) {
                continue;
            }
            MashModule m = filteredModules.get(i);
            String desc = smallF.trimToWidth(m.getDescription(), cardW - 14.0F);
            smallF.drawString(desc, cx + 7.5F, cy + 21.5F, mulAlpha(0xFF7D869C, alpha));

            String meta = m.getCategory().getDisplayName() + "  \u2022  " + m.getSettings().size() + " param.";
            smallF.drawString(meta, cx + 14.0F, cy + 36.5F, mulAlpha(0xFF687288, alpha));
            smallF.drawCentered("\u2699", cx + cardW - 40.5F, cy + 9.0F, mulAlpha(inspectedModule == m ? 0xFFF7CE86 : 0xFFB8C0D4, alpha), false);

            String star = m.isFavorite() ? "\u2605" : "\u2606";
            smallF.drawString(star, cx + cardW - 12.5F, cy + 36.0F, mulAlpha(m.isFavorite() ? 0xFFF5B85C : 0xFF565E72, alpha));
        }
        smallF.endBatch();
    }

    private void renderSubSettingsInspector(
            float ix,
            float iy,
            float iw,
            float ih,
            int mouseX,
            int mouseY,
            float alpha,
            SmoothFontRenderer titleF,
            SmoothFontRenderer bodyF,
            SmoothFontRenderer smallF
    ) {
        MashModule mod = inspectedModule;
        if (mod == null) {
            return;
        }

        float headerBlockH = (mod == MashModules.MOD_CROSSHAIR) ? 74.0F : 46.0F;
        float listY = iy + headerBlockH;
        float listH = ih - headerBlockH - 26.0F;

        // En-tête fixe de l'inspecteur
        NanoUiRenderer.beginBatch();
        NanoUiRenderer.drawToggleSwitch(ix + iw - 48.0F, iy + 6.0F, mod.isEnabled(), inside(mouseX, mouseY, ix + iw - 48.0F, iy + 6.0F, 26.0F, 14.0F));
        NanoUiRenderer.drawRoundedRect(ix + iw - 18.0F, iy + 6.0F, 14.0F, 13.5F, 4.0F, mulAlpha(0xFF181C28, alpha), mulAlpha(0x28FFFFFF, alpha), 1.0F);

        // Aperçu temps-réel si le module inspecté est le Curseur Personnalisé !
        if (mod == MashModules.MOD_CROSSHAIR) {
            float prevX = ix + 8.0F;
            float prevY = iy + 40.0F;
            float prevW = iw - 14.0F;
            float prevH = 28.0F;
            NanoUiRenderer.drawRoundedRect(prevX, prevY, prevW, prevH, 5.5F, mulAlpha(0xFF111622, alpha), mulAlpha(0x28FFFFFF, alpha), 1.0F);
            drawMiniCrosshairPreview(prevX + prevW * 0.5F, prevY + prevH * 0.5F, alpha);
        }

        NanoUiRenderer.drawLine(ix + 8.0F, listY - 2.0F, ix + iw - 8.0F, listY - 2.0F, 0.8F, mulAlpha(0x1EFFFFFF, alpha));
        NanoUiRenderer.endBatch();

        bodyF.beginBatch();
        bodyF.drawString(bodyF.trimToWidth(mod.getName(), iw - 62.0F), ix + 8.0F, iy + 6.5F, mulAlpha(0xFFF7CE86, alpha));
        bodyF.endBatch();

        smallF.beginBatch();
        smallF.drawString(smallF.trimToWidth(mod.getDescription(), iw - 14.0F), ix + 8.0F, iy + 20.5F, mulAlpha(0xFF8E96AA, alpha));
        smallF.drawCentered("x", ix + iw - 11.0F, iy + 8.5F, mulAlpha(0xFFB8C0D4, alpha), false);
        smallF.endBatch();

        // Liste déroulante des sous-paramètres du module
        ScaledResolution sr = new ScaledResolution(this.mc);
        enableScissor(sr, ix, listY, iw, listH);

        List<MashSetting> settings = mod.getSettings();
        float curY = listY + 4.0F - inspectorScroll;
        float totalH = 8.0F;

        NanoUiRenderer.beginBatch();
        for (int i = 0; i < settings.size(); i++) {
            MashSetting s = settings.get(i);
            float rowH = getSettingRowHeight(s);
            if (curY + rowH >= listY - 4.0F && curY <= listY + listH + 4.0F) {
                NanoUiRenderer.drawRoundedRect(ix + 7.0F, curY, iw - 12.0F, rowH - 3.0F, 5.5F, mulAlpha(0xFF0F131E, alpha), mulAlpha(0x1AFFFFFF, alpha), 1.0F);
                drawSettingShapes(s, ix + 7.0F, curY, iw - 12.0F, mouseX, mouseY, alpha);
            }
            curY += rowH;
            totalH += rowH;
        }
        NanoUiRenderer.endBatch();

        inspectorMaxScroll = Math.max(0.0F, totalH - listH);
        inspectorScroll = clamp(inspectorScroll, 0.0F, inspectorMaxScroll);

        smallF.beginBatch();
        curY = listY + 4.0F - inspectorScroll;
        for (int i = 0; i < settings.size(); i++) {
            MashSetting s = settings.get(i);
            float rowH = getSettingRowHeight(s);
            if (curY + rowH >= listY - 4.0F && curY <= listY + listH + 4.0F) {
                drawSettingLabels(smallF, s, ix + 7.0F, curY, iw - 12.0F, alpha);
            }
            curY += rowH;
        }
        smallF.endBatch();

        // Rétablit la découpe de l'inspecteur complet pour le bouton Réinitialiser en bas
        enableScissor(sr, ix, iy, iw, ih);
        float resetY = iy + ih - 22.0F;
        boolean resetHov = alpha > 0.7F && inside(mouseX, mouseY, ix + 8.0F, resetY, iw - 14.0F, 19.0F);
        NanoUiRenderer.beginBatch();
        NanoUiRenderer.drawRoundedRect(
                ix + 8.0F, resetY, iw - 14.0F, 19.0F, 5.0F,
                mulAlpha(resetHov ? 0xFF261820 : 0xFF121520, alpha),
                mulAlpha(resetHov ? 0x77F87171 : 0x24FFFFFF, alpha),
                1.0F
        );
        NanoUiRenderer.endBatch();

        smallF.beginBatch();
        smallF.drawCentered("Réinitialiser ce module par défaut", ix + iw * 0.5F, resetY + 5.5F, mulAlpha(resetHov ? 0xFFFFB4B4 : 0xFF9AA3B8, alpha), false);
        smallF.endBatch();
    }

    private void drawMiniCrosshairPreview(float cx, float cy, float alpha) {
        int col = mulAlpha(MashModules.col("pvp_crosshair", "color", System.currentTimeMillis(), 0xFFFFFFFF), alpha);
        float sz = Math.min(8.0F, MashFlags.crosshairSize);
        float gap = Math.min(5.0F, MashFlags.crosshairGap);
        float th = Math.min(2.5F, MashFlags.crosshairThickness);
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
            NanoUiRenderer.drawCircle(cx, cy, Math.max(1.4F, sz * 0.45F), col);
        } else if (shape == 3) {
            NanoUiRenderer.drawRing(cx, cy, Math.max(2.5F, sz), th * NanoUiRenderer.getScaleFactor(), col);
        } else if (shape == 4) {
            NanoUiRenderer.drawLine(cx, cy, cx - sz, cy + sz, th, col);
            NanoUiRenderer.drawLine(cx, cy, cx + sz, cy + sz, th, col);
        } else {
            NanoUiRenderer.drawRoundedRect(cx - sz, cy - sz, sz * 2.0F, sz * 2.0F, 1.0F, 0, col, th * NanoUiRenderer.getScaleFactor());
        }
    }

    private float getSettingRowHeight(MashSetting s) {
        if (s instanceof MashSetting.NumberSetting) {
            return 34.0F;
        }
        if (s instanceof MashSetting.ColorSetting) {
            return (expandedColorSetting == s) ? 84.0F : 27.0F;
        }
        return 27.0F;
    }

    private void drawSettingShapes(MashSetting s, float rx, float ry, float rw, int mx, int my, float alpha) {
        if (s instanceof MashSetting.BooleanSetting) {
            MashSetting.BooleanSetting bs = (MashSetting.BooleanSetting) s;
            NanoUiRenderer.drawToggleSwitch(rx + rw - 32.0F, ry + 5.0F, bs.get(), inside(mx, my, rx, ry, rw, 24.0F));
        } else if (s instanceof MashSetting.NumberSetting) {
            MashSetting.NumberSetting ns = (MashSetting.NumberSetting) s;
            float sx = rx + 8.0F;
            float sw = rw - 16.0F;
            boolean hov = inside(mx, my, sx - 2.0F, ry + 16.0F, sw + 4.0F, 12.0F);
            NanoUiRenderer.drawSlider(sx, ry + 20.5F, sw, ns.getFraction(), mulAlpha(0xFFF5B85C, alpha), hov || activeDraggingSlider == ns);
        } else if (s instanceof MashSetting.ModeSetting) {
            float pillW = Math.min(98.0F, rw * 0.46F);
            float pillX = rx + rw - pillW - 6.0F;
            boolean hov = inside(mx, my, pillX, ry + 4.0F, pillW, 16.0F);
            NanoUiRenderer.drawRoundedRect(pillX, ry + 4.0F, pillW, 16.0F, 4.5F, mulAlpha(hov ? 0xFF1C2130 : 0xFF151925, alpha), mulAlpha(0x33F5B85C, alpha), 1.0F);
        } else if (s instanceof MashSetting.KeybindSetting) {
            MashSetting.KeybindSetting ks = (MashSetting.KeybindSetting) s;
            boolean listening = listeningKeybind == ks;
            float pillW = 68.0F;
            float pillX = rx + rw - pillW - 6.0F;
            NanoUiRenderer.drawRoundedRect(
                    pillX, ry + 4.0F, pillW, 16.0F, 4.5F,
                    mulAlpha(listening ? 0x44F5B85C : 0xFF151925, alpha),
                    mulAlpha(listening ? 0xEEF5B85C : 0x2AFFFFFF, alpha),
                    1.0F
            );
        } else if (s instanceof MashSetting.ColorSetting) {
            MashSetting.ColorSetting cs = (MashSetting.ColorSetting) s;
            int resolved = mulAlpha(cs.resolveArgb(System.currentTimeMillis(), 0), alpha);
            // Bouton Chroma
            float chX = rx + rw - 66.0F;
            NanoUiRenderer.drawRoundedRect(
                    chX, ry + 4.5F, 40.0F, 15.0F, 4.0F,
                    mulAlpha(cs.isChroma() ? 0x38F5B85C : 0xFF151925, alpha),
                    mulAlpha(cs.isChroma() ? 0xAAF5B85C : 0x22FFFFFF, alpha),
                    1.0F
            );
            // Pastille couleur cliquable
            NanoUiRenderer.drawRoundedRect(rx + rw - 22.0F, ry + 4.5F, 16.0F, 15.0F, 4.0F, resolved, mulAlpha(0x88FFFFFF, alpha), 1.0F);

            if (expandedColorSetting == cs) {
                float bx = rx + 8.0F;
                float bw = rw - 16.0F;
                int pureHue = Color.HSBtoRGB(cs.getHue(), 1.0F, 1.0F) | 0xFF000000;
                NanoUiRenderer.drawSlider(bx, ry + 28.0F, bw, cs.getHue(), mulAlpha(pureHue, alpha), activeColorDragChannel == 0);
                NanoUiRenderer.drawSlider(bx, ry + 42.0F, bw, cs.getSaturation(), mulAlpha(0xFF38BDF8, alpha), activeColorDragChannel == 1);
                NanoUiRenderer.drawSlider(bx, ry + 56.0F, bw, cs.getBrightness(), mulAlpha(0xFFF5B85C, alpha), activeColorDragChannel == 2);
                NanoUiRenderer.drawSlider(bx, ry + 70.0F, bw, cs.getAlpha() / 255.0F, mulAlpha(0xFFE2E8F0, alpha), activeColorDragChannel == 3);
            }
        }
    }

    private void drawSettingLabels(SmoothFontRenderer smallF, MashSetting s, float rx, float ry, float rw, float alpha) {
        if (s instanceof MashSetting.BooleanSetting) {
            smallF.drawString(smallF.trimToWidth(s.getName(), rw - 42.0F), rx + 7.5F, ry + 7.5F, mulAlpha(0xFFEAECEF, alpha));
        } else if (s instanceof MashSetting.NumberSetting) {
            MashSetting.NumberSetting ns = (MashSetting.NumberSetting) s;
            String val = ns.formatValue();
            float vw = smallF.getStringWidth(val);
            smallF.drawString(smallF.trimToWidth(s.getName(), rw - vw - 18.0F), rx + 7.5F, ry + 5.5F, mulAlpha(0xFFEAECEF, alpha));
            smallF.drawString(val, rx + rw - vw - 8.0F, ry + 5.5F, mulAlpha(0xFFF5B85C, alpha));
        } else if (s instanceof MashSetting.ModeSetting) {
            MashSetting.ModeSetting ms = (MashSetting.ModeSetting) s;
            float pillW = Math.min(98.0F, rw * 0.46F);
            float pillX = rx + rw - pillW - 6.0F;
            smallF.drawString(smallF.trimToWidth(s.getName(), rw - pillW - 14.0F), rx + 7.5F, ry + 7.5F, mulAlpha(0xFFEAECEF, alpha));
            smallF.drawCentered(smallF.trimToWidth(ms.getMode(), pillW - 8.0F), pillX + pillW * 0.5F, ry + 7.5F, mulAlpha(0xFFF7CE86, alpha), false);
        } else if (s instanceof MashSetting.KeybindSetting) {
            MashSetting.KeybindSetting ks = (MashSetting.KeybindSetting) s;
            boolean listening = listeningKeybind == ks;
            float pillW = 68.0F;
            float pillX = rx + rw - pillW - 6.0F;
            smallF.drawString(smallF.trimToWidth(s.getName(), rw - pillW - 14.0F), rx + 7.5F, ry + 7.5F, mulAlpha(0xFFEAECEF, alpha));
            String txt = listening ? " Touche... " : ks.getDisplayString();
            smallF.drawCentered(txt, pillX + pillW * 0.5F, ry + 7.5F, mulAlpha(listening ? 0xFFFFF4DC : 0xFFECEFF6, alpha), false);
        } else if (s instanceof MashSetting.ColorSetting) {
            MashSetting.ColorSetting cs = (MashSetting.ColorSetting) s;
            smallF.drawString(smallF.trimToWidth(s.getName(), rw - 76.0F), rx + 7.5F, ry + 7.5F, mulAlpha(0xFFEAECEF, alpha));
            smallF.drawCentered("Chroma", rx + rw - 46.0F, ry + 7.5F, mulAlpha(cs.isChroma() ? 0xFFF7CE86 : 0xFF8E96AA, alpha), false);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // Si un raccourci écoute un bouton souris (ex: Souris 4 / Souris 5 pour Zoom ou Freelook)
        if (listeningKeybind != null && mouseButton >= 2) {
            listeningKeybind.setKeyCode(-100 + mouseButton);
            listeningKeybind = null;
            playClick();
            return;
        }
        listeningKeybind = null;

        computePanelLayout();
        boolean overStudio = !Keyboard.isKeyDown(Keyboard.KEY_TAB)
                && inside(mouseX, mouseY, panelX, panelY, panelW, panelH);

        // 1. Clic sur n'importe quel élément HUD visible en dehors de la fenêtre (ou avec TAB maintenu) !
        if (!overStudio) {
            MashModule hitHud = hitTestHudOutsideOrCanvas(mouseX, mouseY);
            if (hitHud != null) {
                selectedHud = hitHud;
                if (mouseButton == 1) {
                    inspectedModule = hitHud;
                    inspectorScroll = 0.0F;
                    inspectorScrollTarget = 0.0F;
                    playClick();
                    return;
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
                    return;
                }
            } else if (mouseButton == 0) {
                selectedHud = null;
            }
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        // 2. Clics dans la fenêtre Singularity Studio
        float px = panelX;
        float py = panelY;
        float pw = panelW;
        float ph = panelH;
        float sidebarW = 138.0F;
        float headerH = 38.0F;

        float searchX = px + sidebarW + 10.0F;
        float searchY = py + 8.0F;
        float searchW = pw - sidebarW - 122.0F;
        this.searchFocused = inside(mouseX, mouseY, searchX, searchY, searchW, 22.0F);
        if (this.searchFocused && mouseButton == 1) {
            this.searchQuery = "";
            rebuildFilteredList();
            return;
        }

        // Bouton Fermer
        if (mouseButton == 0 && inside(mouseX, mouseY, px + pw - 30.0F, searchY, 22.0F, 22.0F)) {
            playClick();
            this.mc.displayGuiScreen(this.parentScreen);
            return;
        }

        // Profils Moteur FPS (FPS / Équilibré / Qualité)
        PureFPSProfile[] profs = {PureFPSProfile.FPS, PureFPSProfile.BALANCED, PureFPSProfile.QUALITY};
        float profW = (sidebarW - 18.0F) / 3.0F;
        for (int i = 0; i < 3; i++) {
            float bx = px + 8.0F + i * (profW + 1.0F);
            if (mouseButton == 0 && inside(mouseX, mouseY, bx, py + 37.0F, profW, 16.0F)) {
                PureFPSConfig.applyProfile(profs[i], true);
                MashModules.syncFromPureConfig();
                playClick();
                return;
            }
        }

        // Onglets de navigation
        NavTab[] tabs = NavTab.values();
        for (int i = 0; i < tabs.length; i++) {
            float ty = py + 60.0F + i * 24.0F;
            if (mouseButton == 0 && inside(mouseX, mouseY, px + 7.0F, ty, sidebarW - 14.0F, 22.0F)) {
                this.currentTab = tabs[i];
                rebuildFilteredList();
                playClick();
                return;
            }
        }

        // Bascules Aimantation HUD & Borderless
        if (mouseButton == 0 && inside(mouseX, mouseY, px + 7.0F, py + ph - 48.0F, sidebarW - 14.0F, 19.0F)) {
            magneticSnapEnabled = !magneticSnapEnabled;
            playClick();
            return;
        }
        if (mouseButton == 0 && inside(mouseX, mouseY, px + 7.0F, py + ph - 25.0F, sidebarW - 14.0F, 19.0F)) {
            PureFPSConfig.borderlessFullscreen = !PureFPSConfig.borderlessFullscreen;
            MashModules.MOD_BORDERLESS.setEnabledSilent(PureFPSConfig.borderlessFullscreen);
            PureFPSConfig.markDirty();
            playClick();
            return;
        }

        // Clic dans l'Inspecteur de Sous-Paramètres
        if (inspectedModule != null && mouseX >= getInspectorX()) {
            handleInspectorClick(mouseX, mouseY, mouseButton);
            return;
        }

        // Clic sur une carte de module dans la grille
        float inspectorW = inspectedModule != null ? Math.min(242.0F, pw * 0.40F) : 0.0F;
        float gx = px + sidebarW + 8.0F;
        float gy = py + headerH + 6.0F;
        float gw = pw - sidebarW - inspectorW - 16.0F;
        float gh = ph - headerH - 12.0F;

        if (inside(mouseX, mouseY, gx, gy, gw, gh)) {
            int cols = (inspectedModule != null) ? 2 : (gw >= 460.0F ? 3 : 2);
            float gap = 6.0F;
            float cardW = (gw - gap * (cols - 1)) / (float) cols;
            float cardH = 48.0F;

            for (int i = 0; i < filteredModules.size(); i++) {
                int col = i % cols;
                int row = i / cols;
                float cx = gx + col * (cardW + gap);
                float cy = gy + row * (cardH + gap) - gridScroll;
                if (inside(mouseX, mouseY, cx, cy, cardW, cardH)) {
                    MashModule m = filteredModules.get(i);
                    // Clic étoile favori
                    if (mouseButton == 0 && inside(mouseX, mouseY, cx + cardW - 18.0F, cy + 32.0F, 16.0F, 15.0F)) {
                        m.toggleFavorite();
                        playClick();
                        return;
                    }
                    // Clic Droit OU Clic sur l'icône ⚙ -> Ouvre les Sous-Paramètres !
                    boolean gearClick = mouseButton == 0 && inside(mouseX, mouseY, cx + cardW - 49.0F, cy + 5.0F, 17.0F, 16.0F);
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
                    // Clic Gauche -> Active / Désactive le module (et sélectionne l'élément HUD en transparence s'il est HUD)
                    if (mouseButton == 0) {
                        m.toggle();
                        if (m.isHudWidget()) {
                            selectedHud = m.isEnabled() ? m : null;
                        }
                        playClick();
                        return;
                    }
                }
            }
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    private void handleInspectorClick(int mx, int my, int btn) {
        MashModule mod = inspectedModule;
        if (mod == null) {
            return;
        }
        float ix = getInspectorX();
        float iy = panelY + 42.0F;
        float iw = Math.min(242.0F, panelW * 0.40F) - 4.0F;
        float ih = panelH - 46.0F;

        // Toggle ON/OFF en haut de l'inspecteur
        if (btn == 0 && inside(mx, my, ix + iw - 50.0F, iy + 4.0F, 28.0F, 17.0F)) {
            mod.toggle();
            playClick();
            return;
        }
        // Fermer l'inspecteur
        if (btn == 0 && inside(mx, my, ix + iw - 19.0F, iy + 4.0F, 16.0F, 16.0F)) {
            inspectedModule = null;
            expandedColorSetting = null;
            playClick();
            return;
        }
        // Réinitialiser ce module
        float resetY = iy + ih - 22.0F;
        if (btn == 0 && inside(mx, my, ix + 8.0F, resetY, iw - 14.0F, 19.0F)) {
            mod.resetDefaults();
            playClick();
            return;
        }

        float headerBlockH = (mod == MashModules.MOD_CROSSHAIR) ? 74.0F : 46.0F;
        float listY = iy + headerBlockH;
        float listH = ih - headerBlockH - 26.0F;
        if (!inside(mx, my, ix, listY, iw, listH)) {
            return;
        }

        List<MashSetting> settings = mod.getSettings();
        float curY = listY + 4.0F - inspectorScroll;
        float rx = ix + 7.0F;
        float rw = iw - 12.0F;

        for (int i = 0; i < settings.size(); i++) {
            MashSetting s = settings.get(i);
            float rowH = getSettingRowHeight(s);
            if (inside(mx, my, rx, curY, rw, rowH - 3.0F)) {
                if (s instanceof MashSetting.BooleanSetting && btn == 0) {
                    ((MashSetting.BooleanSetting) s).toggle();
                    playClick();
                    return;
                }
                if (s instanceof MashSetting.NumberSetting && btn == 0) {
                    MashSetting.NumberSetting ns = (MashSetting.NumberSetting) s;
                    activeDraggingSlider = ns;
                    activeSliderX = rx + 8.0F;
                    activeSliderW = rw - 16.0F;
                    ns.setFraction((mx - activeSliderX) / Math.max(1.0F, activeSliderW));
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
                    if (btn == 0 && inside(mx, my, rx + rw - 66.0F, curY + 3.0F, 40.0F, 18.0F)) {
                        cs.setChroma(!cs.isChroma());
                        playClick();
                        return;
                    }
                    if (inside(mx, my, rx, curY, rw, 24.0F)) {
                        expandedColorSetting = (expandedColorSetting == cs) ? null : cs;
                        playClick();
                        return;
                    }
                    if (expandedColorSetting == cs && btn == 0) {
                        activeColorBarX = rx + 8.0F;
                        activeColorBarW = rw - 16.0F;
                        if (my >= curY + 24.0F && my < curY + 38.0F) activeColorDragChannel = 0;
                        else if (my >= curY + 38.0F && my < curY + 52.0F) activeColorDragChannel = 1;
                        else if (my >= curY + 52.0F && my < curY + 66.0F) activeColorDragChannel = 2;
                        else if (my >= curY + 66.0F) activeColorDragChannel = 3;
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

        if (keyCode == Keyboard.KEY_ESCAPE || (!searchFocused && keyCode == Keyboard.KEY_RSHIFT)) {
            this.mc.displayGuiScreen(this.parentScreen);
            return;
        }

        // Déplacement précis 1 px au clavier de l'élément HUD sélectionné
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

        // Recherche instantanée au clavier
        if (keyCode == Keyboard.KEY_BACK) {
            if (!searchQuery.isEmpty()) {
                searchFocused = true;
                searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                rebuildFilteredList();
            }
            return;
        }
        if (ChatAllowedCharacters.isAllowedCharacter(typedChar) && typedChar >= 32) {
            searchFocused = true;
            if (searchQuery.length() < 36) {
                searchQuery += typedChar;
                rebuildFilteredList();
            }
            return;
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
        float iw = Math.min(242.0F, panelW * 0.40F);
        return panelX + panelW - iw;
    }

    private void enableScissor(ScaledResolution sr, float x, float y, float w, float h) {
        int sf = sr.getScaleFactor();
        int sx = Math.max(0, Math.round(x * sf));
        int sy = Math.max(0, this.mc.displayHeight - Math.round((y + h) * sf));
        int sw = Math.max(1, Math.round(w * sf));
        int sh = Math.max(1, Math.round(h * sf));
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(sx, sy, sw, sh);
    }

    private void playClick() {
        if (this.mc != null && this.mc.getSoundHandler() != null) {
            this.mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
        }
    }

    private static int mulAlpha(int argb, float factor) {
        if (factor >= 0.995F) {
            return argb;
        }
        int a = (argb >>> 24) & 0xFF;
        int na = Math.max(0, Math.min(255, Math.round(a * factor)));
        return (na << 24) | (argb & 0x00FFFFFF);
    }

    private static boolean inside(int mx, int my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}

package dev.purefps.gui;

import dev.purefps.client.ui.NanoUiRenderer;
import dev.purefps.client.ui.SmoothFontRenderer;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import dev.purefps.core.ChunkRegionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tableau de bord PureClient 3.5 (Right Shift en jeu ou Menu Principal) :
 * - Design AAA Dark Glass (Lunar Client / OneClient / Linear Standard) — ZÉRO GuiButton Vanilla en pierre !
 * - Barre latérale de navigation + sélecteur de profils (Turbo FPS / Équilibré / Qualité)
 * - Grille Bento 2 colonnes défilable à la molette avec découpe matérielle GL11.glScissor
 * - Interrupteurs pilules fluides anti-aliasés (NanoUiRenderer) & typographie Segoe UI HD (SmoothFontRenderer).
 */
public class GuiPureFPSSettings extends GuiScreen {

    private static final String[] CATEGORY_NAMES = {
            "Modules & HUD",
            "Moteur GPU 3.5",
            "CPU & Chunks",
            "Culling 3D",
            "Particules & Éco"
    };

    private static final String[] CATEGORY_SUBTITLES = {
            "Overlays temps-réel, confort PvP et typographie lissée (100% conforme Watchdog)",
            "VAO Matériel GL30, EBO GL_TRIANGLES, Modèles GPU compilés et Zéro glGetError JNI",
            "Lazy Terrain BFS, constructeurs de chunks parallèles et caches zéro-allocation",
            "Optimisation profonde des Item Frames, Joueurs distants, Panneaux, Coffres et Entités",
            "Budget de particules par couche et économie d'énergie automatique hors focus"
    };

    private static final class SettingCard {
        final int id;
        final String title;
        final String subtitle;
        final boolean isToggle;
        final boolean boolVal;
        final String badgeText;

        SettingCard(int id, String title, String subtitle, boolean boolVal) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.isToggle = true;
            this.boolVal = boolVal;
            this.badgeText = null;
        }

        SettingCard(int id, String title, String subtitle, String badgeText) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.isToggle = false;
            this.boolVal = false;
            this.badgeText = badgeText;
        }
    }

    private final GuiScreen parentScreen;
    private int category = 0;
    private float scrollY = 0.0F;
    private float maxScrollY = 0.0F;
    private final List<SettingCard> activeCards = new ArrayList<SettingCard>(16);

    public GuiPureFPSSettings(GuiScreen parentScreen) {
        this.parentScreen = parentScreen;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        rebuildCards();
    }

    private void rebuildCards() {
        this.activeCards.clear();
        if (this.category == 0) {
            this.activeCards.add(new SettingCard(60, "HUD PureClient", "Affiche les modules en surimpression", PureFPSConfig.hudEnabled));
            this.activeCards.add(new SettingCard(61, "Compteur FPS & CPS", "Pilule temps-réel sans allocation", PureFPSConfig.hudFpsCps));
            this.activeCards.add(new SettingCard(62, "Keystrokes PvP", "Touches ZQSD/WASD + clics LMB/RMB", PureFPSConfig.hudKeystrokes));
            this.activeCards.add(new SettingCard(63, "Ping & Armure", "Latence serveur + durabilité équipée", PureFPSConfig.hudPing && PureFPSConfig.hudArmor));
            this.activeCards.add(new SettingCard(64, "ToggleSprint Safe", "Maintien du sprint Vanilla sans paquet", PureFPSConfig.toggleSprint));
            this.activeCards.add(new SettingCard(65, "Zoom Optique (C)", "Zoom fluide réglable à la molette", PureFPSConfig.zoomEnabled));
            this.activeCards.add(new SettingCard(66, "Fullbright Lossless", "Visibilité grottes sans toucher options.txt", PureFPSConfig.fullbright));
            this.activeCards.add(new SettingCard(67, "Feu Bas (Low Fire)", "Dégage le champ de vision en combat", PureFPSConfig.lowFire));
            this.activeCards.add(new SettingCard(68, "No HurtCam", "Désactive l'inclinaison d'écran aux dégâts", PureFPSConfig.noHurtCam));
            this.activeCards.add(new SettingCard(69, "FOV Statique PvP", "Empêche le zoom de vitesse / potions", PureFPSConfig.staticFov));
            this.activeCards.add(new SettingCard(72, "Police HD Lissée", "Typographie Segoe UI anti-aliasée HUD", PureFPSConfig.smoothClientFont));
            this.activeCards.add(new SettingCard(70, "Menu Accueil Custom", "Écran d'accueil sombre 120 FPS", PureFPSConfig.customMainMenu));
            this.activeCards.add(new SettingCard(71, "Chat & Pseudos Épurés", "Fond transparent sur le chat et pseudos", PureFPSConfig.transparentChat));
        } else if (this.category == 1) {
            this.activeCards.add(new SettingCard(80, "Zéro glGetError JNI", "Supprime les 2 stalls GPU/frame (+60 FPS)", PureFPSConfig.disableGlErrorChecks));
            this.activeCards.add(new SettingCard(81, "VAO Matériel GL30", "1 glBindVertexArray par chunk VBO", PureFPSConfig.hardwareRegionVao));
            this.activeCards.add(new SettingCard(29, "Pipeline GL_TRIANGLES", "EBO 16-bit partagé + zéro glGetFloat", PureFPSConfig.regionMultiDraw));
            this.activeCards.add(new SettingCard(50, "Blit FBO DMA GL30", "Copie framebuffer matérielle colorMask RGBA", PureFPSConfig.fastFboBlit));
            this.activeCards.add(new SettingCard(86, "Modèles Items GPU", "Compile les épées/items en Display Lists", PureFPSConfig.fastBakedItemModels));
            this.activeCards.add(new SettingCard(83, "Items 32x Zéro-Alloc", "Supprime le clonage EnumFacing par item", PureFPSConfig.fastItemRender));
            this.activeCards.add(new SettingCard(82, "LOD Reflet Enchanté", "Coupe le double blend d'armure lointain", PureFPSConfig.limitDistantGlint));
            this.activeCards.add(new SettingCard(51, "Frustum P-Vertex 8x", "Test de boîte englobante en 1 sommet", PureFPSConfig.fastFrustumPVertex));
            this.activeCards.add(new SettingCard(46, "Anti-Freeze Skins", "Lisse le téléversement des skins Hypixel", PureFPSConfig.asyncSkinUploadPacing));
            this.activeCards.add(new SettingCard(20, "Smart Animations", "Anime uniquement les textures visibles", PureFPSConfig.smartAnimations));
            this.activeCards.add(new SettingCard(47, "Scoreboard 1-Draw", "Fusionne tous les fonds du scoreboard", PureFPSConfig.fastScoreboard));
            this.activeCards.add(new SettingCard(28, "Cache Têtes & Crânes", "Élimine le lag des têtes décoratives", PureFPSConfig.fastSkullRendering));
            this.activeCards.add(new SettingCard(22, "Cache Lightmap JNI", "Supprime les appels redondants UV1", PureFPSConfig.fastLightmapCoords));
            this.activeCards.add(new SettingCard(23, "Couper Vignette GPU", "Économise une passe plein écran alpha", PureFPSConfig.disableVignette));
            this.activeCards.add(new SettingCard(25, "Hologrammes 1-Passe", "Rendu optimisé des ArmorStands texte", PureFPSConfig.fastNametags));
        } else if (this.category == 2) {
            this.activeCards.add(new SettingCard(1, "Moteur PureClient", "Interrupteur maître des optimisations", PureFPSConfig.enabled));
            this.activeCards.add(new SettingCard(84, "Lazy Terrain BFS", "Cache renderInfos sur micro-mouvements", PureFPSConfig.lazyTerrainSetup));
            this.activeCards.add(new SettingCard(3, "Chunks Multi-Coeurs", "Compilation parallèle des sections", PureFPSConfig.multiThreadedChunkBuild));
            String threadBadge = PureFPSConfig.chunkWorkerThreads == 0
                    ? "Auto (" + PureFPSConfig.resolveChunkWorkerThreads() + ")"
                    : (PureFPSConfig.chunkWorkerThreads + " threads");
            this.activeCards.add(new SettingCard(4, "Threads de Chunks", "Coeurs dédiés à la géométrie monde", threadBadge));
            this.activeCards.add(new SettingCard(85, "Skip VoidFog Loop", "Coupe les 1000 lookups Barrière/tick", PureFPSConfig.skipVoidFogLoop));
            this.activeCards.add(new SettingCard(2, "Fast Atan2 (Safe AC)", "Orientation rapide sans toucher sin/cos", PureFPSConfig.fastMath));
            this.activeCards.add(new SettingCard(5, "Smooth Light 0-Alloc", "Ambient Occlusion sur tableaux primitifs", PureFPSConfig.zeroAllocSmoothLighting));
            this.activeCards.add(new SettingCard(6, "Cache Teintes Biomes", "Mémorise les couleurs d'herbe/eau", PureFPSConfig.fastBiomeColors));
            this.activeCards.add(new SettingCard(7, "Tri Quads Zéro-Alloc", "Tri translucide sans boxing Float", PureFPSConfig.fastWorldVertexSort));
            this.activeCards.add(new SettingCard(8, "BFS Chunk VisGraph", "Parcours binaire sans file objet", PureFPSConfig.fastChunkVisGraph));
            this.activeCards.add(new SettingCard(10, "Anti-Freeze System.gc()", "Bloque les pauses GC inter-lobbies", PureFPSConfig.disableWorldLoadGC));
            this.activeCards.add(new SettingCard(27, "Ignorer Skylight Client", "Saute le recalcul lumière ciel client", PureFPSConfig.skipClientSkylightUpdates));
        } else if (this.category == 3) {
            this.activeCards.add(new SettingCard(87, "Item Frames Turbo", "1 glCallList + zéro EntityItem/PushAttrib", PureFPSConfig.fastItemFrames));
            this.activeCards.add(new SettingCard(88, "Portée Item Frames", "Cartes murales conservées plus loin", PureFPSConfig.maxItemFrameDistance + " blocs"));
            this.activeCards.add(new SettingCard(89, "Joueurs Distants LOD", "Armures O(1), capes et sur-couches >20m", PureFPSConfig.fastPlayerRendering));
            this.activeCards.add(new SettingCard(90, "Panneaux Zéro-Alloc", "Cache le texte et coupe à >24-32m", PureFPSConfig.fastSignText));
            this.activeCards.add(new SettingCard(30, "Occlusion Entités 3D", "Masque les entités derrière les murs", PureFPSConfig.entityOcclusionCulling));
            this.activeCards.add(new SettingCard(31, "Limite Dist. Entités", "Coupe le rendu au-delà du rayon choisi", PureFPSConfig.entityDistanceCulling));
            this.activeCards.add(new SettingCard(32, "Portée des Entités", "Cliquez pour ajuster la distance max", PureFPSConfig.maxEntityRenderDistance + " blocs"));
            this.activeCards.add(new SettingCard(24, "Items au Sol LOD", "1 modèle à >12m et coupe le reflet >16m", PureFPSConfig.fastDroppedItems));
            this.activeCards.add(new SettingCard(33, "Frustum Coffres / TE", "Élimine les TileEntities hors caméra", PureFPSConfig.tileEntityFrustumCulling));
            this.activeCards.add(new SettingCard(34, "Limite Dist. Coffres", "Coupe les TileEntities très éloignées", PureFPSConfig.tileEntityDistanceCulling));
            this.activeCards.add(new SettingCard(35, "Portée des Coffres", "Cliquez pour ajuster la distance TE", PureFPSConfig.maxTileEntityRenderDistance + " blocs"));
            this.activeCards.add(new SettingCard(36, "Limite Dist. Pseudos", "Masque les étiquettes lointaines", PureFPSConfig.nametagDistanceCulling));
            this.activeCards.add(new SettingCard(37, "Portée des Pseudos", "Cliquez pour ajuster la distance", PureFPSConfig.maxNametagDistance + " blocs"));
        } else {
            this.activeCards.add(new SettingCard(40, "Frustum Particules", "Ignore les particules hors champ", PureFPSConfig.particleFrustumCulling));
            this.activeCards.add(new SettingCard(41, "Physique Particules", "Collisions allégées sur les effets", PureFPSConfig.fastParticlePhysics));
            this.activeCards.add(new SettingCard(42, "Plafond Particules", "Budget maximal par couche d'effet", PureFPSConfig.maxParticlesPerLayer + " / c"));
            this.activeCards.add(new SettingCard(43, "Débris Blocs Légers", "Réduit la saturation en cassant vite", PureFPSConfig.fastBlockBreakParticles));
            this.activeCards.add(new SettingCard(44, "Éco FPS Arrière-Plan", "Réduit la charge GPU pendant Alt-Tab", PureFPSConfig.unfocusedFpsLimiter));
            this.activeCards.add(new SettingCard(45, "Plafond Arrière-Plan", "Cadence cible fenêtre inactive", PureFPSConfig.unfocusedFpsLimit + " FPS"));
            this.activeCards.add(new SettingCard(26, "Télémétrie Écran F3", "Affiche le diagnostic VAO+Triangles", PureFPSConfig.showF3Stats));
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && this.maxScrollY > 0.0F) {
            float step = (wheel > 0) ? -24.0F : 24.0F;
            this.scrollY = Math.max(0.0F, Math.min(this.maxScrollY, this.scrollY + step));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ScaledResolution sr = new ScaledResolution(this.mc);
        int scaleFactor = sr.getScaleFactor();
        NanoUiRenderer.setScaleFactor(scaleFactor);

        // Voile sombre élégant sur le monde ou le menu parent
        this.drawGradientRect(0, 0, this.width, this.height, 0xB005070D, 0xCC070A12);

        float modalW = Math.min(524.0F, this.width - 20.0F);
        float modalH = Math.min(312.0F, this.height - 16.0F);
        float mx0 = (this.width - modalW) * 0.5F;
        float my0 = (this.height - modalH) * 0.5F;
        float sidebarW = 136.0F;

        NanoUiRenderer.drawShadow(mx0, my0, modalW, modalH, 12.0F, 18.0F, 0xAA000000);
        NanoUiRenderer.drawRoundedGradient(mx0, my0, modalW, modalH, 12.0F, 0xF60F131E, 0xF60B0E17, 0x26FFFFFF, 1.0F);

        // Panneau latéral gauche
        NanoUiRenderer.drawRoundedRect(mx0 + 1.0F, my0 + 1.0F, sidebarW, modalH - 2.0F, 11.0F, 0xCC0A0D14, 0, 0.0F);
        NanoUiRenderer.drawRoundedRect(mx0 + sidebarW, my0 + 10.0F, 0.8F, modalH - 20.0F, 0.0F, 0x16FFFFFF, 0, 0.0F);

        SmoothFontRenderer subF = SmoothFontRenderer.subtitle();
        SmoothFontRenderer bodyF = SmoothFontRenderer.body();
        SmoothFontRenderer smallF = SmoothFontRenderer.small();

        // En-tête Sidebar
        subF.drawStringWithShadow("PURECLIENT", mx0 + 14.0F, my0 + 13.0F, 0xFFECEFF6);
        NanoUiRenderer.drawRoundedRect(mx0 + 91.0F, my0 + 13.5F, 28.0F, 11.5F, 5.5F, 0x335E6AD2, 0x665E6AD2, 1.0F);
        smallF.drawCentered("3.5", mx0 + 105.0F, my0 + 15.2F, 0xFFA5B0FF, false);

        // Sélecteur de profils rapides dans la Sidebar
        smallF.drawString("PROFIL MOTEUR", mx0 + 14.0F, my0 + 33.0F, 0xFF5E667A);
        drawProfilePill(mx0 + 10.0F, my0 + 44.0F, sidebarW - 20.0F, 18.0F, PureFPSProfile.FPS, "Turbo FPS", 0xFF34D399, mouseX, mouseY);
        drawProfilePill(mx0 + 10.0F, my0 + 65.0F, sidebarW - 20.0F, 18.0F, PureFPSProfile.BALANCED, "Équilibré", 0xFF5E6AD2, mouseX, mouseY);
        drawProfilePill(mx0 + 10.0F, my0 + 86.0F, sidebarW - 20.0F, 18.0F, PureFPSProfile.QUALITY, "Qualité", 0xFFFBBF24, mouseX, mouseY);

        // Catégories de réglages
        smallF.drawString("NAVIGATION", mx0 + 14.0F, my0 + 112.0F, 0xFF5E667A);
        for (int i = 0; i < CATEGORY_NAMES.length; i++) {
            float cy = my0 + 123.0F + i * 21.0F;
            boolean selected = (this.category == i);
            boolean hov = inside(mouseX, mouseY, mx0 + 10.0F, cy, sidebarW - 20.0F, 18.5F);
            if (selected) {
                NanoUiRenderer.drawRoundedRect(mx0 + 10.0F, cy, sidebarW - 20.0F, 18.5F, 5.5F, 0x335E6AD2, 0x775E6AD2, 1.0F);
                NanoUiRenderer.drawRoundedRect(mx0 + 13.5F, cy + 4.5F, 2.2F, 9.5F, 1.1F, 0xFF818CF8, 0, 0.0F);
                bodyF.drawString(CATEGORY_NAMES[i], mx0 + 20.0F, cy + 4.8F, 0xFFFFFFFF);
            } else {
                if (hov) {
                    NanoUiRenderer.drawRoundedRect(mx0 + 10.0F, cy, sidebarW - 20.0F, 18.5F, 5.5F, 0x16FFFFFF, 0, 0.0F);
                }
                smallF.drawString(CATEGORY_NAMES[i], mx0 + 16.0F, cy + 5.2F, hov ? 0xFFECEFF6 : 0xFF949CB0);
            }
        }

        // Actions bas de Sidebar : Éditer le HUD & Fermer
        float hudBtnY = my0 + modalH - 48.0F;
        float closeBtnY = my0 + modalH - 25.0F;
        drawSidebarFooterBtn(mx0 + 10.0F, hudBtnY, sidebarW - 20.0F, 19.0F, "Déplacer le HUD", true, mouseX, mouseY);
        drawSidebarFooterBtn(mx0 + 10.0F, closeBtnY, sidebarW - 20.0F, 18.0F, "Fermer (Échap)", false, mouseX, mouseY);

        // En-tête de la zone principale (droite)
        float contentX = mx0 + sidebarW + 14.0F;
        float contentW = modalW - sidebarW - 28.0F;

        subF.drawStringWithShadow(CATEGORY_NAMES[this.category], contentX, my0 + 14.0F, 0xFFECEFF6);
        smallF.drawString(CATEGORY_SUBTITLES[this.category], contentX, my0 + 28.0F, 0xFF7E879B);

        // Badge FPS temps-réel en haut à droite de la modale
        String liveFps = Minecraft.getDebugFPS() + " FPS";
        float fpsBadgeW = Math.max(54.0F, smallF.getStringWidth(liveFps) + 22.0F);
        float fpsBadgeX = mx0 + modalW - fpsBadgeW - 14.0F;
        NanoUiRenderer.drawRoundedRect(fpsBadgeX, my0 + 13.0F, fpsBadgeW, 17.0F, 8.5F, 0xCC151A28, 0x24FFFFFF, 1.0F);
        NanoUiRenderer.drawCircle(fpsBadgeX + 8.5F, my0 + 21.5F, 2.3F, 0xFF34D399);
        smallF.drawString(liveFps, fpsBadgeX + 14.5F, my0 + 17.5F, 0xFFECEFF6);

        NanoUiRenderer.drawRoundedRect(contentX, my0 + 42.0F, contentW, 0.8F, 0.0F, 0x16FFFFFF, 0, 0.0F);

        // Grille Bento 2 colonnes défilable sous découpe matérielle glScissor
        float listTop = my0 + 49.0F;
        float listH = modalH - 59.0F;
        int rows = (this.activeCards.size() + 1) / 2;
        float cardH = 34.0F;
        float gapY = 6.0F;
        float gapX = 8.0F;
        float colW = (contentW - gapX) * 0.5F;
        float totalContentH = rows * cardH + Math.max(0, rows - 1) * gapY;
        this.maxScrollY = Math.max(0.0F, totalContentH - listH);
        if (this.scrollY > this.maxScrollY) {
            this.scrollY = this.maxScrollY;
        }

        enableScissor(contentX - 2.0F, listTop, contentW + 4.0F, listH, sr);

        for (int idx = 0; idx < this.activeCards.size(); idx++) {
            SettingCard card = this.activeCards.get(idx);
            int col = idx & 1;
            int row = idx >> 1;
            float cx = contentX + col * (colW + gapX);
            float cy = listTop + row * (cardH + gapY) - this.scrollY;
            if (cy + cardH < listTop - 2.0F || cy > listTop + listH + 2.0F) {
                continue;
            }

            boolean inClip = mouseY >= listTop && mouseY <= listTop + listH;
            boolean hov = inClip && inside(mouseX, mouseY, cx, cy, colW, cardH);

            int bgTop = hov ? 0xF01B2132 : 0xE6141926;
            int bgBot = hov ? 0xF0161B2A : 0xE6111520;
            int border = (card.isToggle && card.boolVal)
                    ? (hov ? 0x665E6AD2 : 0x385E6AD2)
                    : (hov ? 0x34FFFFFF : 0x16FFFFFF);

            NanoUiRenderer.drawRoundedGradient(cx, cy, colW, cardH, 7.0F, bgTop, bgBot, border, 1.0F);

            bodyF.drawString(card.title, cx + 9.0F, cy + 6.5F, 0xFFECEFF6);
            smallF.drawString(card.subtitle, cx + 9.0F, cy + 19.0F, 0xFF7B8499);

            if (card.isToggle) {
                NanoUiRenderer.drawToggleSwitch(cx + colW - 34.0F, cy + 10.0F, card.boolVal, hov);
            } else {
                float bw = Math.max(42.0F, smallF.getStringWidth(card.badgeText) + 12.0F);
                float bx = cx + colW - bw - 7.0F;
                NanoUiRenderer.drawRoundedRect(bx, cy + 8.5F, bw, 17.0F, 5.0F, hov ? 0x445E6AD2 : 0x285E6AD2, 0x665E6AD2, 1.0F);
                smallF.drawCentered(card.badgeText, bx + bw * 0.5F, cy + 13.0F, 0xFFB8C2FF, false);
            }
        }

        disableScissor();

        // Barre de défilement fine 3px à droite si nécessaire
        if (this.maxScrollY > 1.0F) {
            float trackX = mx0 + modalW - 6.5F;
            NanoUiRenderer.drawRoundedRect(trackX, listTop, 2.5F, listH, 1.25F, 0x18FFFFFF, 0, 0.0F);
            float thumbH = Math.max(22.0F, (listH / totalContentH) * listH);
            float thumbY = listTop + (this.scrollY / this.maxScrollY) * (listH - thumbH);
            NanoUiRenderer.drawRoundedRect(trackX, thumbY, 2.5F, thumbH, 1.25F, 0x66949CB0, 0, 0.0F);
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawProfilePill(
            float x,
            float y,
            float w,
            float h,
            PureFPSProfile target,
            String label,
            int dotArgb,
            int mx,
            int my
    ) {
        boolean active = (PureFPSConfig.activeProfile == target);
        boolean hov = inside(mx, my, x, y, w, h);
        int fill = active ? 0x2E5E6AD2 : (hov ? 0x1AFFFFFF : 0x10FFFFFF);
        int border = active ? 0x885E6AD2 : (hov ? 0x2AFFFFFF : 0x12FFFFFF);
        NanoUiRenderer.drawRoundedRect(x, y, w, h, 5.5F, fill, border, 1.0F);
        NanoUiRenderer.drawCircle(x + 8.5F, y + h * 0.5F, 2.4F, active ? dotArgb : 0xFF4B5266);
        SmoothFontRenderer sf = active ? SmoothFontRenderer.body() : SmoothFontRenderer.small();
        sf.drawString(label, x + 15.5F, y + (h - sf.getHeight()) * 0.5F + 0.5F, active ? 0xFFFFFFFF : 0xFF949CB0);
    }

    private static void drawSidebarFooterBtn(float x, float y, float w, float h, String label, boolean accent, int mx, int my) {
        boolean hov = inside(mx, my, x, y, w, h);
        int fill = accent
                ? (hov ? 0xFF6A76E6 : 0xFF5E6AD2)
                : (hov ? 0xFF212738 : 0xFF161A26);
        int border = accent ? 0x48FFFFFF : 0x20FFFFFF;
        NanoUiRenderer.drawRoundedRect(x, y, w, h, 5.5F, fill, border, 1.0F);
        SmoothFontRenderer sf = SmoothFontRenderer.small();
        sf.drawCentered(label, x + w * 0.5F, y + (h - sf.getHeight()) * 0.5F + 0.5F, accent ? 0xFFFFFFFF : 0xFFB8C0D4, false);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton != 0) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        float modalW = Math.min(524.0F, this.width - 20.0F);
        float modalH = Math.min(312.0F, this.height - 16.0F);
        float mx0 = (this.width - modalW) * 0.5F;
        float my0 = (this.height - modalH) * 0.5F;
        float sidebarW = 136.0F;

        // Clic sur profils rapides
        if (inside(mouseX, mouseY, mx0 + 10.0F, my0 + 44.0F, sidebarW - 20.0F, 18.0F)) {
            playClick();
            PureFPSConfig.applyProfile(PureFPSProfile.FPS, true);
            PureFPSConfig.save();
            rebuildCards();
            return;
        }
        if (inside(mouseX, mouseY, mx0 + 10.0F, my0 + 65.0F, sidebarW - 20.0F, 18.0F)) {
            playClick();
            PureFPSConfig.applyProfile(PureFPSProfile.BALANCED, true);
            PureFPSConfig.save();
            rebuildCards();
            return;
        }
        if (inside(mouseX, mouseY, mx0 + 10.0F, my0 + 86.0F, sidebarW - 20.0F, 18.0F)) {
            playClick();
            PureFPSConfig.applyProfile(PureFPSProfile.QUALITY, true);
            PureFPSConfig.save();
            rebuildCards();
            return;
        }

        // Clic sur catégories
        for (int i = 0; i < CATEGORY_NAMES.length; i++) {
            float cy = my0 + 123.0F + i * 21.0F;
            if (inside(mouseX, mouseY, mx0 + 10.0F, cy, sidebarW - 20.0F, 18.5F)) {
                playClick();
                this.category = i;
                this.scrollY = 0.0F;
                rebuildCards();
                return;
            }
        }

        // Boutons bas de Sidebar
        if (inside(mouseX, mouseY, mx0 + 10.0F, my0 + modalH - 48.0F, sidebarW - 20.0F, 19.0F)) {
            playClick();
            PureFPSConfig.save();
            this.mc.displayGuiScreen(new GuiHudEditor(this));
            return;
        }
        if (inside(mouseX, mouseY, mx0 + 10.0F, my0 + modalH - 25.0F, sidebarW - 20.0F, 18.0F)) {
            playClick();
            PureFPSConfig.save();
            this.mc.displayGuiScreen(this.parentScreen);
            return;
        }

        // Clic sur les cartes Bento visibles dans la zone de défilement
        float contentX = mx0 + sidebarW + 14.0F;
        float contentW = modalW - sidebarW - 28.0F;
        float listTop = my0 + 49.0F;
        float listH = modalH - 59.0F;
        if (mouseY >= listTop && mouseY <= listTop + listH) {
            float cardH = 34.0F;
            float gapY = 6.0F;
            float gapX = 8.0F;
            float colW = (contentW - gapX) * 0.5F;

            for (int idx = 0; idx < this.activeCards.size(); idx++) {
                int col = idx & 1;
                int row = idx >> 1;
                float cx = contentX + col * (colW + gapX);
                float cy = listTop + row * (cardH + gapY) - this.scrollY;
                if (inside(mouseX, mouseY, cx, cy, colW, cardH)) {
                    playClick();
                    handleCardClick(this.activeCards.get(idx).id);
                    rebuildCards();
                    return;
                }
            }
        }
    }

    private void handleCardClick(int id) {
        boolean affectsEngineProfile = true;
        switch (id) {
            // Modules & HUD (indépendants du profil FPS/Équilibré/Qualité)
            case 60: PureFPSConfig.hudEnabled = !PureFPSConfig.hudEnabled; affectsEngineProfile = false; break;
            case 61: PureFPSConfig.hudFpsCps = !PureFPSConfig.hudFpsCps; affectsEngineProfile = false; break;
            case 62: PureFPSConfig.hudKeystrokes = !PureFPSConfig.hudKeystrokes; affectsEngineProfile = false; break;
            case 63:
                boolean next = !(PureFPSConfig.hudPing && PureFPSConfig.hudArmor);
                PureFPSConfig.hudPing = next;
                PureFPSConfig.hudArmor = next;
                affectsEngineProfile = false;
                break;
            case 64: PureFPSConfig.toggleSprint = !PureFPSConfig.toggleSprint; affectsEngineProfile = false; break;
            case 65: PureFPSConfig.zoomEnabled = !PureFPSConfig.zoomEnabled; affectsEngineProfile = false; break;
            case 66: PureFPSConfig.fullbright = !PureFPSConfig.fullbright; affectsEngineProfile = false; break;
            case 67: PureFPSConfig.lowFire = !PureFPSConfig.lowFire; affectsEngineProfile = false; break;
            case 68: PureFPSConfig.noHurtCam = !PureFPSConfig.noHurtCam; affectsEngineProfile = false; break;
            case 69: PureFPSConfig.staticFov = !PureFPSConfig.staticFov; affectsEngineProfile = false; break;
            case 70: PureFPSConfig.customMainMenu = !PureFPSConfig.customMainMenu; affectsEngineProfile = false; break;
            case 71:
                boolean clean = !PureFPSConfig.transparentChat;
                PureFPSConfig.transparentChat = clean;
                PureFPSConfig.transparentNametags = clean;
                affectsEngineProfile = false;
                break;
            case 72: PureFPSConfig.smoothClientFont = !PureFPSConfig.smoothClientFont; affectsEngineProfile = false; break;
            case 26: PureFPSConfig.showF3Stats = !PureFPSConfig.showF3Stats; affectsEngineProfile = false; break;

            // Moteur GPU 3.5 & CPU
            case 1:
                PureFPSConfig.enabled = !PureFPSConfig.enabled;
                if (this.mc.renderGlobal != null) {
                    this.mc.renderGlobal.loadRenderers();
                }
                break;
            case 80: PureFPSConfig.disableGlErrorChecks = !PureFPSConfig.disableGlErrorChecks; break;
            case 81: PureFPSConfig.hardwareRegionVao = !PureFPSConfig.hardwareRegionVao; break;
            case 82: PureFPSConfig.limitDistantGlint = !PureFPSConfig.limitDistantGlint; break;
            case 83: PureFPSConfig.fastItemRender = !PureFPSConfig.fastItemRender; break;
            case 84: PureFPSConfig.lazyTerrainSetup = !PureFPSConfig.lazyTerrainSetup; break;
            case 85: PureFPSConfig.skipVoidFogLoop = !PureFPSConfig.skipVoidFogLoop; break;
            case 86: PureFPSConfig.fastBakedItemModels = !PureFPSConfig.fastBakedItemModels; break;
            case 87: PureFPSConfig.fastItemFrames = !PureFPSConfig.fastItemFrames; break;
            case 88:
                PureFPSConfig.maxItemFrameDistance = cycleInt(PureFPSConfig.maxItemFrameDistance, new int[]{24, 36, 48, 64, 96});
                break;
            case 89: PureFPSConfig.fastPlayerRendering = !PureFPSConfig.fastPlayerRendering; break;
            case 90: PureFPSConfig.fastSignText = !PureFPSConfig.fastSignText; break;
            case 2: PureFPSConfig.fastMath = !PureFPSConfig.fastMath; break;
            case 3: PureFPSConfig.multiThreadedChunkBuild = !PureFPSConfig.multiThreadedChunkBuild; break;
            case 4:
                PureFPSConfig.chunkWorkerThreads = cycleInt(PureFPSConfig.chunkWorkerThreads, new int[]{0, 2, 3, 4, 6});
                break;
            case 5: PureFPSConfig.zeroAllocSmoothLighting = !PureFPSConfig.zeroAllocSmoothLighting; break;
            case 6: PureFPSConfig.fastBiomeColors = !PureFPSConfig.fastBiomeColors; break;
            case 7: PureFPSConfig.fastWorldVertexSort = !PureFPSConfig.fastWorldVertexSort; break;
            case 8: PureFPSConfig.fastChunkVisGraph = !PureFPSConfig.fastChunkVisGraph; break;
            case 10: PureFPSConfig.disableWorldLoadGC = !PureFPSConfig.disableWorldLoadGC; break;

            case 20: PureFPSConfig.smartAnimations = !PureFPSConfig.smartAnimations; break;
            case 22: PureFPSConfig.fastLightmapCoords = !PureFPSConfig.fastLightmapCoords; break;
            case 23: PureFPSConfig.disableVignette = !PureFPSConfig.disableVignette; break;
            case 24: PureFPSConfig.fastDroppedItems = !PureFPSConfig.fastDroppedItems; break;
            case 25: PureFPSConfig.fastNametags = !PureFPSConfig.fastNametags; break;
            case 27: PureFPSConfig.skipClientSkylightUpdates = !PureFPSConfig.skipClientSkylightUpdates; break;
            case 28: PureFPSConfig.fastSkullRendering = !PureFPSConfig.fastSkullRendering; break;
            case 29:
                PureFPSConfig.regionMultiDraw = !PureFPSConfig.regionMultiDraw;
                ChunkRegionManager.clearAll();
                if (this.mc.renderGlobal != null) {
                    this.mc.renderGlobal.loadRenderers();
                }
                break;
            case 50: PureFPSConfig.fastFboBlit = !PureFPSConfig.fastFboBlit; break;
            case 51: PureFPSConfig.fastFrustumPVertex = !PureFPSConfig.fastFrustumPVertex; break;
            case 46: PureFPSConfig.asyncSkinUploadPacing = !PureFPSConfig.asyncSkinUploadPacing; break;
            case 47: PureFPSConfig.fastScoreboard = !PureFPSConfig.fastScoreboard; break;

            case 30: PureFPSConfig.entityOcclusionCulling = !PureFPSConfig.entityOcclusionCulling; break;
            case 31: PureFPSConfig.entityDistanceCulling = !PureFPSConfig.entityDistanceCulling; break;
            case 32:
                PureFPSConfig.maxEntityRenderDistance = cycleInt(PureFPSConfig.maxEntityRenderDistance, new int[]{32, 48, 64, 96, 128, 160});
                break;
            case 33: PureFPSConfig.tileEntityFrustumCulling = !PureFPSConfig.tileEntityFrustumCulling; break;
            case 34: PureFPSConfig.tileEntityDistanceCulling = !PureFPSConfig.tileEntityDistanceCulling; break;
            case 35:
                PureFPSConfig.maxTileEntityRenderDistance = cycleInt(PureFPSConfig.maxTileEntityRenderDistance, new int[]{24, 32, 48, 64, 96, 128});
                break;
            case 36: PureFPSConfig.nametagDistanceCulling = !PureFPSConfig.nametagDistanceCulling; break;
            case 37:
                PureFPSConfig.maxNametagDistance = cycleInt(PureFPSConfig.maxNametagDistance, new int[]{16, 24, 32, 48, 64});
                break;

            case 40: PureFPSConfig.particleFrustumCulling = !PureFPSConfig.particleFrustumCulling; break;
            case 41: PureFPSConfig.fastParticlePhysics = !PureFPSConfig.fastParticlePhysics; break;
            case 42:
                PureFPSConfig.maxParticlesPerLayer = cycleInt(PureFPSConfig.maxParticlesPerLayer, new int[]{250, 500, 1000, 2000, 4000});
                break;
            case 43: PureFPSConfig.fastBlockBreakParticles = !PureFPSConfig.fastBlockBreakParticles; break;
            case 44: PureFPSConfig.unfocusedFpsLimiter = !PureFPSConfig.unfocusedFpsLimiter; break;
            case 45:
                PureFPSConfig.unfocusedFpsLimit = cycleInt(PureFPSConfig.unfocusedFpsLimit, new int[]{10, 15, 30, 60});
                break;
            default:
                break;
        }
        if (affectsEngineProfile) {
            PureFPSConfig.refreshProfileAfterManualEdit();
        }
        PureFPSConfig.save();
    }

    private void playClick() {
        this.mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }

    private static void enableScissor(float x, float y, float w, float h, ScaledResolution sr) {
        int sf = sr.getScaleFactor();
        int sx = Math.max(0, (int) Math.floor(x * sf));
        int sy = Math.max(0, (int) Math.floor((sr.getScaledHeight_double() - (y + h)) * sf));
        int sw = Math.max(0, (int) Math.ceil(w * sf));
        int sh = Math.max(0, (int) Math.ceil(h * sf));
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(sx, sy, sw, sh);
    }

    private static void disableScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private static boolean inside(int mx, int my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static int cycleInt(int current, int[] steps) {
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == current) {
                return steps[(i + 1) % steps.length];
            }
        }
        return steps[0];
    }

    @Override
    public void onGuiClosed() {
        PureFPSConfig.save();
    }
}

package dev.purefps.module;

import dev.purefps.client.CpsTracker;
import dev.purefps.client.ui.NanoUiRenderer;
import dev.purefps.client.ui.SmoothFontRenderer;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.RealPingEstimator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.biome.BiomeGenBase;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import java.awt.Color;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Moteur de rendu HUD unifié MashClient 4.1 :
 * - Dessine les 65 modules HUD déplaçables, le Curseur Personnalisé, l'Indicateur d'Arc et l'Alerte Vie Critique.
 * - Regroupe TOUTES les formes vectorielles SDF (cartes, graphes, keystrokes, curseur) en UN SEUL appel GPU NanoUiRenderer.
 * - Lorsqu'un élément HUD est sélectionné dans le menu RSHIFT, son opacité passe automatiquement en semi-transparence
 *   (~48 %) pour permettre un placement chirurgical tout en voyant à travers !
 * - Met en cache les métriques coûteuses (inventaire, ping, RAM, biome, pack) côté tick (4 Hz) -> 0 allocation par frame.
 */
public final class MashHudRenderer {

    private static final long SESSION_START_MS = System.currentTimeMillis();

    // Télémétrie PvP & combat
    private static float lastReachBlocks = 0.0F;
    private static long lastHitTimeMs = 0L;
    private static int currentCombo = 0;
    private static int sessionHitCount = 0;
    private static float lastDamageHearts = 0.0F;
    private static float prevPlayerHealth = -1.0F;
    private static int prevHurtTime = 0;
    private static EntityLivingBase lastTargetEntity = null;
    private static long lastTargetSeenMs = 0L;

    // Cooldowns automatiques (Perle & Pomme d'Or)
    private static long lastPearlThrowMs = 0L;
    private static long lastGappleEatMs = 0L;
    private static int prevPearlCount = -1;
    private static int prevGappleCount = -1;

    // Chronomètre manuel
    private static boolean stopwatchRunning = false;
    private static long stopwatchAccumulatedMs = 0L;
    private static long stopwatchLastStartMs = 0L;

    // Vitesse, TPS serveur & Souris
    private static float currentSpeedBps = 0.0F;
    private static float currentSpeed3DBps = 0.0F;
    private static float estimatedServerTps = 20.0F;
    private static long lastWorldTimeSample = -1L;
    private static long lastWorldWallMs = 0L;
    private static float mouseRadarX = 0.0F;
    private static float mouseRadarY = 0.0F;

    // Graphes circulaires (64 échantillons)
    private static final int GRAPH_LEN = 48;
    private static final float[] FPS_HISTORY = new float[GRAPH_LEN];
    private static final float[] CPS_HISTORY = new float[GRAPH_LEN];
    private static final float[] PING_HISTORY = new float[GRAPH_LEN];
    private static int graphCursor = 0;
    private static long lastGraphSampleMs = 0L;

    // Frametime lissé
    private static long lastFrameNano = 0L;
    private static float smoothedFrameMs = 4.0F;

    // Caches 4 Hz (zéro surcharge pendant le rendu)
    private static long lastSlowCacheMs = 0L;
    private static int cachedPingMs = -1;
    private static int cachedRamPct = 25;
    private static String cachedRamMbText = "512 / 2048 MB";
    private static String cachedClock24 = "12:00";
    private static String cachedClock24Sec = "12:00:00";
    private static String cachedClock12 = "12:00 PM";
    private static String cachedBiomeName = "Plains";
    private static String cachedPackName = "Default";
    private static int cachedLightLevel = 15;

    // Caches zéro-allocation pour widgets à rafraîchissement continu (supprime String.format + Regex par frame)
    private static final StringBuilder HUD_FMT_SB = new StringBuilder(64);
    private static final String[] DIRECTIONS_EN = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final String[] DIRECTIONS_FR = {"N", "NE", "E", "SE", "S", "SO", "O", "NO"};
    private static final String[] MOON_PHASES_EN = {
            "Full Moon", "Waning Gib.", "Last Quarter", "Waning Cres.",
            "New Moon", "Waxing Cres.", "First Quarter", "Waxing Gib."
    };
    private static final String[] MOON_PHASES_FR = {
            "Pleine Lune", "Gibbeuse D.", "Dernier Q.", "Croissant D.",
            "Nouvelle Lune", "Premier C.", "Premier Q.", "Gibbeuse C."
    };
    private static final String[] CUSTOM_LABEL_PRESETS = {
            "MashClient", "MASH \u2022 FPS", "GG \u2022 FairPlay", "PvP"
    };
    private static int cachedFrameMsTenths = Integer.MIN_VALUE;
    private static String cachedFrameMsText = "4.0 ms";
    private static int cachedTpsTenths = Integer.MIN_VALUE;
    private static String cachedTpsText = "20.0 TPS";
    private static long cachedSessionSec = -1L;
    private static String cachedSessionText = "00:00";
    private static int cachedGameTimeMin = -1;
    private static String cachedGameTimeText = "12:00";
    private static int cachedReachHundredths = Integer.MIN_VALUE;
    private static boolean cachedReachLangEn = false;
    private static String cachedReachText = "0.00 blocs";
    private static int cachedSpeedScaled = Integer.MIN_VALUE;
    private static int cachedSpeedUnit = -1;
    private static String cachedSpeedText = "0.00 m/s";

    // Caches zéro-allocation pour les widgets les plus fréquents (FPS, CPS, Ping, Coords)
    private static int cachedFpsVal = Integer.MIN_VALUE;
    private static boolean cachedFpsPrefix = true;
    private static boolean cachedFpsBrackets = false;
    private static String cachedFpsLabel = "0 FPS";

    private static int cachedCpsL = Integer.MIN_VALUE;
    private static int cachedCpsR = Integer.MIN_VALUE;
    private static boolean cachedCpsRight = true;
    private static boolean cachedCpsPrefix = true;
    private static boolean cachedCpsBrackets = false;
    private static String cachedCpsLabel = "0 \u2022 0 CPS";

    private static int cachedPingLabelVal = Integer.MIN_VALUE;
    private static boolean cachedPingPrefix = true;
    private static boolean cachedPingBrackets = false;
    private static String cachedPingLabel = "Ping: Solo";

    private static long cachedCoordQx = Long.MIN_VALUE;
    private static long cachedCoordQy = Long.MIN_VALUE;
    private static long cachedCoordQz = Long.MIN_VALUE;
    private static int cachedCoordDec = -1;
    private static boolean cachedCoordShowY = true;
    private static int cachedCoordLayout = -1;
    private static String cachedCoordInline = "XYZ: 0  64  0";
    private static String cachedCoordRowX = "X: 0";
    private static String cachedCoordRowY = "Y: 64";
    private static String cachedCoordRowZ = "Z: 0";

    // Compteurs inventaire mis en cache (ticks)
    private static int invArrows = 0;
    private static int invHealPots = 0;
    private static int invGapples = 0;
    private static int invPearls = 0;
    private static int invSoups = 0;
    private static int invBlocks = 0;
    private static int invFireballs = 0;
    private static int invIron = 0;
    private static int invGold = 0;
    private static int invDiamond = 0;
    private static int invEmerald = 0;

    // Sourdine automatique hors focus
    private static boolean mutedByFocusLoss = false;
    private static float savedMasterVolume = 1.0F;

    // File d'attente de texte différé pour le lot SmoothFontRenderer
    private static final class QueuedText {
        String text;
        float x;
        float y;
        float scale;
        int argb;
        boolean shadow;
        boolean centered;
    }

    private static final List<QueuedText> TEXT_QUEUE = new ArrayList<>(128);
    private static int textQueueUsed = 0;

    // File d'attente d'icônes d'objets 3D à dessiner après la passe SDF/Texte
    private static final class QueuedItem {
        ItemStack stack;
        float x;
        float y;
        float scale;
        boolean overlay;
    }

    private static final List<QueuedItem> ITEM_QUEUE = new ArrayList<>(64);
    private static int itemQueueUsed = 0;

    private static final SimpleDateFormat FMT_24 = new SimpleDateFormat("HH:mm", Locale.ROOT);
    private static final SimpleDateFormat FMT_24_SEC = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private static final SimpleDateFormat FMT_12 = new SimpleDateFormat("hh:mm a", Locale.US);

    private static ItemStack sampleSword;
    private static ItemStack[] sampleArmor;

    private MashHudRenderer() {
    }

    /**
     * Libère toute référence d'entité/monde lors d'un changement de lobby ou déchargement de monde.
     */
    public static void clearWorldRefs() {
        lastTargetEntity = null;
        prevPlayerHealth = -1.0F;
        lastWorldTimeSample = -1L;
        for (int i = 0; i < TEXT_QUEUE.size(); i++) {
            TEXT_QUEUE.get(i).text = null;
        }
        for (int i = 0; i < ITEM_QUEUE.size(); i++) {
            ITEM_QUEUE.get(i).stack = null;
        }
        textQueueUsed = 0;
        itemQueueUsed = 0;
    }

    public static void onAttackEntity(EntityPlayer attacker, Entity target) {
        if (attacker == null || target == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || attacker != mc.thePlayer) {
            return;
        }
        long now = System.currentTimeMillis();
        if (mc.objectMouseOver != null && mc.objectMouseOver.hitVec != null) {
            double dist = attacker.getPositionEyes(1.0F).distanceTo(mc.objectMouseOver.hitVec);
            lastReachBlocks = (float) Math.min(6.0D, Math.max(0.0D, dist));
        } else {
            float raw = attacker.getDistanceToEntity(target) - target.getCollisionBorderSize();
            lastReachBlocks = Math.max(0.0F, Math.min(6.0F, raw));
        }
        lastHitTimeMs = now;
        currentCombo++;
        sessionHitCount++;
        if (target instanceof EntityLivingBase) {
            lastTargetEntity = (EntityLivingBase) target;
            lastTargetSeenMs = now;
        }
    }

    public static void toggleStopwatch() {
        long now = System.currentTimeMillis();
        if (stopwatchRunning) {
            stopwatchAccumulatedMs += (now - stopwatchLastStartMs);
            stopwatchRunning = false;
        } else {
            stopwatchLastStartMs = now;
            stopwatchRunning = true;
        }
    }

    public static void resetStopwatch() {
        stopwatchRunning = false;
        stopwatchAccumulatedMs = 0L;
        stopwatchLastStartMs = System.currentTimeMillis();
    }

    public static void onClientTick(Minecraft mc) {
        if (mc == null) {
            return;
        }

        // Sourdine automatique hors focus (wld_mute_unfocused) + arrêt son du Trou Noir hors menu principal
        if (mc.gameSettings != null && Display.isCreated()) {
            boolean active = Display.isActive();
            if (PureFPSConfig.enabled && MashFlags.muteUnfocusedAudio && !active) {
                dev.purefps.client.audio.MashAudioEngine.updateMenuAmbientLoop(false);
                if (!mutedByFocusLoss) {
                    savedMasterVolume = mc.gameSettings.getSoundLevel(SoundCategory.MASTER);
                    float targetBgVol = MashFlags.muteUnfocusedBgVol;
                    if (savedMasterVolume > targetBgVol + 0.001F) {
                        mc.gameSettings.setSoundLevel(SoundCategory.MASTER, targetBgVol);
                        mutedByFocusLoss = true;
                    }
                }
            } else if (mutedByFocusLoss) {
                mc.gameSettings.setSoundLevel(SoundCategory.MASTER, savedMasterVolume);
                mutedByFocusLoss = false;
            }
        }
        if (!(mc.currentScreen instanceof dev.purefps.gui.GuiPureMainMenu)) {
            dev.purefps.client.audio.MashAudioEngine.updateMenuAmbientLoop(false);
        }

        long now = System.currentTimeMillis();

        if (mc.thePlayer != null && mc.theWorld != null) {
            EntityPlayer p = mc.thePlayer;
            double dx = p.posX - p.prevPosX;
            double dy = p.posY - p.prevPosY;
            double dz = p.posZ - p.prevPosZ;
            currentSpeedBps = (float) (Math.sqrt(dx * dx + dz * dz) * 20.0D);
            currentSpeed3DBps = (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) * 20.0D);

            // Détection de dégâts reçus -> reset combo + dernier impact
            float hp = p.getHealth() + p.getAbsorptionAmount();
            if (p.hurtTime > prevHurtTime && p.hurtTime >= 8) {
                currentCombo = 0;
                if (prevPlayerHealth > hp) {
                    lastDamageHearts = (prevPlayerHealth - hp) * 0.5F;
                }
            }
            prevHurtTime = p.hurtTime;
            prevPlayerHealth = hp;

            // Expiration naturelle du combo
            if (currentCombo > 0 && (now - lastHitTimeMs) > (long) MashFlags.hudComboResetMs) {
                currentCombo = 0;
            }

            // Cible sous le viseur
            if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit instanceof EntityLivingBase) {
                lastTargetEntity = (EntityLivingBase) mc.objectMouseOver.entityHit;
                lastTargetSeenMs = now;
            }

            // Estimation TPS serveur
            long wt = mc.theWorld.getTotalWorldTime();
            if (lastWorldTimeSample >= 0L && wt >= lastWorldTimeSample && now - lastWorldWallMs >= 1000L) {
                float dtSec = (now - lastWorldWallMs) * 0.001F;
                float rawTps = (wt - lastWorldTimeSample) / Math.max(0.05F, dtSec);
                float clamped = Math.max(0.0F, Math.min(20.0F, rawTps));
                estimatedServerTps = estimatedServerTps * 0.7F + clamped * 0.3F;
                lastWorldTimeSample = wt;
                lastWorldWallMs = now;
            } else if (lastWorldTimeSample < 0L || wt < lastWorldTimeSample) {
                lastWorldTimeSample = wt;
                lastWorldWallMs = now;
                estimatedServerTps = 20.0F;
            }
        } else {
            prevPlayerHealth = -1.0F;
            lastWorldTimeSample = -1L;
            lastTargetEntity = null;
        }

        // Mise à jour 4 Hz (toutes les 250 ms) uniquement pour les widgets HUD réellement activés
        if (now - lastSlowCacheMs >= 250L) {
            lastSlowCacheMs = now;
            updateSlowCaches(mc, now);
        }
    }

    private static void updateSlowCaches(Minecraft mc, long now) {
        MashModule[] activeHud = MashModules.getEnabledHudModules();
        boolean needPing = false;
        boolean needMemory = false;
        boolean needClock = false;
        boolean needPack = false;
        boolean needBiomeOrLight = false;
        boolean needInventory = false;

        for (int i = 0; i < activeHud.length; i++) {
            String id = activeHud[i].getId();
            switch (id) {
                case "hud_ping":
                case "hud_ping_graph":
                    needPing = true;
                    break;
                case "hud_memory":
                    needMemory = true;
                    break;
                case "hud_clock":
                    needClock = true;
                    break;
                case "hud_pack_display":
                    needPack = true;
                    break;
                case "hud_biome":
                case "hud_light_level":
                    needBiomeOrLight = true;
                    break;
                case "hud_arrow_count":
                case "hud_pot_count":
                case "hud_gapple_count":
                case "hud_pearl_count":
                case "hud_soup_count":
                case "hud_totem_block":
                case "hud_fireball_count":
                case "hud_bw_resources":
                case "hud_pearl_cooldown":
                case "hud_gapple_cooldown":
                    needInventory = true;
                    break;
                default:
                    break;
            }
        }

        if (needPing) {
            cachedPingMs = resolvePing(mc);
        }

        if (needMemory) {
            Runtime rt = Runtime.getRuntime();
            long maxB = Math.max(1L, rt.maxMemory());
            long usedB = Math.max(0L, rt.totalMemory() - rt.freeMemory());
            cachedRamPct = (int) Math.min(100L, (usedB * 100L) / maxB);
            cachedRamMbText = (usedB >> 20) + " / " + (maxB >> 20) + (PureFPSConfig.isEnglish() ? " MB" : " Mo");
        }

        if (needClock) {
            Date d = new Date(now);
            cachedClock24 = FMT_24.format(d);
            cachedClock24Sec = FMT_24_SEC.format(d);
            cachedClock12 = FMT_12.format(d);
        }

        if (needPack && mc.getResourcePackRepository() != null) {
            List<ResourcePackRepository.Entry> entries = mc.getResourcePackRepository().getRepositoryEntries();
            if (entries != null && !entries.isEmpty()) {
                String raw = entries.get(entries.size() - 1).getResourcePackName();
                cachedPackName = stripFormatting(raw).replace(".zip", "");
            } else {
                cachedPackName = "Default";
            }
        }

        if ((needBiomeOrLight || needInventory) && mc.thePlayer != null && mc.theWorld != null) {
            if (needBiomeOrLight) {
                BlockPos pos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
                if (mc.theWorld.isBlockLoaded(pos)) {
                    BiomeGenBase biome = mc.theWorld.getBiomeGenForCoords(pos);
                    if (biome != null && biome.biomeName != null) {
                        cachedBiomeName = biome.biomeName;
                    }
                    cachedLightLevel = mc.theWorld.getLightFor(EnumSkyBlock.BLOCK, pos);
                    int sky = mc.theWorld.getLightFor(EnumSkyBlock.SKY, pos) - mc.theWorld.getSkylightSubtracted();
                    if (sky > cachedLightLevel) {
                        cachedLightLevel = sky;
                    }
                }
            }
            if (needInventory) {
                scanPlayerInventory(mc.thePlayer, now);
            }
        }
    }

    private static void scanPlayerInventory(EntityPlayer player, long now) {
        int arrows = 0, pots = 0, gapples = 0, pearls = 0, soups = 0, blocks = 0, fireballs = 0;
        int iron = 0, gold = 0, diamond = 0, emerald = 0;

        Item tntItem = Item.getItemFromBlock(Blocks.tnt);
        ItemStack[] main = player.inventory.mainInventory;
        for (int i = 0; i < main.length; i++) {
            ItemStack st = main[i];
            if (st == null || st.stackSize <= 0) {
                continue;
            }
            Item it = st.getItem();
            int n = st.stackSize;
            if (it == Items.arrow) {
                arrows += n;
            } else if (it == Items.golden_apple) {
                gapples += n;
            } else if (it == Items.ender_pearl) {
                pearls += n;
            } else if (it == Items.mushroom_stew) {
                soups += n;
            } else if (it == Items.fire_charge || it == tntItem) {
                fireballs += n;
            } else if (it == Items.iron_ingot) {
                iron += n;
            } else if (it == Items.gold_ingot) {
                gold += n;
            } else if (it == Items.diamond) {
                diamond += n;
            } else if (it == Items.emerald) {
                emerald += n;
            } else if (it instanceof ItemPotion && ItemPotion.isSplash(st.getMetadata())) {
                List<PotionEffect> fx = ((ItemPotion) it).getEffects(st);
                if (fx != null) {
                    for (int k = 0; k < fx.size(); k++) {
                        if (fx.get(k).getPotionID() == Potion.heal.id) {
                            pots += n;
                            break;
                        }
                    }
                }
            } else if (it instanceof ItemBlock) {
                blocks += n;
            }
        }

        if (prevPearlCount > pearls && pearls >= 0) {
            lastPearlThrowMs = now;
        }
        if (prevGappleCount > gapples && gapples >= 0) {
            lastGappleEatMs = now;
        }
        prevPearlCount = pearls;
        prevGappleCount = gapples;

        invArrows = arrows;
        invHealPots = pots;
        invGapples = gapples;
        invPearls = pearls;
        invSoups = soups;
        invBlocks = blocks;
        invFireballs = fireballs;
        invIron = iron;
        invGold = gold;
        invDiamond = diamond;
        invEmerald = emerald;
    }

    public static void renderOverlay(
            Minecraft mc,
            ScaledResolution sr,
            boolean editorPreview,
            String selectedHudId,
            boolean draggingSelected
    ) {
        if (mc == null || sr == null || mc.fontRendererObj == null) {
            return;
        }
        if (!editorPreview) {
            if (!PureFPSConfig.enabled || !PureFPSConfig.hudEnabled) {
                return;
            }
            if (mc.gameSettings != null && mc.gameSettings.showDebugInfo) {
                return;
            }
        }

        MashModule[] activeHudMods = MashModules.getEnabledHudModules();
        NanoUiRenderer.setScaleFactor(sr.getScaleFactor());

        long nowNano = System.nanoTime();
        if (!Display.isCreated() || Display.isActive()) {
            if (lastFrameNano != 0L) {
                float dtMs = (nowNano - lastFrameNano) * 1.0e-6F;
                if (dtMs > 0.05F && dtMs < 200.0F) {
                    smoothedFrameMs = smoothedFrameMs * 0.90F + dtMs * 0.10F;
                }
            }
            lastFrameNano = nowNano;
        } else {
            lastFrameNano = 0L;
        }

        long nowMs = System.currentTimeMillis();
        int fps = Minecraft.getDebugFPS();
        int lCps = CpsTracker.getLeftCps(nowMs);
        int rCps = CpsTracker.getRightCps(nowMs);

        if (nowMs - lastGraphSampleMs >= 100L) {
            lastGraphSampleMs = nowMs;
            FPS_HISTORY[graphCursor] = fps;
            CPS_HISTORY[graphCursor] = lCps + rCps;
            PING_HISTORY[graphCursor] = Math.max(0, cachedPingMs);
            graphCursor = (graphCursor + 1) % GRAPH_LEN;
        }

        if (MashFlags.hudMouseStrokerEnabled && mc.currentScreen == null && mc.mouseHelper != null) {
            mouseRadarX = clamp(mouseRadarX * 0.82F + mc.mouseHelper.deltaX * 0.14F, -18.0F, 18.0F);
            mouseRadarY = clamp(mouseRadarY * 0.82F - mc.mouseHelper.deltaY * 0.14F, -18.0F, 18.0F);
        } else if (mouseRadarX != 0.0F || mouseRadarY != 0.0F) {
            mouseRadarX *= 0.90F;
            mouseRadarY *= 0.90F;
            if (Math.abs(mouseRadarX) < 0.01F) mouseRadarX = 0.0F;
            if (Math.abs(mouseRadarY) < 0.01F) mouseRadarY = 0.0F;
        }

        textQueueUsed = 0;
        itemQueueUsed = 0;

        // =========================================================================
        // PASSE 1 : TOUTES LES FORMES VECTORIELLES SDF EN UN SEUL APPEL GPU !
        // =========================================================================
        NanoUiRenderer.beginBatch();

        if (!editorPreview) {
            if (MashFlags.criticalHealthVignette && mc.thePlayer != null && !mc.thePlayer.capabilities.isCreativeMode) {
                renderCriticalHealthVignette(mc.thePlayer, sr, nowMs);
            }
            if (MashFlags.customCrosshair && mc.currentScreen == null) {
                if (mc.gameSettings.thirdPersonView == 0 || MashFlags.crosshairShowInThirdPerson) {
                    renderCustomCrosshair(mc, sr, nowMs);
                }
            }
            if (MashFlags.bowChargeIndicator && mc.currentScreen == null && mc.thePlayer != null) {
                renderBowChargeArc(mc.thePlayer, sr);
            }
        }

        for (int i = 0; i < activeHudMods.length; i++) {
            MashModule mod = activeHudMods[i];
            // Lorsqu'un module est sélectionné sur le menu RSHIFT, il devient translucide (~48%) !
            boolean isSelected = selectedHudId != null && selectedHudId.equals(mod.getId());
            float alphaMul = isSelected ? (draggingSelected ? 0.44F : 0.54F) : 1.0F;
            emitHudWidget(mc, sr, mod, nowMs, fps, lCps, rCps, editorPreview, alphaMul);
        }

        NanoUiRenderer.endBatch();

        // =========================================================================
        // PASSE 2 : TOUS LES TEXTES (TOUTES ÉCHELLES) REGROUPÉS EN 1 SEUL DRAW CALL
        // =========================================================================
        if (textQueueUsed > 0) {
            if (PureFPSConfig.smoothClientFont) {
                SmoothFontRenderer smallF = SmoothFontRenderer.small();
                smallF.beginBatch();
                for (int i = 0; i < textQueueUsed; i++) {
                    QueuedText qt = TEXT_QUEUE.get(i);
                    smallF.drawBatchedScaled(qt.text, qt.x, qt.y, qt.scale, qt.argb, qt.shadow, qt.centered);
                    qt.text = null;
                }
                smallF.endBatch();
            } else {
                FontRenderer fr = mc.fontRendererObj;
                for (int i = 0; i < textQueueUsed; i++) {
                    QueuedText qt = TEXT_QUEUE.get(i);
                    GlStateManager.pushMatrix();
                    GlStateManager.translate(qt.x, qt.y, 0.0F);
                    if (Math.abs(qt.scale - 1.0F) > 0.015F) {
                        GlStateManager.scale(qt.scale, qt.scale, 1.0F);
                    }
                    float drawX = qt.centered ? -fr.getStringWidth(qt.text) * 0.5F : 0.0F;
                    fr.drawString(qt.text, drawX, 0.5F, qt.argb, qt.shadow);
                    GlStateManager.popMatrix();
                    qt.text = null;
                }
            }
            textQueueUsed = 0;
        }

        // =========================================================================
        // PASSE 3 : ICÔNES D'OBJETS (Armure, Main, Hotbar, Inventaire)
        // =========================================================================
        if (itemQueueUsed > 0) {
            RenderItem ri = mc.getRenderItem();
            GlStateManager.pushMatrix();
            RenderHelper.enableGUIStandardItemLighting();
            for (int i = 0; i < itemQueueUsed; i++) {
                QueuedItem qi = ITEM_QUEUE.get(i);
                ItemStack st = qi.stack;
                qi.stack = null;
                if (st == null) {
                    continue;
                }
                GlStateManager.pushMatrix();
                GlStateManager.translate(qi.x, qi.y, 0.0F);
                if (Math.abs(qi.scale - 1.0F) > 0.015F) {
                    GlStateManager.scale(qi.scale, qi.scale, 1.0F);
                }
                ri.renderItemAndEffectIntoGUI(st, 0, 0);
                if (qi.overlay) {
                    ri.renderItemOverlays(mc.fontRendererObj, st, 0, 0);
                }
                GlStateManager.popMatrix();
            }
            itemQueueUsed = 0;
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableLighting();
            GlStateManager.popMatrix();
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void emitHudWidget(
            Minecraft mc,
            ScaledResolution sr,
            MashModule mod,
            long nowMs,
            int fps,
            int lCps,
            int rCps,
            boolean preview,
            float alphaMul
    ) {
        String id = mod.getId();
        float x = mod.getHudX();
        float y = mod.getHudY();
        float s = mod.getScale();
        int textCol = mulAlpha(mod.hudTextColor.resolveArgb(nowMs, id.hashCode() & 0x3FF), alphaMul);
        int accentCol = mulAlpha(mod.hudAccentColor.resolveArgb(nowMs, 180), alphaMul);
        boolean shadow = mod.hudTextShadow.get();

        boolean en = PureFPSConfig.isEnglish();

        switch (id) {
            case "hud_watermark": {
                int style = mode(mod, "style", 0);
                boolean inlineFps = bool(mod, "show_fps_inline", false);
                String base = style == 1 ? "MASH \u2022 FPS" : (style == 2 ? "MASH" : "MashClient");
                if (inlineFps) {
                    base = base + "  \u2022  " + fps + " FPS";
                }
                drawStandardBadge(mod, x, y, s, base, accentCol, textCol, shadow, true, alphaMul);
                return;
            }
            case "hud_fps": {
                boolean dynCol = bool(mod, "fast_color", false);
                int col = textCol;
                if (dynCol) {
                    col = mulAlpha(fps >= 144 ? 0xFF34D399 : (fps >= 60 ? 0xFFF5B85C : 0xFFF87171), alphaMul);
                }
                boolean showPrefix = bool(mod, "show_prefix", true);
                boolean brackets = bool(mod, "brackets", false);
                if (fps != cachedFpsVal || showPrefix != cachedFpsPrefix || brackets != cachedFpsBrackets) {
                    cachedFpsLabel = formatBadgeTextDirect("FPS", String.valueOf(fps), true, showPrefix, brackets);
                    cachedFpsVal = fps;
                    cachedFpsPrefix = showPrefix;
                    cachedFpsBrackets = brackets;
                }
                drawStandardBadge(mod, x, y, s, cachedFpsLabel, accentCol, col, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_cps": {
                boolean right = bool(mod, "right_cps", true);
                boolean showPrefix = bool(mod, "show_prefix", true);
                boolean brackets = bool(mod, "brackets", false);
                if (lCps != cachedCpsL || rCps != cachedCpsR || right != cachedCpsRight
                        || showPrefix != cachedCpsPrefix || brackets != cachedCpsBrackets) {
                    HUD_FMT_SB.setLength(0);
                    HUD_FMT_SB.append(lCps);
                    if (right) {
                        HUD_FMT_SB.append(" \u2022 ").append(rCps);
                    }
                    cachedCpsLabel = formatBadgeTextDirect("CPS", HUD_FMT_SB.toString(), true, showPrefix, brackets);
                    cachedCpsL = lCps;
                    cachedCpsR = rCps;
                    cachedCpsRight = right;
                    cachedCpsPrefix = showPrefix;
                    cachedCpsBrackets = brackets;
                }
                drawStandardBadge(mod, x, y, s, cachedCpsLabel, accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_ping": {
                int p = cachedPingMs;
                float warn = num(mod, "warn_ms", 120.0F);
                int dot = mulAlpha(p < 0 ? 0xFF60A5FA : (p >= warn ? 0xFFFBBF24 : 0xFF34D399), alphaMul);
                boolean showPrefix = bool(mod, "show_prefix", true);
                boolean brackets = bool(mod, "brackets", false);
                if (p != cachedPingLabelVal || showPrefix != cachedPingPrefix || brackets != cachedPingBrackets) {
                    String val = p >= 0 ? (p + " ms") : "Solo";
                    cachedPingLabel = formatBadgeTextDirect("Ping", val, false, showPrefix, brackets);
                    cachedPingLabelVal = p;
                    cachedPingPrefix = showPrefix;
                    cachedPingBrackets = brackets;
                }
                drawStandardBadge(mod, x, y, s, cachedPingLabel, dot, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_sprint": {
                boolean sneaking = MashFlags.toggleSneakEnabled && MashFlags.sneakerActive;
                boolean sprinting = PureFPSConfig.sprintToggledActive;
                if (!preview && bool(mod, "hide_idle", false) && !sprinting && !sneaking) {
                    return;
                }
                int fmt = mode(mod, "format", 0);
                String txt;
                if (sneaking) {
                    txt = fmt == 1 ? "[Sneaking (Toggled)]" : (en ? "Sneak \u2022 Active" : "Accroupi \u2022 Actif");
                } else if (sprinting) {
                    txt = fmt == 1 ? "[Sprinting (Toggled)]" : (fmt == 2 ? "SPRINT" : (en ? "Sprint \u2022 Active" : "Sprint \u2022 Actif"));
                } else {
                    txt = fmt == 1 ? "[Walking]" : (fmt == 2 ? (en ? "MANUAL" : "MANUEL") : (en ? "Sprint \u2022 Manual" : "Sprint \u2022 Manuel"));
                }
                int dot = (sprinting || sneaking) ? accentCol : mulAlpha(0xFF64748B, alphaMul);
                drawStandardBadge(mod, x, y, s, txt, dot, textCol, shadow, true, alphaMul);
                return;
            }
            case "hud_keystrokes": {
                renderKeystrokesWidget(mc, mod, x, y, s, lCps, rCps, textCol, shadow, alphaMul);
                return;
            }
            case "hud_armor": {
                renderArmorWidget(mc, mod, x, y, s, textCol, shadow, preview, alphaMul);
                return;
            }
            case "hud_potions": {
                renderPotionsWidget(mc, mod, x, y, s, nowMs, accentCol, textCol, shadow, preview, alphaMul);
                return;
            }
            case "hud_coords": {
                renderCoordsWidget(mc, mod, x, y, s, accentCol, textCol, shadow, alphaMul);
                return;
            }
            case "hud_direction": {
                renderDirectionWidget(mc, mod, x, y, s, accentCol, textCol, shadow, alphaMul);
                return;
            }
            case "hud_reach": {
                float decayMs = num(mod, "decay_sec", 3.5F) * 1000.0F;
                float r = (nowMs - lastHitTimeMs <= (long) decayMs) ? lastReachBlocks : 0.0F;
                int hundredths = Math.round(r * 100.0F);
                if (hundredths != cachedReachHundredths || en != cachedReachLangEn) {
                    HUD_FMT_SB.setLength(0);
                    appendFixed2(HUD_FMT_SB, r);
                    HUD_FMT_SB.append(en ? " blocks" : " blocs");
                    cachedReachText = HUD_FMT_SB.toString();
                    cachedReachHundredths = hundredths;
                    cachedReachLangEn = en;
                }
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Reach", cachedReachText, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_combo": {
                String val = currentCombo > 0 ? (currentCombo + " Combo") : (en ? "No combo" : "Aucun combo");
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Combo", val, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_speed": {
                int unit = mode(mod, "unit", 0);
                boolean incY = bool(mod, "include_y", false);
                float bps = incY ? currentSpeed3DBps : currentSpeedBps;
                int scaled = (unit == 1) ? Math.round(bps * 36.0F) : ((unit == 2) ? Math.round(bps * 5.0F) : Math.round(bps * 100.0F));
                if (scaled != cachedSpeedScaled || unit != cachedSpeedUnit) {
                    HUD_FMT_SB.setLength(0);
                    if (unit == 1) {
                        appendFixed1(HUD_FMT_SB, bps * 3.6F);
                        HUD_FMT_SB.append(" km/h");
                    } else if (unit == 2) {
                        appendFixed2(HUD_FMT_SB, bps * 0.05F);
                        HUD_FMT_SB.append(" b/t");
                    } else {
                        appendFixed2(HUD_FMT_SB, bps);
                        HUD_FMT_SB.append(" m/s");
                    }
                    cachedSpeedText = HUD_FMT_SB.toString();
                    cachedSpeedScaled = scaled;
                    cachedSpeedUnit = unit;
                }
                drawStandardBadge(mod, x, y, s, cachedSpeedText, accentCol, textCol, shadow, true, alphaMul);
                return;
            }
            case "hud_clock": {
                boolean f24 = bool(mod, "format_24h", true);
                boolean sec = bool(mod, "show_sec", false);
                String val = f24 ? (sec ? cachedClock24Sec : cachedClock24) : cachedClock12;
                drawStandardBadge(mod, x, y, s, val, accentCol, textCol, shadow, true, alphaMul);
                return;
            }
            case "hud_game_time": {
                long wt = mc.theWorld != null ? (mc.theWorld.getWorldTime() % 24000L) : 6000L;
                int totalMin = (int) (((wt + 6000L) % 24000L) * 60L / 1000L);
                if (totalMin != cachedGameTimeMin) {
                    HUD_FMT_SB.setLength(0);
                    appendTwoDigits(HUD_FMT_SB, totalMin / 60);
                    HUD_FMT_SB.append(':');
                    appendTwoDigits(HUD_FMT_SB, totalMin % 60);
                    cachedGameTimeText = HUD_FMT_SB.toString();
                    cachedGameTimeMin = totalMin;
                }
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, en ? "World" : "Monde", cachedGameTimeText, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_memory": {
                String val = mode(mod, "display", 0) == 0 ? ("RAM " + cachedRamPct + "%") : cachedRamMbText;
                drawStandardBadge(mod, x, y, s, val, accentCol, textCol, shadow, true, alphaMul);
                return;
            }
            case "hud_frametime": {
                int tenths = Math.round(smoothedFrameMs * 10.0F);
                if (tenths != cachedFrameMsTenths) {
                    cachedFrameMsText = formatFixed1(smoothedFrameMs, null, " ms");
                    cachedFrameMsTenths = tenths;
                }
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Frame", cachedFrameMsText, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_fps_graph": {
                renderGraphWidget(mod, x, y, s, "FPS", fps + " FPS", FPS_HISTORY, num(mod, "target_fps", 360.0F), accentCol, textCol, shadow, alphaMul);
                return;
            }
            case "hud_cps_graph": {
                renderGraphWidget(mod, x, y, s, "CPS", (lCps + rCps) + " CPS", CPS_HISTORY, num(mod, "max_cps", 20.0F), accentCol, textCol, shadow, alphaMul);
                return;
            }
            case "hud_ping_graph": {
                String pv = cachedPingMs >= 0 ? (cachedPingMs + " ms") : "Solo";
                renderGraphWidget(mod, x, y, s, "Ping", pv, PING_HISTORY, num(mod, "ceil_ms", 200.0F), accentCol, textCol, shadow, alphaMul);
                return;
            }
            case "hud_server_ip": {
                String ip = (mc.getCurrentServerData() != null && mc.getCurrentServerData().serverIP != null)
                        ? mc.getCurrentServerData().serverIP
                        : (en ? "Singleplayer" : "Mode Solo");
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "IP", ip, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_tps": {
                int tenths = Math.round(estimatedServerTps * 10.0F);
                if (tenths != cachedTpsTenths) {
                    cachedTpsText = formatFixed1(estimatedServerTps, null, " TPS");
                    cachedTpsTenths = tenths;
                }
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, en ? "Server" : "Serveur", cachedTpsText, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_pack_display": {
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Pack", cachedPackName, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_biome": {
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Biome", cachedBiomeName, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_held_item": {
                ItemStack held = mc.thePlayer != null ? mc.thePlayer.getHeldItem() : null;
                if (held == null && preview) {
                    held = getSampleSword();
                }
                if (held == null) {
                    mod.setBaseHudSize(96.0F, 22.0F);
                    return;
                }
                boolean showName = bool(mod, "show_name", true);
                String nm = showName ? stripFormatting(held.getDisplayName()) : "";
                float txtW = showName ? measureText(mc, nm) + 8.0F : 0.0F;
                float bw = 24.0F + txtW;
                float bh = 22.0F;
                mod.setBaseHudSize(bw, bh);
                drawHudCard(mod, x, y, bw, bh, s, alphaMul);
                queueItem(held, x + 4.0F * s, y + 3.0F * s, s, true);
                if (showName) {
                    queueText(nm, x + 23.0F * s, y + 7.0F * s, s, textCol, shadow, false);
                }
                return;
            }
            case "hud_arrow_count":
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, en ? "Arrows" : "Flèches", String.valueOf(invArrows), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            case "hud_pot_count":
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Pots", String.valueOf(invHealPots), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            case "hud_gapple_count":
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Gapples", String.valueOf(invGapples), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            case "hud_pearl_count":
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, en ? "Pearls" : "Perles", String.valueOf(invPearls), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            case "hud_soup_count":
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, en ? "Soups" : "Soupes", String.valueOf(invSoups), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            case "hud_totem_block":
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, en ? "Blocks" : "Blocs", String.valueOf(invBlocks), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            case "hud_fireball_count":
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, en ? "Explosives" : "Explosifs", String.valueOf(invFireballs), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            case "hud_bw_resources": {
                String txt = "Fe " + invIron + (en ? "  \u2022  Au " : "  \u2022  Or ") + invGold + "  \u2022  Di " + invDiamond + "  \u2022  Em " + invEmerald;
                drawStandardBadge(mod, x, y, s, txt, accentCol, textCol, shadow, true, alphaMul);
                return;
            }
            case "hud_session_timer": {
                long sec = Math.max(0L, (nowMs - SESSION_START_MS) / 1000L);
                String val = formatDurationSec(sec);
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Session", val, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_stopwatch": {
                long totalMs = stopwatchAccumulatedMs + (stopwatchRunning ? (nowMs - stopwatchLastStartMs) : 0L);
                long sec = totalMs / 1000L;
                int tenth = (int) ((totalMs % 1000L) / 100L);
                HUD_FMT_SB.setLength(0);
                HUD_FMT_SB.append(en ? "Timer: " : "Chrono: ");
                appendTwoDigits(HUD_FMT_SB, sec / 60L);
                HUD_FMT_SB.append(':');
                appendTwoDigits(HUD_FMT_SB, sec % 60L);
                HUD_FMT_SB.append('.').append(tenth);
                drawStandardBadge(mod, x, y, s, HUD_FMT_SB.toString(), stopwatchRunning ? accentCol : mulAlpha(0xFF64748B, alphaMul), textCol, shadow, true, alphaMul);
                return;
            }
            case "hud_pitch_yaw": {
                float yaw = mc.thePlayer != null ? MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw) : 0.0F;
                float pitch = mc.thePlayer != null ? mc.thePlayer.rotationPitch : 0.0F;
                HUD_FMT_SB.setLength(0);
                HUD_FMT_SB.append(Math.round(yaw)).append("\u00b0 / ").append(Math.round(pitch)).append('\u00b0');
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Angles", HUD_FMT_SB.toString(), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_light_level": {
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, en ? "Light" : "Lumière", String.valueOf(cachedLightLevel), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_saturation": {
                float sat = mc.thePlayer != null ? mc.thePlayer.getFoodStats().getSaturationLevel() : 20.0F;
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Saturation", formatFixed1(sat, null, null), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_absorption": {
                float abs = mc.thePlayer != null ? mc.thePlayer.getAbsorptionAmount() * 0.5F : 0.0F;
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Absorption", formatFixed1(abs, "+", null), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_xp_info": {
                int lvl = mc.thePlayer != null ? mc.thePlayer.experienceLevel : 0;
                int pct = mc.thePlayer != null ? Math.round(mc.thePlayer.experience * 100.0F) : 0;
                drawStandardBadge(mod, x, y, s, (en ? "XP Lv." : "XP Niv.") + lvl + " (" + pct + "%)", accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_day_counter": {
                long day = mc.theWorld != null ? (mc.theWorld.getWorldTime() / 24000L) : 0L;
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, en ? "Day" : "Jour", String.valueOf(day), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_chunk_coords": {
                int bx = mc.thePlayer != null ? MathHelper.floor_double(mc.thePlayer.posX) : 0;
                int bz = mc.thePlayer != null ? MathHelper.floor_double(mc.thePlayer.posZ) : 0;
                String val = "[" + (bx >> 4) + ", " + (bz >> 4) + "] (" + (bx & 15) + "," + (bz & 15) + ")";
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Chunk", val, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_entity_count": {
                int total = mc.theWorld != null ? mc.theWorld.loadedEntityList.size() : 0;
                int culled = PureFPSConfig.culledEntitiesLastFrame;
                drawStandardBadge(mod, x, y, s, (en ? "Entities: " : "Entités: ") + total + " (-" + culled + ")", accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_vbo_stats": {
                String val = PureFPSConfig.chunksBatchedLastFrame + "c -> " + PureFPSConfig.multiDrawsLastFrame + (en ? " batches" : " lots");
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "GPU", val, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_cps_ratio": {
                String val = (rCps <= 0) ? (lCps + ":0") : formatFixed1((double) lCps / (double) rCps, null, ":1");
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Ratio", val, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_hit_counter": {
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, en ? "Hits" : "Coups", String.valueOf(sessionHitCount), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_damage_taken": {
                String val = formatFixed1(lastDamageHearts, "-", en ? " hearts" : " cœurs");
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Impact", val, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_target_hud": {
                renderTargetHudWidget(mc, mod, x, y, s, nowMs, accentCol, textCol, shadow, preview, alphaMul);
                return;
            }
            case "hud_mouse_stroker": {
                float bw = 50.0F;
                float bh = 50.0F;
                mod.setBaseHudSize(bw, bh);
                drawHudCard(mod, x, y, bw, bh, s, alphaMul);
                float cx = x + bw * 0.5F * s;
                float cy = y + bh * 0.5F * s;
                NanoUiRenderer.drawLine(x + 6.0F * s, cy, x + (bw - 6.0F) * s, cy, 0.7F * s, mulAlpha(0x28FFFFFF, alphaMul));
                NanoUiRenderer.drawLine(cx, y + 6.0F * s, cx, y + (bh - 6.0F) * s, 0.7F * s, mulAlpha(0x28FFFFFF, alphaMul));
                float amp = num(mod, "sens_vis", 1.0F);
                float dx = clamp(mouseRadarX * amp, -18.0F, 18.0F) * s;
                float dy = clamp(mouseRadarY * amp, -18.0F, 18.0F) * s;
                NanoUiRenderer.drawCircle(cx + dx, cy + dy, 3.2F * s, accentCol);
                return;
            }
            case "hud_wasd_mini": {
                float bw = 44.0F;
                float bh = 30.0F;
                mod.setBaseHudSize(bw, bh);
                drawHudCard(mod, x, y, bw, bh, s, alphaMul);
                boolean kw = mc.gameSettings.keyBindForward.isKeyDown();
                boolean ka = mc.gameSettings.keyBindLeft.isKeyDown();
                boolean ks = mc.gameSettings.keyBindBack.isKeyDown();
                boolean kd = mc.gameSettings.keyBindRight.isKeyDown();
                int off = mulAlpha(0x44949CB0, alphaMul);
                NanoUiRenderer.drawRoundedRect(x + 17.0F * s, y + 4.0F * s, 10.0F * s, 10.0F * s, 2.5F * s, kw ? accentCol : off, 0, 0.0F);
                NanoUiRenderer.drawRoundedRect(x + 5.0F * s, y + 16.0F * s, 10.0F * s, 10.0F * s, 2.5F * s, ka ? accentCol : off, 0, 0.0F);
                NanoUiRenderer.drawRoundedRect(x + 17.0F * s, y + 16.0F * s, 10.0F * s, 10.0F * s, 2.5F * s, ks ? accentCol : off, 0, 0.0F);
                NanoUiRenderer.drawRoundedRect(x + 29.0F * s, y + 16.0F * s, 10.0F * s, 10.0F * s, 2.5F * s, kd ? accentCol : off, 0, 0.0F);
                return;
            }
            case "hud_sneak_badge": {
                boolean snk = (mc.thePlayer != null && mc.thePlayer.isSneaking()) || (MashFlags.toggleSneakEnabled && MashFlags.sneakerActive);
                drawStandardBadge(mod, x, y, s, snk ? (en ? "Sneaking \u2022 Bridge" : "Accroupi \u2022 Pont") : (en ? "Standing" : "Debout"), snk ? accentCol : mulAlpha(0xFF64748B, alphaMul), textCol, shadow, true, alphaMul);
                return;
            }
            case "hud_fly_speed": {
                float fs = mc.thePlayer != null ? mc.thePlayer.capabilities.getFlySpeed() * 20.0F : 1.0F;
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, en ? "Fly" : "Vol", formatFixed1(fs, null, "x"), false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_packet_rate": {
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, "Client", "20.0 TPS", false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_weather_badge": {
                String w = en ? "Clear" : "Clair";
                if (mc.theWorld != null) {
                    if (mc.theWorld.isThundering()) w = en ? "Storm" : "Orage";
                    else if (mc.theWorld.isRaining()) w = en ? "Rain" : "Pluie";
                }
                if (MashFlags.weatherChangerEnabled) {
                    w = MashFlags.weatherMode == 0 ? (en ? "Clear" : "Clair") : (MashFlags.weatherMode == 1 ? (en ? "Rain" : "Pluie") : (en ? "Storm" : "Orage"));
                }
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, en ? "Weather" : "Météo", w, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_moon_phase": {
                int phase = mc.theWorld != null ? mc.theWorld.getMoonPhase() : 0;
                String[] names = en ? MOON_PHASES_EN : MOON_PHASES_FR;
                drawStandardBadge(mod, x, y, s, names[phase & 7], accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_durability_helmet":
                drawArmorSlotDuraBadge(mc, mod, 3, en ? "Helmet" : "Casque", x, y, s, accentCol, textCol, shadow, alphaMul);
                return;
            case "hud_durability_chest":
                drawArmorSlotDuraBadge(mc, mod, 2, en ? "Chestplate" : "Plastron", x, y, s, accentCol, textCol, shadow, alphaMul);
                return;
            case "hud_durability_legs":
                drawArmorSlotDuraBadge(mc, mod, 1, en ? "Leggings" : "Jambières", x, y, s, accentCol, textCol, shadow, alphaMul);
                return;
            case "hud_durability_boots":
                drawArmorSlotDuraBadge(mc, mod, 0, en ? "Boots" : "Bottes", x, y, s, accentCol, textCol, shadow, alphaMul);
                return;
            case "hud_weapon_dura": {
                ItemStack held = mc.thePlayer != null ? mc.thePlayer.getHeldItem() : null;
                String val = (held != null && held.isItemStackDamageable())
                        ? String.valueOf(held.getMaxDamage() - held.getItemDamage())
                        : "--";
                drawStandardBadge(mod, x, y, s, formatBadgeText(mod, en ? "Weapon" : "Arme", val, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
                return;
            }
            case "hud_hotbar_mirror": {
                float bw = 184.0F;
                float bh = 22.0F;
                mod.setBaseHudSize(bw, bh);
                drawHudCard(mod, x, y, bw, bh, s, alphaMul);
                int sel = mc.thePlayer != null ? mc.thePlayer.inventory.currentItem : 0;
                if (bool(mod, "highlight_slot", true)) {
                    NanoUiRenderer.drawRoundedRect(x + (3.0F + sel * 20.0F) * s, y + 2.0F * s, 18.0F * s, 18.0F * s, 4.0F * s, mulAlpha(0x28F5B85C, alphaMul), accentCol, 1.2F);
                }
                if (mc.thePlayer != null) {
                    for (int slot = 0; slot < 9; slot++) {
                        ItemStack st = mc.thePlayer.inventory.mainInventory[slot];
                        if (st != null) {
                            queueItem(st, x + (4.0F + slot * 20.0F) * s, y + 3.0F * s, s, true);
                        }
                    }
                }
                return;
            }
            case "hud_inventory_grid": {
                float bw = 166.0F;
                float bh = 58.0F;
                mod.setBaseHudSize(bw, bh);
                drawHudCard(mod, x, y, bw, bh, s, alphaMul);
                boolean counts = bool(mod, "show_counts", true);
                if (mc.thePlayer != null) {
                    for (int row = 0; row < 3; row++) {
                        for (int col = 0; col < 9; col++) {
                            ItemStack st = mc.thePlayer.inventory.mainInventory[9 + row * 9 + col];
                            if (st != null) {
                                queueItem(st, x + (3.0F + col * 18.0F) * s, y + (3.0F + row * 18.0F) * s, s, counts);
                            }
                        }
                    }
                }
                return;
            }
            case "hud_pearl_cooldown": {
                float dur = num(mod, "duration", 15.0F);
                float rem = Math.max(0.0F, dur - (nowMs - lastPearlThrowMs) * 0.001F);
                if (!preview && rem <= 0.05F) {
                    return;
                }
                String val = rem > 0.05F
                        ? formatFixed1(rem, en ? "Pearl: " : "Perle: ", "s")
                        : (en ? "Pearl: Ready" : "Perle: Prête");
                drawStandardBadge(mod, x, y, s, val, accentCol, textCol, shadow, true, alphaMul);
                return;
            }
            case "hud_gapple_cooldown": {
                float dur = num(mod, "duration", 5.0F);
                float rem = Math.max(0.0F, dur - (nowMs - lastGappleEatMs) * 0.001F);
                if (!preview && rem <= 0.05F) {
                    return;
                }
                String val = rem > 0.05F
                        ? formatFixed1(rem, en ? "Regen: " : "Régén: ", "s")
                        : (en ? "Regen: Ready" : "Régén: Prête");
                drawStandardBadge(mod, x, y, s, val, accentCol, textCol, shadow, true, alphaMul);
                return;
            }
            case "hud_custom_label":
            default: {
                int idx = mode(mod, "preset_label", 0);
                String val = CUSTOM_LABEL_PRESETS[Math.max(0, Math.min(CUSTOM_LABEL_PRESETS.length - 1, idx))];
                drawStandardBadge(mod, x, y, s, val, accentCol, textCol, shadow, true, alphaMul);
            }
        }
    }

    private static void drawArmorSlotDuraBadge(
            Minecraft mc,
            MashModule mod,
            int slot,
            String label,
            float x,
            float y,
            float s,
            int accentCol,
            int textCol,
            boolean shadow,
            float alphaMul
    ) {
        ItemStack st = (mc.thePlayer != null) ? mc.thePlayer.inventory.armorInventory[slot] : null;
        String val = (st != null && st.isItemStackDamageable())
                ? String.valueOf(st.getMaxDamage() - st.getItemDamage())
                : "--";
        drawStandardBadge(mod, x, y, s, formatBadgeText(mod, label, val, false), accentCol, textCol, shadow, bool(mod, "dot_indicator", true), alphaMul);
    }

    private static void renderKeystrokesWidget(
            Minecraft mc,
            MashModule mod,
            float x,
            float y,
            float s,
            int lCps,
            int rCps,
            int textCol,
            boolean shadow,
            float alphaMul
    ) {
        boolean showMouse = bool(mod, "show_mouse", true);
        boolean cpsOnMouse = bool(mod, "show_cps_on_mouse", true);
        boolean showSpace = bool(mod, "show_space", true);

        float bw = 78.0F;
        float bh = 46.0F + (showMouse ? 20.0F : 0.0F) + (showSpace ? 17.0F : 0.0F);
        mod.setBaseHudSize(bw, bh);

        GameSettings gs = mc.gameSettings;
        boolean kw = isKeyBindDownRealtime(mc, gs.keyBindForward);
        boolean ka = isKeyBindDownRealtime(mc, gs.keyBindLeft);
        boolean ks = isKeyBindDownRealtime(mc, gs.keyBindBack);
        boolean kd = isKeyBindDownRealtime(mc, gs.keyBindRight);
        boolean kj = isKeyBindDownRealtime(mc, gs.keyBindJump);
        boolean ml = Mouse.isCreated() && mc.currentScreen == null && Mouse.isButtonDown(0);
        boolean mr = Mouse.isCreated() && mc.currentScreen == null && Mouse.isButtonDown(1);

        int pressedArgb = mulAlpha(col(mod, "pressed_col", System.currentTimeMillis(), 0xE6F5B85C), alphaMul);
        String nw = shortKeyLabel(gs.keyBindForward.getKeyCode(), "W");
        String na = shortKeyLabel(gs.keyBindLeft.getKeyCode(), "A");
        String ns = shortKeyLabel(gs.keyBindBack.getKeyCode(), "S");
        String nd = shortKeyLabel(gs.keyBindRight.getKeyCode(), "D");

        drawKeyCap(mod, x + 27.0F * s, y, 24.0F, 22.0F, s, kw, pressedArgb, nw, textCol, shadow, alphaMul);
        drawKeyCap(mod, x, y + 24.0F * s, 25.0F, 22.0F, s, ka, pressedArgb, na, textCol, shadow, alphaMul);
        drawKeyCap(mod, x + 27.0F * s, y + 24.0F * s, 24.0F, 22.0F, s, ks, pressedArgb, ns, textCol, shadow, alphaMul);
        drawKeyCap(mod, x + 53.0F * s, y + 24.0F * s, 25.0F, 22.0F, s, kd, pressedArgb, nd, textCol, shadow, alphaMul);

        float curY = 48.0F;
        if (showMouse) {
            String lTxt = cpsOnMouse ? (lCps + " CPS") : "LMB";
            String rTxt = cpsOnMouse ? (rCps + " CPS") : "RMB";
            drawKeyCap(mod, x, y + curY * s, 38.0F, 18.0F, s, ml, pressedArgb, lTxt, textCol, shadow, alphaMul);
            drawKeyCap(mod, x + 40.0F * s, y + curY * s, 38.0F, 18.0F, s, mr, pressedArgb, rTxt, textCol, shadow, alphaMul);
            curY += 20.0F;
        }
        if (showSpace) {
            if (kj) {
                NanoUiRenderer.drawRoundedRect(x, y + curY * s, 78.0F * s, 15.0F * s, 4.5F * s, pressedArgb, mulAlpha(0x66FFFFFF, alphaMul), 1.0F);
            } else {
                drawHudCard(mod, x, y + curY * s, 78.0F, 15.0F, s, alphaMul);
            }
            int barCol = kj ? mulAlpha(0xFF0A0B0E, alphaMul) : textCol;
            NanoUiRenderer.drawRoundedRect(x + 20.0F * s, y + (curY + 6.5F) * s, 38.0F * s, 2.0F * s, 1.0F * s, barCol, 0, 0.0F);
        }
    }

    private static void drawKeyCap(
            MashModule mod,
            float x,
            float y,
            float w,
            float h,
            float s,
            boolean down,
            int pressedArgb,
            String label,
            int textCol,
            boolean shadow,
            float alphaMul
    ) {
        if (down) {
            NanoUiRenderer.drawRoundedRect(x, y, w * s, h * s, mod.hudCornerRadius.get() * s, pressedArgb, mulAlpha(0x66FFFFFF, alphaMul), 1.0F);
            queueText(label, x + w * 0.5F * s, y + (h - 8.0F) * 0.5F * s, s, mulAlpha(0xFF0A0B0E, alphaMul), false, true);
        } else {
            drawHudCard(mod, x, y, w, h, s, alphaMul);
            queueText(label, x + w * 0.5F * s, y + (h - 8.0F) * 0.5F * s, s, textCol, shadow, true);
        }
    }

    private static boolean isKeyBindDownRealtime(Minecraft mc, net.minecraft.client.settings.KeyBinding kb) {
        if (kb == null) {
            return false;
        }
        if (mc != null && mc.currentScreen == null) {
            return dev.purefps.client.ClientEventBridge.isKeybindPhysicallyDown(kb.getKeyCode()) || kb.isKeyDown();
        }
        return kb.isKeyDown();
    }

    private static void renderArmorWidget(
            Minecraft mc,
            MashModule mod,
            float x,
            float y,
            float s,
            int textCol,
            boolean shadow,
            boolean preview,
            float alphaMul
    ) {
        boolean vertical = mode(mod, "layout", 0) == 1;
        int duraMode = mode(mod, "dura_mode", 0);
        boolean incHand = bool(mod, "include_hand", false);

        ItemStack[] armor = (mc.thePlayer != null) ? mc.thePlayer.inventory.armorInventory : null;
        boolean hasAny = false;
        if (armor != null) {
            for (int i = 0; i < 4; i++) {
                if (armor[i] != null) {
                    hasAny = true;
                    break;
                }
            }
        }
        if (!hasAny && preview) {
            armor = getSampleArmor();
            hasAny = true;
        }
        if (!hasAny) {
            mod.setBaseHudSize(vertical ? 54.0F : 86.0F, vertical ? 78.0F : 22.0F);
            return;
        }

        int slots = incHand ? 5 : 4;
        if (!vertical) {
            float bw = slots * 20.0F + 6.0F;
            float bh = duraMode == 0 ? 22.0F : 30.0F;
            mod.setBaseHudSize(bw, bh);
            drawHudCard(mod, x, y, bw, bh, s, alphaMul);
            int idx = 0;
            for (int i = 3; i >= 0; i--) {
                ItemStack st = armor[i];
                if (st != null) {
                    float ix = x + (4.0F + idx * 20.0F) * s;
                    queueItem(st, ix, y + 3.0F * s, s, duraMode == 0);
                    if (duraMode > 0 && st.isItemStackDamageable()) {
                        String dt = formatItemDura(st, duraMode);
                        queueText(dt, ix + 8.0F * s, y + 20.5F * s, s * 0.85F, textCol, shadow, true);
                    }
                }
                idx++;
            }
            if (incHand) {
                ItemStack hand = mc.thePlayer != null ? mc.thePlayer.getHeldItem() : getSampleSword();
                if (hand != null) {
                    queueItem(hand, x + (4.0F + 4 * 20.0F) * s, y + 3.0F * s, s, true);
                }
            }
        } else {
            float bw = duraMode == 0 ? 24.0F : 58.0F;
            float bh = slots * 19.0F + 4.0F;
            mod.setBaseHudSize(bw, bh);
            drawHudCard(mod, x, y, bw, bh, s, alphaMul);
            int idx = 0;
            for (int i = 3; i >= 0; i--) {
                ItemStack st = armor[i];
                if (st != null) {
                    float iy = y + (3.0F + idx * 19.0F) * s;
                    queueItem(st, x + 4.0F * s, iy, s, duraMode == 0);
                    if (duraMode > 0 && st.isItemStackDamageable()) {
                        queueText(formatItemDura(st, duraMode), x + 23.0F * s, iy + 5.0F * s, s, textCol, shadow, false);
                    }
                }
                idx++;
            }
        }
    }

    private static String formatItemDura(ItemStack st, int mode) {
        int rem = Math.max(0, st.getMaxDamage() - st.getItemDamage());
        if (mode == 2 && st.getMaxDamage() > 0) {
            return (rem * 100 / st.getMaxDamage()) + "%";
        }
        return String.valueOf(rem);
    }

    private static void renderPotionsWidget(
            Minecraft mc,
            MashModule mod,
            float x,
            float y,
            float s,
            long nowMs,
            int accentCol,
            int textCol,
            boolean shadow,
            boolean preview,
            float alphaMul
    ) {
        Collection<PotionEffect> effects = (mc.thePlayer != null) ? mc.thePlayer.getActivePotionEffects() : null;
        boolean compact = bool(mod, "compact", false);
        float rowH = compact ? 12.0F : 14.5F;

        if (effects == null || effects.isEmpty()) {
            if (!preview) {
                mod.setBaseHudSize(112.0F, 34.0F);
                return;
            }
            float bw = 116.0F;
            float bh = rowH * 2.0F + 8.0F;
            mod.setBaseHudSize(bw, bh);
            drawHudCard(mod, x, y, bw, bh, s, alphaMul);
            boolean en = PureFPSConfig.isEnglish();
            queueText(en ? "Speed II  \u2022  01:24" : "Rapidité II  \u2022  01:24", x + 7.0F * s, y + 5.0F * s, s, accentCol, shadow, false);
            queueText(en ? "Fire Resistance  \u2022  03:10" : "Résistance Feu  \u2022  03:10", x + 7.0F * s, y + (5.0F + rowH) * s, s, textCol, shadow, false);
            return;
        }

        int count = Math.min(6, effects.size());
        float maxW = 92.0F;
        String[] lines = new String[count];
        int idx = 0;
        boolean blink = bool(mod, "blink_low", true);
        for (PotionEffect pe : effects) {
            if (idx >= count) break;
            Potion pot = Potion.potionTypes[pe.getPotionID()];
            if (pot == null) continue;
            String name = I18n.format(pot.getName());
            if (pe.getAmplifier() == 1) name += " II";
            else if (pe.getAmplifier() == 2) name += " III";
            else if (pe.getAmplifier() >= 3) name += " " + (pe.getAmplifier() + 1);
            String dur = Potion.getDurationString(pe);
            String line = name + "  \u2022  " + dur;
            if (blink && pe.getDuration() < 160 && ((nowMs / 250L) & 1L) == 0L) {
                line = "\u00a7c" + line;
            }
            lines[idx++] = line;
            maxW = Math.max(maxW, measureText(mc, line) + 14.0F);
        }
        float bh = idx * rowH + 7.0F;
        mod.setBaseHudSize(maxW, bh);
        drawHudCard(mod, x, y, maxW, bh, s, alphaMul);
        for (int i = 0; i < idx; i++) {
            queueText(lines[i], x + 7.0F * s, y + (4.5F + i * rowH) * s, s, textCol, shadow, false);
        }
    }

    private static void renderCoordsWidget(
            Minecraft mc,
            MashModule mod,
            float x,
            float y,
            float s,
            int accentCol,
            int textCol,
            boolean shadow,
            float alphaMul
    ) {
        double px = mc.thePlayer != null ? mc.thePlayer.posX : 0.0D;
        double py = mc.thePlayer != null ? mc.thePlayer.posY : 64.0D;
        double pz = mc.thePlayer != null ? mc.thePlayer.posZ : 0.0D;
        int dec = Math.round(num(mod, "decimals", 0.0F));
        boolean showY = bool(mod, "show_y", true);
        int layout = mode(mod, "layout", 0);
        double qScale = dec <= 0 ? 1.0D : (dec == 1 ? 10.0D : 100.0D);
        long qx = Math.round(px * qScale);
        long qy = Math.round(py * qScale);
        long qz = Math.round(pz * qScale);

        if (qx != cachedCoordQx || qy != cachedCoordQy || qz != cachedCoordQz
                || dec != cachedCoordDec || showY != cachedCoordShowY || layout != cachedCoordLayout) {
            cachedCoordQx = qx;
            cachedCoordQy = qy;
            cachedCoordQz = qz;
            cachedCoordDec = dec;
            cachedCoordShowY = showY;
            cachedCoordLayout = layout;
            if (layout == 0) {
                HUD_FMT_SB.setLength(0);
                HUD_FMT_SB.append(showY ? "XYZ: " : "XZ: ");
                appendCoord(HUD_FMT_SB, px, dec);
                if (showY) {
                    HUD_FMT_SB.append("  ");
                    appendCoord(HUD_FMT_SB, py, dec);
                }
                HUD_FMT_SB.append("  ");
                appendCoord(HUD_FMT_SB, pz, dec);
                cachedCoordInline = HUD_FMT_SB.toString();
            } else {
                HUD_FMT_SB.setLength(0);
                HUD_FMT_SB.append("X: ");
                appendCoord(HUD_FMT_SB, px, dec);
                cachedCoordRowX = HUD_FMT_SB.toString();
                if (showY) {
                    HUD_FMT_SB.setLength(0);
                    HUD_FMT_SB.append("Y: ");
                    appendCoord(HUD_FMT_SB, py, dec);
                    cachedCoordRowY = HUD_FMT_SB.toString();
                }
                HUD_FMT_SB.setLength(0);
                HUD_FMT_SB.append("Z: ");
                appendCoord(HUD_FMT_SB, pz, dec);
                cachedCoordRowZ = HUD_FMT_SB.toString();
            }
        }

        if (layout == 0) {
            drawStandardBadge(mod, x, y, s, cachedCoordInline, accentCol, textCol, shadow, true, alphaMul);
        } else {
            int rows = showY ? 3 : 2;
            float bw = 88.0F;
            float bh = rows * 12.0F + 7.0F;
            mod.setBaseHudSize(bw, bh);
            drawHudCard(mod, x, y, bw, bh, s, alphaMul);
            queueText(cachedCoordRowX, x + 7.0F * s, y + 4.5F * s, s, textCol, shadow, false);
            if (showY) {
                queueText(cachedCoordRowY, x + 7.0F * s, y + 16.5F * s, s, textCol, shadow, false);
                queueText(cachedCoordRowZ, x + 7.0F * s, y + 28.5F * s, s, textCol, shadow, false);
            } else {
                queueText(cachedCoordRowZ, x + 7.0F * s, y + 16.5F * s, s, textCol, shadow, false);
            }
        }
    }

    private static void renderDirectionWidget(
            Minecraft mc,
            MashModule mod,
            float x,
            float y,
            float s,
            int accentCol,
            int textCol,
            boolean shadow,
            float alphaMul
    ) {
        float yaw = mc.thePlayer != null ? MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw) : 0.0F;
        float deg360 = (yaw + 180.0F + 360.0F) % 360.0F;
        String[] dirs = PureFPSConfig.isEnglish() ? DIRECTIONS_EN : DIRECTIONS_FR;
        int idx = Math.round(deg360 / 45.0F) & 7;
        boolean showDeg = bool(mod, "show_deg", true);

        if (mode(mod, "style", 0) == 1) {
            String txt = showDeg ? (dirs[idx] + "  \u2022  " + Math.round(deg360) + "\u00b0") : dirs[idx];
            drawStandardBadge(mod, x, y, s, txt, accentCol, textCol, shadow, true, alphaMul);
            return;
        }

        float bw = 122.0F;
        float bh = 21.0F;
        mod.setBaseHudSize(bw, bh);
        drawHudCard(mod, x, y, bw, bh, s, alphaMul);
        String left = dirs[(idx + 7) & 7];
        String mid = showDeg ? (dirs[idx] + " " + Math.round(deg360) + "\u00b0") : dirs[idx];
        String right = dirs[(idx + 1) & 7];
        queueText(left, x + 16.0F * s, y + 6.0F * s, s, mulAlpha(0xFF7D8599, alphaMul), shadow, true);
        queueText(mid, x + bw * 0.5F * s, y + 6.0F * s, s, accentCol, shadow, true);
        queueText(right, x + (bw - 16.0F) * s, y + 6.0F * s, s, mulAlpha(0xFF7D8599, alphaMul), shadow, true);
        NanoUiRenderer.drawCircle(x + bw * 0.5F * s, y + 2.5F * s, 1.5F * s, accentCol);
    }

    private static void renderGraphWidget(
            MashModule mod,
            float x,
            float y,
            float s,
            String title,
            String valueText,
            float[] ring,
            float maxScale,
            int accentCol,
            int textCol,
            boolean shadow,
            float alphaMul
    ) {
        float bw = 112.0F;
        float bh = 32.0F;
        mod.setBaseHudSize(bw, bh);
        drawHudCard(mod, x, y, bw, bh, s, alphaMul);
        queueText(title, x + 6.5F * s, y + 4.0F * s, s, mulAlpha(0xFF8E96AA, alphaMul), shadow, false);
        queueText(valueText, x + (bw - 6.0F) * s - measureText(Minecraft.getMinecraft(), valueText) * s, y + 4.0F * s, s, textCol, shadow, false);

        float gx0 = x + 6.0F * s;
        float gy0 = y + 15.0F * s;
        float gw = (bw - 12.0F) * s;
        float gh = 13.0F * s;
        float stepX = gw / (float) (GRAPH_LEN - 1);
        float ceil = Math.max(1.0F, maxScale);

        for (int i = 0; i < GRAPH_LEN; i++) {
            float v = ring[(graphCursor + i) % GRAPH_LEN];
            float frac = clamp(v / ceil, 0.04F, 1.0F);
            float barH = Math.max(1.2F * s, gh * frac);
            float bx = gx0 + i * stepX;
            NanoUiRenderer.drawRoundedRect(bx, gy0 + gh - barH, Math.max(1.2F * s, stepX * 0.72F), barH, 0.6F * s, accentCol, 0, 0.0F);
        }
    }

    private static void renderTargetHudWidget(
            Minecraft mc,
            MashModule mod,
            float x,
            float y,
            float s,
            long nowMs,
            int accentCol,
            int textCol,
            boolean shadow,
            boolean preview,
            float alphaMul
    ) {
        EntityLivingBase target = (lastTargetEntity != null && !lastTargetEntity.isDead && (nowMs - lastTargetSeenMs <= 3500L))
                ? lastTargetEntity
                : null;
        if (target == null && !preview) {
            mod.setBaseHudSize(128.0F, 34.0F);
            return;
        }

        boolean en = PureFPSConfig.isEnglish();
        String name = target != null ? stripFormatting(target.getDisplayName().getFormattedText()) : (en ? "PvP Opponent" : "Adversaire PvP");
        if (MashFlags.nickHiderEnabled && mc.thePlayer != null && name.contains(mc.thePlayer.getName())) {
            name = name.replace(mc.thePlayer.getName(), MashFlags.nickReplacement);
        }
        float hp = target != null ? target.getHealth() : 15.4F;
        float maxHp = target != null ? Math.max(1.0F, target.getMaxHealth()) : 20.0F;
        float dist = (target != null && mc.thePlayer != null) ? mc.thePlayer.getDistanceToEntity(target) : 2.84F;

        float bw = 132.0F;
        float bh = 34.0F;
        mod.setBaseHudSize(bw, bh);
        drawHudCard(mod, x, y, bw, bh, s, alphaMul);

        queueText(name, x + 8.0F * s, y + 5.0F * s, s, textCol, shadow, false);
        HUD_FMT_SB.setLength(0);
        appendFixed1(HUD_FMT_SB, hp);
        HUD_FMT_SB.append(en ? " HP" : " PV");
        if (bool(mod, "show_dist", true)) {
            HUD_FMT_SB.append("  \u2022  ");
            appendFixed1(HUD_FMT_SB, dist);
            HUD_FMT_SB.append('m');
        }
        queueText(HUD_FMT_SB.toString(), x + 8.0F * s, y + 15.0F * s, s, mulAlpha(0xFF949CB0, alphaMul), shadow, false);

        if (bool(mod, "show_hearts", true)) {
            float barW = (bw - 16.0F) * s;
            float frac = clamp(hp / maxHp, 0.0F, 1.0F);
            NanoUiRenderer.drawRoundedRect(x + 8.0F * s, y + 26.0F * s, barW, 4.0F * s, 2.0F * s, mulAlpha(0x88141822, alphaMul), 0, 0.0F);
            if (frac > 0.01F) {
                NanoUiRenderer.drawRoundedRect(x + 8.0F * s, y + 26.0F * s, Math.max(4.0F * s, barW * frac), 4.0F * s, 2.0F * s, accentCol, 0, 0.0F);
            }
        }
    }

    private static void drawStandardBadge(
            MashModule mod,
            float x,
            float y,
            float s,
            String text,
            int dotArgb,
            int textArgb,
            boolean shadow,
            boolean showDot,
            float alphaMul
    ) {
        Minecraft mc = Minecraft.getMinecraft();
        float textW = measureText(mc, text);
        float leftPad = showDot ? 15.5F : 7.5F;
        float bw = Math.max(46.0F, leftPad + textW + 7.5F);
        float bh = 19.0F;
        mod.setBaseHudSize(bw, bh);

        drawHudCard(mod, x, y, bw, bh, s, alphaMul);
        if (showDot) {
            NanoUiRenderer.drawCircle(x + 8.0F * s, y + bh * 0.5F * s, 2.1F * s, dotArgb);
        }
        queueText(text, x + leftPad * s, y + 5.5F * s, s, textArgb, shadow, false);
    }

    private static void drawHudCard(MashModule mod, float x, float y, float bw, float bh, float s, float alphaMul) {
        if (!mod.hudShowBg.get()) {
            return;
        }
        int baseAlpha = Math.round(mod.hudBgOpacity.get() * 2.55F * alphaMul);
        baseAlpha = Math.max(0, Math.min(255, baseAlpha));
        int topAlpha = Math.min(255, Math.round(baseAlpha * 1.04F));
        int bgTopArgb = (topAlpha << 24) | 0x10141F;
        int bgBotArgb = (baseAlpha << 24) | 0x090B12;
        int borderArgb = mod.hudShowBorder.get() ? mulAlpha(0x28FFFFFF, alphaMul) : 0;
        float radius = mod.hudCornerRadius.get() * s;
        NanoUiRenderer.drawRoundedGradient(
                x, y, bw * s, bh * s, radius,
                bgTopArgb, bgBotArgb, borderArgb,
                mod.hudShowBorder.get() ? 1.1F : 0.0F
        );
    }

    private static void renderCustomCrosshair(Minecraft mc, ScaledResolution sr, long nowMs) {
        float cx = sr.getScaledWidth_double() > 0 ? (float) (sr.getScaledWidth_double() * 0.5D) : sr.getScaledWidth() * 0.5F;
        float cy = sr.getScaledHeight_double() > 0 ? (float) (sr.getScaledHeight_double() * 0.5D) : sr.getScaledHeight() * 0.5F;

        int col = MashFlags.crosshairColor;
        if (MashFlags.crosshairChroma) {
            float h = (nowMs % 3200L) / 3200.0F;
            col = (col & 0xFF000000) | (Color.HSBtoRGB(h, 0.80F, 1.0F) & 0x00FFFFFF);
        }
        if (MashFlags.crosshairHighlightEntity && mc.objectMouseOver != null && mc.objectMouseOver.entityHit instanceof EntityLivingBase) {
            col = MashFlags.crosshairTargetColor;
        }

        float size = MashFlags.crosshairSize;
        float gap = MashFlags.crosshairGap;
        float thick = MashFlags.crosshairThickness;
        boolean outline = MashFlags.crosshairOutline;
        float outPx = outline ? MashFlags.crosshairOutlinePx * NanoUiRenderer.getScaleFactor() : 0.0F;
        int outCol = outline ? 0xDC050608 : 0;

        if (MashFlags.crosshairDynamicSpread && mc.thePlayer != null) {
            if (!mc.thePlayer.onGround) {
                gap += 2.2F;
            } else if (mc.thePlayer.isSprinting()) {
                gap += 1.0F;
            }
            if (mc.thePlayer.isUsingItem() && mc.thePlayer.getItemInUse() != null && mc.thePlayer.getItemInUse().getItem() instanceof ItemBow) {
                int used = mc.thePlayer.getItemInUseDuration();
                float pull = Math.min(1.0F, used / 20.0F);
                gap = Math.max(0.5F, gap + (1.0F - pull) * 3.5F - pull * 1.2F);
            }
        }

        int shape = MashFlags.crosshairShape;
        switch (shape) {
            case 0: // Croix
            case 1: // Croix + Point
                NanoUiRenderer.drawRoundedRect(cx - gap - size, cy - thick * 0.5F, size, thick, thick * 0.4F, col, outCol, outPx);
                NanoUiRenderer.drawRoundedRect(cx + gap, cy - thick * 0.5F, size, thick, thick * 0.4F, col, outCol, outPx);
                NanoUiRenderer.drawRoundedRect(cx - thick * 0.5F, cy - gap - size, thick, size, thick * 0.4F, col, outCol, outPx);
                NanoUiRenderer.drawRoundedRect(cx - thick * 0.5F, cy + gap, thick, size, thick * 0.4F, col, outCol, outPx);
                if (shape == 1) {
                    float dr = Math.max(1.0F, thick * 0.7F);
                    NanoUiRenderer.drawRoundedRect(cx - dr, cy - dr, dr * 2.0F, dr * 2.0F, dr, col, outCol, outPx);
                }
                break;
            case 2: { // Point seul
                float r = Math.max(1.2F, size * 0.45F);
                NanoUiRenderer.drawRoundedRect(cx - r, cy - r, r * 2.0F, r * 2.0F, r, col, outCol, outPx);
                break;
            }
            case 3: { // Cercle
                float r = Math.max(2.0F, size + gap * 0.5F);
                if (outline) {
                    NanoUiRenderer.drawRing(cx, cy, r, (thick + MashFlags.crosshairOutlinePx * 1.4F) * NanoUiRenderer.getScaleFactor(), outCol);
                }
                NanoUiRenderer.drawRing(cx, cy, r, thick * NanoUiRenderer.getScaleFactor(), col);
                break;
            }
            case 4: { // Chevron ^
                if (outline) {
                    NanoUiRenderer.drawLine(cx, cy, cx - size, cy + size, thick + 1.2F, outCol);
                    NanoUiRenderer.drawLine(cx, cy, cx + size, cy + size, thick + 1.2F, outCol);
                }
                NanoUiRenderer.drawLine(cx, cy, cx - size, cy + size, thick, col);
                NanoUiRenderer.drawLine(cx, cy, cx + size, cy + size, thick, col);
                break;
            }
            case 5: // Carré
            default: {
                float half = Math.max(2.0F, gap + size * 0.5F);
                if (outline) {
                    NanoUiRenderer.drawRoundedRect(cx - half, cy - half, half * 2.0F, half * 2.0F, 1.0F, 0x00000000, outCol, (thick + 1.2F) * NanoUiRenderer.getScaleFactor());
                }
                NanoUiRenderer.drawRoundedRect(cx - half, cy - half, half * 2.0F, half * 2.0F, 1.0F, 0x00000000, col, thick * NanoUiRenderer.getScaleFactor());
                break;
            }
        }
    }

    private static void renderBowChargeArc(EntityPlayer player, ScaledResolution sr) {
        if (!player.isUsingItem() || player.getItemInUse() == null || !(player.getItemInUse().getItem() instanceof ItemBow)) {
            return;
        }
        int used = player.getItemInUseDuration();
        float f = (float) used / 20.0F;
        f = (f * f + f * 2.0F) / 3.0F;
        if (f > 1.0F) f = 1.0F;
        if (f <= 0.05F) return;

        float cx = sr.getScaledWidth() * 0.5F;
        float cy = sr.getScaledHeight() * 0.5F + MashFlags.bowChargeRadius + 4.0F;
        float w = 24.0F;
        int barCol = f >= 0.99F ? 0xFF34D399 : (f >= 0.55F ? 0xFFF5B85C : 0xFFF87171);
        NanoUiRenderer.drawRoundedRect(cx - w * 0.5F, cy, w, 2.8F, 1.4F, 0xAA0A0D14, 0x33FFFFFF, 1.0F);
        NanoUiRenderer.drawRoundedRect(cx - w * 0.5F, cy, Math.max(2.8F, w * f), 2.8F, 1.4F, barCol, 0, 0.0F);
    }

    private static void renderCriticalHealthVignette(EntityPlayer player, ScaledResolution sr, long nowMs) {
        float hp = player.getHealth();
        if (hp > MashFlags.criticalHealthThreshold || hp <= 0.0F) {
            return;
        }
        float pulse = 0.55F + 0.45F * (float) Math.sin(nowMs * 0.008D);
        int alpha = Math.round(clamp((1.0F - hp / Math.max(1.0F, MashFlags.criticalHealthThreshold)) * 68.0F * pulse + 18.0F, 8.0F, 86.0F));
        int col = (alpha << 24) | 0xDC2626;
        float w = sr.getScaledWidth();
        float h = sr.getScaledHeight();
        NanoUiRenderer.drawRoundedGradient(0.0F, 0.0F, w, 18.0F, 0.0F, col, 0x00DC2626, 0, 0.0F);
        NanoUiRenderer.drawRoundedGradient(0.0F, h - 18.0F, w, 18.0F, 0.0F, 0x00DC2626, col, 0, 0.0F);
    }

    private static String formatBadgeText(MashModule mod, String prefix, String val, boolean suffixMode) {
        return formatBadgeTextDirect(prefix, val, suffixMode, bool(mod, "show_prefix", true), bool(mod, "brackets", false));
    }

    private static String formatBadgeTextDirect(
            String prefix,
            String val,
            boolean suffixMode,
            boolean showPrefix,
            boolean brackets
    ) {
        String core;
        if (!showPrefix) {
            core = val;
        } else if (suffixMode) {
            core = val + " " + prefix;
        } else {
            core = prefix + ": " + val;
        }
        return brackets ? ("[" + core + "]") : core;
    }

    private static float measureText(Minecraft mc, String text) {
        if (text == null || text.isEmpty()) {
            return 0.0F;
        }
        if (PureFPSConfig.smoothClientFont) {
            return SmoothFontRenderer.small().getStringWidth(text);
        }
        return mc != null && mc.fontRendererObj != null ? mc.fontRendererObj.getStringWidth(text) : text.length() * 5.5F;
    }

    private static void queueText(String text, float x, float y, float scale, int argb, boolean shadow, boolean centered) {
        if (text == null || text.isEmpty()) {
            return;
        }
        QueuedText qt;
        if (textQueueUsed < TEXT_QUEUE.size()) {
            qt = TEXT_QUEUE.get(textQueueUsed);
        } else {
            qt = new QueuedText();
            TEXT_QUEUE.add(qt);
        }
        qt.text = text;
        qt.x = x;
        qt.y = y;
        qt.scale = scale;
        qt.argb = argb;
        qt.shadow = shadow;
        qt.centered = centered;
        textQueueUsed++;
    }

    private static void queueItem(ItemStack stack, float x, float y, float scale, boolean overlay) {
        if (stack == null) {
            return;
        }
        QueuedItem qi;
        if (itemQueueUsed < ITEM_QUEUE.size()) {
            qi = ITEM_QUEUE.get(itemQueueUsed);
        } else {
            qi = new QueuedItem();
            ITEM_QUEUE.add(qi);
        }
        qi.stack = stack;
        qi.x = x;
        qi.y = y;
        qi.scale = scale;
        qi.overlay = overlay;
        itemQueueUsed++;
    }

    private static ItemStack getSampleSword() {
        if (sampleSword == null) {
            sampleSword = new ItemStack(Items.diamond_sword);
        }
        return sampleSword;
    }

    private static ItemStack[] getSampleArmor() {
        if (sampleArmor == null) {
            sampleArmor = new ItemStack[]{
                    new ItemStack(Items.diamond_boots),
                    new ItemStack(Items.diamond_leggings),
                    new ItemStack(Items.diamond_chestplate),
                    new ItemStack(Items.diamond_helmet)
            };
        }
        return sampleArmor;
    }

    private static String shortKeyLabel(int keyCode, String fallback) {
        try {
            String raw = GameSettings.getKeyDisplayString(keyCode);
            if (raw != null && raw.length() <= 3) {
                return raw.toUpperCase(Locale.ROOT);
            }
        } catch (Exception | LinkageError ignored) {
        }
        return fallback;
    }

    private static String formatDurationSec(long sec) {
        if (sec == cachedSessionSec) {
            return cachedSessionText;
        }
        long h = sec / 3600L;
        long m = (sec % 3600L) / 60L;
        long s = sec % 60L;
        HUD_FMT_SB.setLength(0);
        if (h > 0L) {
            HUD_FMT_SB.append(h).append(':');
            appendTwoDigits(HUD_FMT_SB, m);
            HUD_FMT_SB.append(':');
            appendTwoDigits(HUD_FMT_SB, s);
        } else {
            appendTwoDigits(HUD_FMT_SB, m);
            HUD_FMT_SB.append(':');
            appendTwoDigits(HUD_FMT_SB, s);
        }
        cachedSessionSec = sec;
        cachedSessionText = HUD_FMT_SB.toString();
        return cachedSessionText;
    }

    private static void appendTwoDigits(StringBuilder sb, long v) {
        if (v < 10L) {
            sb.append('0');
        }
        sb.append(v);
    }

    private static void appendFixed1(StringBuilder sb, double val) {
        long tenths = Math.round(val * 10.0D);
        if (tenths < 0L) {
            sb.append('-');
            tenths = -tenths;
        }
        sb.append(tenths / 10L).append('.').append(tenths % 10L);
    }

    private static void appendFixed2(StringBuilder sb, double val) {
        long hundredths = Math.round(val * 100.0D);
        if (hundredths < 0L) {
            sb.append('-');
            hundredths = -hundredths;
        }
        sb.append(hundredths / 100L).append('.');
        long frac = hundredths % 100L;
        if (frac < 10L) {
            sb.append('0');
        }
        sb.append(frac);
    }

    private static String formatFixed1(double val, String prefix, String suffix) {
        HUD_FMT_SB.setLength(0);
        if (prefix != null) {
            HUD_FMT_SB.append(prefix);
        }
        appendFixed1(HUD_FMT_SB, val);
        if (suffix != null) {
            HUD_FMT_SB.append(suffix);
        }
        return HUD_FMT_SB.toString();
    }

    private static void appendCoord(StringBuilder sb, double val, int decimals) {
        if (decimals <= 0) {
            sb.append(Math.round(val));
        } else if (decimals == 1) {
            appendFixed1(sb, val);
        } else {
            appendFixed2(sb, val);
        }
    }

    private static String stripFormatting(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int idx = s.indexOf('\u00a7');
        if (idx < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < s.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean bool(MashModule m, String id, boolean def) {
        MashSetting s = m.findSetting(id);
        return (s instanceof MashSetting.BooleanSetting) ? ((MashSetting.BooleanSetting) s).get() : def;
    }

    private static float num(MashModule m, String id, float def) {
        MashSetting s = m.findSetting(id);
        return (s instanceof MashSetting.NumberSetting) ? ((MashSetting.NumberSetting) s).get() : def;
    }

    private static int mode(MashModule m, String id, int def) {
        MashSetting s = m.findSetting(id);
        return (s instanceof MashSetting.ModeSetting) ? ((MashSetting.ModeSetting) s).getIndex() : def;
    }

    private static int col(MashModule m, String id, long nowMs, int def) {
        MashSetting s = m.findSetting(id);
        return (s instanceof MashSetting.ColorSetting) ? ((MashSetting.ColorSetting) s).resolveArgb(nowMs, 0) : def;
    }

    private static int mulAlpha(int argb, float factor) {
        if (factor >= 0.995F) {
            return argb;
        }
        int a = (argb >>> 24) & 0xFF;
        int na = Math.max(0, Math.min(255, Math.round(a * factor)));
        return (na << 24) | (argb & 0x00FFFFFF);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static int resolvePing(Minecraft mc) {
        if (mc == null || mc.isSingleplayer() || mc.thePlayer == null || mc.getNetHandler() == null) {
            return -1;
        }
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        int rawPing = info != null ? info.getResponseTime() : 0;
        return RealPingEstimator.resolveEffectivePing(mc, rawPing);
    }
}

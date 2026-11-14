package dev.purefps.module;

import org.lwjgl.input.Keyboard;

/**
 * Champs primitifs statiques mis en cache par MashModules.syncRuntimeFlags() :
 * garantit un accès O(1) zéro-allocation dans tous les Mixins de rendu et boucles de ticks !
 */
public final class MashFlags {

    // Curseur personnalisé (pvp_crosshair)
    public static boolean customCrosshair = false;
    public static int crosshairShape = 0; // 0=Croix, 1=Croix+Point, 2=Point, 3=Cercle, 4=Chevron, 5=Carré
    public static float crosshairSize = 4.5F;
    public static float crosshairGap = 2.5F;
    public static float crosshairThickness = 1.2F;
    public static boolean crosshairOutline = true;
    public static float crosshairOutlinePx = 1.0F;
    public static int crosshairColor = 0xFFFFFFFF;
    public static boolean crosshairChroma = false;
    public static boolean crosshairHighlightEntity = true;
    public static int crosshairTargetColor = 0xFFF87171;
    public static boolean crosshairDynamicSpread = false;
    public static boolean crosshairShowInThirdPerson = false;

    // Champ de vision PvP (pvp_fov)
    public static boolean pvpFovEnabled = false;
    public static boolean pvpFovOverrideBase = false;
    public static float pvpFovBase = 90.0F;
    public static boolean pvpFovDynamic = true;
    public static float pvpFovSpeed1Mult = 1.10F;
    public static float pvpFovSpeed2Mult = 1.20F;
    public static float pvpFovSprintBoost = 0.08F;
    public static float pvpFovBowZoom = 0.85F;
    public static float pvpFovFlyMult = 1.05F;
    public static boolean pvpFovIgnoreSlowness = true;

    // Zoom optique (pvp_zoom)
    public static boolean zoomEnabled = true;
    public static int zoomKeyCode = Keyboard.KEY_C;
    public static float zoomInitialDivisor = 4.0F;
    public static boolean zoomScrollEnabled = true;
    public static float zoomScrollStep = 1.18F;
    public static float zoomMaxDivisor = 18.0F;
    public static boolean zoomSmoothTransition = true;
    public static float zoomTransitionSpeed = 18.0F;
    public static boolean zoomSmoothCamera = false;
    public static float zoomSensitivityPct = 65.0F;

    // Toggle Sprint & Sneak (pvp_toggle_sprint)
    public static boolean toggleSprintEnabled = true;
    public static boolean toggleSneakEnabled = false;
    public static boolean sneakerActive = false;

    // Feu Bas, HurtCam & Viewmodel (pvp_low_fire, pvp_no_hurtcam, pvp_viewmodel)
    public static boolean lowFireEnabled = true;
    public static float lowFireOffset = -0.26F;
    public static boolean hurtCamCustom = false;
    public static float hurtCamIntensity = 0.0F; // 0.0 = désactivé, 1.0 = Vanilla
    public static boolean viewmodelEnabled = false;
    public static float viewmodelX = 0.0F;
    public static float viewmodelY = 0.0F;
    public static float viewmodelZ = 0.0F;
    public static float viewmodelScale = 1.0F;

    // Hit Color & Hitboxes
    public static boolean hitColorEnabled = false;
    public static int hitColorArgb = 0x55FF2B2B;
    public static boolean hitColorChroma = false;
    public static boolean bowChargeIndicator = false;
    public static boolean criticalHealthVignette = false;
    public static float criticalHealthThreshold = 6.0F;

    // Freelook 360° (caméra locale uniquement, 0 paquet serveur modifié !)
    public static boolean freelookEnabled = false;
    public static int freelookKeyCode = Keyboard.KEY_LMENU;
    public static boolean freelookInvertY = false;
    public static boolean freelookActive = false;
    public static float freelookYaw = 0.0F;
    public static float freelookPitch = 0.0F;

    // Visuel (Fullbright, Time/Weather, Scoreboard, Tab Ping, Chat, TNT Timer, Block Overlay)
    public static boolean fullbrightEnabled = true;
    public static float fullbrightStrength = 100.0F;
    public static boolean timeChangerEnabled = false;
    public static long customWorldTime = 6000L;
    public static boolean weatherChangerEnabled = false;
    public static int weatherMode = 0; // 0=Clair, 1=Pluie, 2=Orage
    public static boolean tabPingNumbers = true;
    public static boolean scoreboardHideNumbers = false;
    public static int scoreboardOffsetY = 0;
    public static boolean scoreboardHidden = false;
    public static boolean chatTransparent = false;
    public static boolean chatTimestamps = false;
    public static boolean nickHiderEnabled = false;
    public static String nickReplacement = "MashPlayer";
    public static boolean muteUnfocusedAudio = false;
    public static boolean uhcGoldScale = false;
    public static float uhcGoldScaleFactor = 1.35F;

    // TNT Timer 3D (vis_tnt_timer)
    public static boolean tntTimerEnabled = true;
    public static boolean tntTimerDynamicColor = true;
    public static int tntTimerFuseMode = 0; // 0=Auto (BedWars 2.6s / Vanilla 4.0s), 1=Vanilla 4.0s, 2=BedWars 2.6s

    // Curseur OpenGL de menu (vis_menu_cursor — contournement matériel DirectFlip / LWJGL 2.9.4)
    public static boolean menuCursorEnabled = true;
    public static int menuCursorStyle = 0; // 0=Flèche Système, 1=Flèche Mash, 2=Diamant, 3=Croix
    public static float menuCursorScale = 1.0F;
    public static int menuCursorColor = 0xFFFFFFFF;
    public static boolean menuCursorChroma = false;
    public static int menuCursorOutlineColor = 0xFF0A0D14;
    public static boolean menuCursorShadow = true;
    public static boolean menuCursorClickPulse = false;

    private MashFlags() {
    }
}

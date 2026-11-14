package dev.purefps.module;

import java.util.HashMap;
import java.util.Map;

/**
 * Catalogue bilingue officiel des 200 modules MashClient 4.1 :
 * associe chaque identifiant de module à son nom officiel anglais (Lunar / Badlion / PvP)
 * ainsi qu'à ses synonymes anglais courants pour une recherche instantanée FR + EN.
 */
public final class MashEnglishCatalog {

    public static final class Entry {
        public final String englishName;
        public final String englishAliases;

        Entry(String englishName, String englishAliases) {
            this.englishName = englishName;
            this.englishAliases = englishAliases;
        }
    }

    private static final Map<String, Entry> ENTRIES = new HashMap<>(256);

    static {
        // =====================================================================
        // 1. 65 HUD MODULES
        // =====================================================================
        put("hud_watermark", "Watermark", "client logo brand badge title hud");
        put("hud_fps", "FPS Counter", "fps display frame rate frames per second");
        put("hud_cps", "CPS Counter", "cps display clicks per second click counter lmb rmb");
        put("hud_ping", "Ping Display", "ping hud latency ms connection");
        put("hud_sprint", "Toggle Sprint HUD", "sprint status sneak status sprinting hud");
        put("hud_keystrokes", "Keystrokes", "wasd keys mouse buttons spacebar key strokes");
        put("hud_armor", "Armor Status", "armour status armor hud equipment durability");
        put("hud_potions", "Potion Effects", "potion status active effects buffs debuffs");
        put("hud_coords", "Coordinates", "coords xyz position location");
        put("hud_direction", "Direction HUD", "compass cardinal north south east west");
        put("hud_reach", "Reach Display", "reach counter hit distance attack range");
        put("hud_combo", "Combo Counter", "combo display hit streak pvp combo");
        put("hud_speed", "Speedometer", "movement speed bps blocks per second velocity");
        put("hud_clock", "Clock", "real time irl clock local time");
        put("hud_game_time", "Game Time", "world time ingame clock minecraft time");
        put("hud_memory", "Memory Usage", "ram display ram counter heap jvm memory");
        put("hud_frametime", "Frametime", "frame time render ms smoothness");
        put("hud_fps_graph", "FPS Graph", "framerate chart frame graph");
        put("hud_cps_graph", "CPS Graph", "click graph clicks chart");
        put("hud_server_ip", "Server Address", "server ip server display current server");
        put("hud_tps", "Server TPS", "tick rate server lag tps display");
        put("hud_pack_display", "Pack Display", "resource pack texture pack overlay");
        put("hud_biome", "Biome Display", "current biome world biome");
        put("hud_held_item", "Held Item", "item tracker main hand equipped item");
        put("hud_arrow_count", "Arrow Counter", "arrows bow ammo quiver");
        put("hud_pot_count", "Potion Counter", "pot counter splash heal potions nodebuff pots");
        put("hud_gapple_count", "Golden Apple Counter", "gapple counter gapples gold apples");
        put("hud_pearl_count", "Ender Pearl Counter", "pearl counter enderpearls pearls");
        put("hud_soup_count", "Soup Counter", "mushroom stew soups kitpvp hg");
        put("hud_totem_block", "Block Counter", "wool counter blocks rush bridge bedwars blocks");
        put("hud_fireball_count", "Fireball & TNT Counter", "fireballs tnt explosives bedwars");
        put("hud_bw_resources", "BedWars Resources", "iron gold diamond emerald counter ores");
        put("hud_session_timer", "Session Timer", "playtime play time session duration");
        put("hud_stopwatch", "Stopwatch", "manual timer speedrun timer");
        put("hud_pitch_yaw", "Pitch & Yaw", "camera angles rotation f3 angles");
        put("hud_light_level", "Light Level", "block brightness mob spawn light");
        put("hud_saturation", "Saturation", "food saturation hunger reserve");
        put("hud_absorption", "Absorption", "gold hearts absorption shield");
        put("hud_xp_info", "XP Progress", "experience level xp percent");
        put("hud_day_counter", "Day Counter", "world days survival day");
        put("hud_chunk_coords", "Chunk Coordinates", "chunk index chunk position");
        put("hud_entity_count", "Entity Counter", "rendered entities culled entities");
        put("hud_vbo_stats", "GPU MultiDraw Stats", "draw calls vbo batching chunks");
        put("hud_cps_ratio", "Click Ratio", "lmb rmb ratio attack place ratio");
        put("hud_hit_counter", "Hit Counter", "landed hits total attacks");
        put("hud_damage_taken", "Last Damage Taken", "damage indicator hearts lost");
        put("hud_target_hud", "Target HUD", "target info enemy health opponent hud");
        put("hud_mouse_stroker", "Mouse Strokes", "mouse radar aim tracker mouse movement");
        put("hud_wasd_mini", "Mini Keystrokes", "compact wasd mini keys");
        put("hud_sneak_badge", "Sneak Indicator", "crouch badge speedbridge ninja bridge");
        put("hud_fly_speed", "Fly Speed", "flight speed creative fly boost");
        put("hud_ping_graph", "Ping Graph", "latency chart network graph");
        put("hud_packet_rate", "Client Tick Rate", "client tps simulation rate");
        put("hud_weather_badge", "Weather Display", "rain thunder clear sky status");
        put("hud_moon_phase", "Moon Phase", "lunar cycle full moon slime chunk");
        put("hud_durability_helmet", "Helmet Durability", "head armor points helmet wear");
        put("hud_durability_chest", "Chestplate Durability", "chest armor points tunic wear");
        put("hud_durability_legs", "Leggings Durability", "pants armor points legs wear");
        put("hud_durability_boots", "Boots Durability", "shoes armor points feet wear");
        put("hud_weapon_dura", "Weapon Durability", "sword durability tool wear");
        put("hud_hotbar_mirror", "Hotbar Mirror", "movable hotbar actionbar mirror");
        put("hud_inventory_grid", "Inventory HUD", "inventory viewer backpack hud inv display");
        put("hud_pearl_cooldown", "Ender Pearl Cooldown", "pearl timer enderpearl cooldown");
        put("hud_gapple_cooldown", "Golden Apple Cooldown", "gapple timer regen timer");
        put("hud_custom_label", "Custom Text", "custom label custom tag text hud");

        // =====================================================================
        // 2. 30 PVP & CAMERA MODULES
        // =====================================================================
        put("pvp_crosshair", "Custom Crosshair", "crosshair reticle dot cross circle aim");
        put("pvp_fov", "FOV Changer", "custom fov dynamic fov speed fov quake pro field of view");
        put("pvp_zoom", "Zoom", "optifine zoom scroll zoom smooth zoom magnify");
        put("pvp_toggle_sprint", "Toggle Sprint", "togglesprint togglesneak toggle sneak autosprint sprint");
        put("pvp_hit_color", "Hit Color", "damage color hit armor tint hurt flash");
        put("pvp_low_fire", "Low Fire", "lower fire reduced fire flame height fire overlay");
        put("pvp_no_hurtcam", "No Hurt Cam", "hurtcam hurt camera screen shake damage tilt");
        put("pvp_viewmodel", "Item Viewmodel", "animations hand position sword scale item transform");
        put("pvp_old_animations", "1.7 Visuals", "old animations 1.7 animations blockhit sword block eating");
        put("pvp_hitbox", "Hitboxes", "hitbox f3+b bounding box eye line look vector");
        put("pvp_particles_mult", "Particle Multiplier", "more particles crit particles sharpness stars");
        put("pvp_freelook", "Freelook", "perspective mod 360 camera free look alt look");
        put("pvp_snaplook", "Snaplook", "rear view look behind instant third person");
        put("pvp_bow_charge_tint", "Bow Charge", "bow indicator arrow pull charge bar");
        put("pvp_shield_sword_pos", "Sword Block Position", "blocking angle lower sword guard");
        put("pvp_damage_tint", "Damage Tint", "low health vignette blood border critical hp");
        put("pvp_rod_tracer_vis", "Fishing Rod Bobber", "rod hook bobber scale");
        put("pvp_potion_glint_col", "Glint Color", "enchantment glint custom glint shiny");
        put("pvp_minimal_bobbing", "Minimal View Bobbing", "no screen bobbing steady crosshair view bob");
        put("pvp_tall_grass_clear", "Clean Tall Grass", "grass hitbox flower outline");
        put("pvp_sound_hit_confirm", "Hit Sound Pitch", "combat audio hit sound");
        put("pvp_team_marker", "Team View", "teammate color team marker allies");
        put("pvp_respawn_fast_ui", "Clean Death Screen", "game over tint respawn ui");
        put("pvp_cursor_lock_fix", "Raw Mouse Fix", "cursor jump fix inventory mouse reset");
        put("pvp_sprint_particles", "Hide Sprint Particles", "no footstep particles clean ground");
        put("pvp_bow_trajectory_fov", "Bow Aim Stabilizer", "archer sensitivity bow damp");
        put("pvp_hotbar_lock", "Slot Lock", "drop protection prevent sword drop lock slot");
        put("pvp_clean_water_overlay", "Clear Water", "underwater overlay clean water visibility");
        put("pvp_clean_pumpkin", "Clear Pumpkin", "no pumpkin blur transparent pumpkin overlay");
        put("pvp_low_shield_fire_ext", "Fire Extinguish Alert", "burn end indicator");

        // =====================================================================
        // 3. 35 VISUAL & UI MODULES
        // =====================================================================
        put("vis_fullbright", "Fullbright", "gamma brightness night vision dark caves");
        put("vis_block_overlay", "Block Outline", "block overlay selection box highlight block");
        put("vis_time_changer", "Time Changer", "timechanger custom time sunset night day");
        put("vis_weather_changer", "Weather Changer", "weatherchanger no rain clear sky storm");
        put("vis_nametags", "Nametags", "name tags own nametag third person nameplates");
        put("vis_scoreboard", "Scoreboard", "custom scoreboard hide red numbers sidebar");
        put("vis_bossbar", "Bossbar", "boss bar wither bar dragon bar");
        put("vis_tab_list", "Tab Editor", "tablist numeric ping ping numbers player list");
        put("vis_tnt_timer", "TNT Timer", "tnt countdown explosion fuse primed tnt");
        put("vis_item_physics", "Item Physics", "3d items dropped items spin ground items");
        put("vis_motion_blur", "Motion Blur", "camera blur smooth frame blur");
        put("vis_menu_blur", "Menu Blur", "gui background inventory blur container tint");
        put("vis_inv_shift_fix", "Centered Inventory", "no potion shift fix inventory offset");
        put("vis_scrollable_tooltips", "Scrollable Tooltips", "scroll lore item description skyblock");
        put("vis_clear_glass", "Clear Glass", "clean glass stained glass panes");
        put("vis_sky_color", "Custom Sky", "sky color atmosphere tint");
        put("vis_fog_customizer", "Fog Customizer", "no fog render fog distance fog");
        put("vis_hurt_flash_sky", "No Lightning Flash", "anti flash thunder sky");
        put("vis_void_tint", "Clean Void Horizon", "no black void skywars horizon");
        put("vis_glint_particles", "Enchant Particles", "portal particles rune density");
        put("vis_saturation_color", "UI Contrast", "vibrance crisp text");
        put("vis_vignette_ctrl", "No Vignette", "remove vignette dark screen corners");
        put("vis_titles_scale", "Titles", "title scale resize announcements subtitles");
        put("vis_hotbar_style", "Hotbar Numbers", "keybind numbers clean hotbar");
        put("vis_xp_bar_clean", "Clean XP Bar", "experience level color");
        put("vis_crosshair_f3", "F3 Crosshair", "debug axes scale");
        put("vis_rain_splash", "Minimal Rain", "rain splash droplets");
        put("vis_cloud_height", "Cloud Height", "raise clouds sky height");
        put("vis_cape_physics", "Smooth Capes", "cape physics cloak fps");
        put("vis_armor_glint_toggle", "Distant Armor Readability", "team leather glint");
        put("vis_bed_hide_plates", "Smart Sign Text", "sign text distance culling");
        put("vis_beacon_beam", "Beacon Beam", "hide beacon light ray");
        put("vis_falling_blocks", "Smooth Falling Blocks", "falling sand gravel anvil");
        put("vis_fireworks_ctrl", "Firework Limiter", "anti firework lag spark cap");
        put("vis_gui_font_hd", "Smooth Font", "custom font hd typography ttf");

        // =====================================================================
        // 4. 25 HYPIXEL & CHAT MODULES
        // =====================================================================
        put("hyp_chat_pro", "Chat", "custom chat transparent chat timestamps");
        put("hyp_autogg", "AutoGG", "auto gg good game karma");
        put("hyp_autotip_reminder", "AutoTip Tracker", "karma reminder network xp");
        put("hyp_nick_hider", "Nick Hider", "nickhider streamer mode hide name disguise");
        put("hyp_levelhead_tab", "LevelHead / Ping Color", "levelhead tab ping quality");
        put("hyp_bedwars_team_color", "BedWars Team Sync", "bedwars armor color match");
        put("hyp_uhc_overlay", "UHC Overlay", "gold ingot scale golden apple size");
        put("hyp_skywars_perk_timer", "SkyWars Strength Timer", "bulldozer perk kill buff");
        put("hyp_bridge_goal_alert", "Height Limit Overlay", "max build height warning bridge");
        put("hyp_chat_copy", "Copy Chat", "clipboard copy message");
        put("hyp_compact_join_leave", "Lobby Spam Filter", "hide join messages hub filter");
        put("hyp_friend_highlight", "Chat Mention", "name highlight ping alert");
        put("hyp_party_chat_badge", "Party & Guild Badges", "channel tags team chat");
        put("hyp_mystery_dust_mute", "Quiet Lobby", "mute hub explosions mystery box");
        put("hyp_bed_destroy_alert", "Bed Destroyed Alert", "bedwars title shift");
        put("hyp_sumo_border_warn", "Sumo Edge Alert", "void border duel warning");
        put("hyp_quick_play_menu", "QuickPlay", "quick play /lobby /rejoin");
        put("hyp_hide_lobby_npcs_tags", "Hologram Culling", "lobby armorstand nametags fps");
        put("hyp_kill_counter", "Kill Counter", "session kills tracker");
        put("hyp_winstreak_tracker", "Winstreak", "win streak tracker");
        put("hyp_chat_search", "Chat Filter", "keyword highlight chat search");
        put("hyp_anti_portal_trap_ui", "Portal Chat", "gui in nether portal");
        put("hyp_tablist_ping_sort_col", "Ping Thresholds", "custom ms color limits");
        put("hyp_guild_motd_clean", "Clean MOTD", "compact guild banner");
        put("hyp_gg_filter", "Compact Chat", "stack gg anti spam flood");

        // =====================================================================
        // 5. 20 WORLD, WINDOW & AUDIO MODULES
        // =====================================================================
        put("wld_borderless", "Borderless Fullscreen", "windowed fullscreen instant f11 alt tab");
        put("wld_mute_unfocused", "Mute Unfocused", "mute on alt tab background audio");
        put("wld_async_screenshots", "Async Screenshots", "fast screenshot lagless f2");
        put("wld_sound_limiter", "Sound Limiter", "tnt explosion audio cap");
        put("wld_footstep_vol", "Footstep Clarity", "enemy footsteps audio");
        put("wld_portal_sound_mute", "Quiet Portals", "mute nether portal noise");
        put("wld_wither_dragon_mute", "Boss Sound Limiter", "quiet dragon wither roar");
        put("wld_f3_enhancer", "Enhanced F3", "gpu telemetry debug screen");
        put("wld_waypoints_local", "Waypoints", "death coords last death location");
        put("wld_chunk_borders", "Chunk Borders", "chunk grid 16x16 lines");
        put("wld_lighting_debug", "Light Overlay", "mob spawn light level");
        put("wld_horse_stats", "Horse Stats", "mount speed jump inspection");
        put("wld_book_page_num", "Fast Book Pages", "keyboard book navigation");
        put("wld_sign_search", "Quick Sign Close", "fast sign editing");
        put("wld_disconnect_confirm", "Disconnect Confirm", "prevent accidental quit");
        put("wld_server_reconnect", "Auto Reconnect", "fast rejoin server");
        put("wld_memory_cleaner", "Memory Cleaner", "world switch cache purge");
        put("wld_custom_main_menu", "Singularity Main Menu", "black hole title screen");
        put("wld_discord_status_fmt", "Server Privacy", "hide numeric server ip");
        put("wld_auto_sprint_resume", "Inventory Move Sync", "resume keys after gui");

        // =====================================================================
        // 6. 25 ENGINE / FPS BOOST MODULES
        // =====================================================================
        put("eng_region_multidraw", "Region GPU MultiDraw", "chunk batching vbo ebo gl_triangles fps boost");
        put("eng_lazy_terrain_bfs", "Lazy Chunk Loading", "terrain bfs visibility graph cpu");
        put("eng_frustum_pvertex", "Fast Frustum Culling", "p-vertex aabb culling");
        put("eng_fbo_blit_gl30", "Hardware FBO Blit", "glblitframebuffer dma framebuffer");
        put("eng_no_gl_error", "No glGetError Stalls", "disable driver sync stalls");
        put("eng_smart_animations", "Smart Animations", "only visible animated textures water lava");
        put("eng_entity_culling", "Entity Culling", "raymarch occlusion culling behind walls");
        put("eng_item_frame_opt", "Fast Item Frames", "item frame fps fix map lag");
        put("eng_player_opt", "Player LOD", "distant players optimization lobby fps");
        put("eng_baked_items", "Fast Item Rendering", "precompiled 3d item models");
        put("eng_tile_culling", "TileEntity Culling", "chest bed skull frustum culling");
        put("eng_skull_cache", "Fast Skull Cache", "player head nbt uuid cache");
        put("eng_fast_font", "Fast Font Cache", "o1 string width cache");
        put("eng_fast_scoreboard_gpu", "GPU Scoreboard Batch", "2-quad sidebar batching");
        put("eng_particle_engine", "Particle Culling", "fast particles max particle limit");
        put("eng_armor_stand_opt", "Armor Stand Optimizer", "skip hologram physics");
        put("eng_biome_cache", "Biome Color Cache", "fast grass water foliage colors");
        put("eng_smooth_lighting_zero", "Zero-Alloc Smooth Lighting", "ambient occlusion gc fix");
        put("eng_quad_radix_sort", "Fast Translucent Sort", "primitive water glass sorting");
        put("eng_chunk_threads", "Multi-Core Chunk Threads", "parallel chunk compiler workers");
        put("eng_skin_pacing", "Skin Upload Pacing", "eliminate lobby skin microstutter");
        put("eng_distant_glint", "Distant Glint Culling", "cull far enchantment shine");
        put("eng_void_fog_skip", "Disable Void Fog Loop", "skip tick block sampling");
        put("eng_world_gc_guard", "Anti-Freeze GC Guard", "block system.gc world load freeze");
        put("eng_unfocused_saver", "Unfocused FPS Saver", "background frame rate cap");
    }

    private MashEnglishCatalog() {
    }

    private static void put(String id, String englishName, String englishAliases) {
        ENTRIES.put(id, new Entry(englishName, englishAliases));
    }

    public static Entry lookup(String moduleId) {
        return ENTRIES.get(moduleId);
    }
}

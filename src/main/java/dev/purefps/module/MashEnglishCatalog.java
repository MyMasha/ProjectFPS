package dev.purefps.module;

import java.util.HashMap;
import java.util.Map;

/**
 * Complete bilingual catalog (EN + FR) for all MashClient modules, descriptions,
 * sub-setting names, sub-setting descriptions, and mode options.
 */
public final class MashEnglishCatalog {

    public static final class Entry {
        public final String englishName;
        public final String englishAliases;
        public final String englishDesc;

        Entry(String englishName, String englishAliases, String englishDesc) {
            this.englishName = englishName;
            this.englishAliases = englishAliases;
            this.englishDesc = englishDesc;
        }
    }

    private static final Map<String, Entry> ENTRIES = new HashMap<>(256);
    private static final Map<String, String> SETTING_NAMES = new HashMap<>(256);
    private static final Map<String, String> SETTING_DESCS = new HashMap<>(256);
    private static final Map<String, String> MODE_OPTIONS = new HashMap<>(128);

    static {
        // =====================================================================
        // 1. 65 HUD MODULES
        // =====================================================================
        put("hud_watermark", "Watermark", "client logo brand badge title hud", "Displays the client watermark badge in the corner");
        put("hud_fps", "FPS Counter", "fps display frame rate frames per second", "Real-time frames per second counter");
        put("hud_cps", "CPS Counter", "cps display clicks per second click counter lmb rmb", "Left and right clicks per second counter");
        put("hud_ping", "Ping Display", "ping hud latency ms connection", "Server latency in milliseconds");
        put("hud_sprint", "Toggle Sprint HUD", "sprint status sneak status sprinting hud", "Shows active Toggle Sprint or Toggle Sneak state");
        put("hud_keystrokes", "Keystrokes", "wasd keys mouse buttons spacebar key strokes", "WASD keys, mouse buttons and spacebar overlay");
        put("hud_armor", "Armor Status", "armour status armor hud equipment durability", "Equipped armor pieces and remaining durability");
        put("hud_potions", "Potion Effects", "potion status active effects buffs debuffs", "Active potion effects and remaining duration");
        put("hud_coords", "Coordinates", "coords xyz position location", "Current X, Y and Z world coordinates");
        put("hud_direction", "Direction HUD", "compass cardinal north south east west", "Cardinal compass direction (N, E, S, W) and yaw angle");
        put("hud_reach", "Reach Display", "reach counter hit distance attack range", "Exact distance of your last landed hit");
        put("hud_combo", "Combo Counter", "combo display hit streak pvp combo", "Consecutive hits landed without taking damage");
        put("hud_speed", "Speedometer", "movement speed bps blocks per second velocity", "Movement speed in blocks per second");
        put("hud_clock", "Clock", "real time irl clock local time", "Local system time clock");
        put("hud_game_time", "Game Time", "world time ingame clock minecraft time", "In-game world day/night cycle time");
        put("hud_memory", "Memory Usage", "ram display ram counter heap jvm memory", "Active Java heap memory usage");
        put("hud_frametime", "Frametime", "frame time render ms smoothness", "Frame rendering duration in milliseconds");
        put("hud_fps_graph", "FPS Graph", "framerate chart frame graph", "Real-time FPS history graph");
        put("hud_cps_graph", "CPS Graph", "click graph clicks chart", "Real-time click rate graph");
        put("hud_server_ip", "Server Address", "server ip server display current server", "Connected server IP address");
        put("hud_tps", "Server TPS", "tick rate server lag tps display", "Estimated server ticks per second");
        put("hud_pack_display", "Pack Display", "resource pack texture pack overlay", "Currently active resource pack name");
        put("hud_biome", "Biome Display", "current biome world biome", "Biome at your current position");
        put("hud_held_item", "Held Item", "item tracker main hand equipped item", "Icon and durability of the item in main hand");
        put("hud_arrow_count", "Arrow Counter", "arrows bow ammo quiver", "Total arrows in your inventory");
        put("hud_pot_count", "Potion Counter", "pot counter splash heal potions nodebuff pots", "Remaining Splash Instant Heal potions");
        put("hud_gapple_count", "Golden Apple Counter", "gapple counter gapples gold apples", "Golden apples in your inventory");
        put("hud_pearl_count", "Ender Pearl Counter", "pearl counter enderpearls pearls", "Ender pearls in your inventory");
        put("hud_soup_count", "Soup Counter", "mushroom stew soups kitpvp hg", "Mushroom soups ready in your inventory");
        put("hud_totem_block", "Block Counter", "wool counter blocks rush bridge bedwars blocks", "Total placeable blocks in your inventory");
        put("hud_fireball_count", "Fireball & TNT Counter", "fireballs tnt explosives bedwars", "BedWars fireballs and TNT in your inventory");
        put("hud_bw_resources", "BedWars Resources", "iron gold diamond emerald counter ores", "Iron, gold, diamond and emerald count");
        put("hud_session_timer", "Session Timer", "playtime play time session duration", "Elapsed playtime since client launch");
        put("hud_stopwatch", "Stopwatch", "manual timer speedrun timer", "Keyboard-triggered manual stopwatch");
        put("hud_pitch_yaw", "Pitch & Yaw", "camera angles rotation f3 angles", "Camera Yaw and Pitch aim angles");
        put("hud_light_level", "Light Level", "block brightness mob spawn light", "Light level at current block (0-15)");
        put("hud_saturation", "Saturation", "food saturation hunger reserve", "Food saturation reserve level");
        put("hud_absorption", "Absorption", "gold hearts absorption shield", "Active golden absorption hearts");
        put("hud_xp_info", "XP Progress", "experience level xp percent", "Experience level and progress percentage");
        put("hud_day_counter", "Day Counter", "world days survival day", "Elapsed in-game world days");
        put("hud_chunk_coords", "Chunk Coordinates", "chunk index chunk position", "Chunk indices and local coordinates");
        put("hud_entity_count", "Entity Counter", "rendered entities culled entities", "Rendered and culled entity count");
        put("hud_vbo_stats", "GPU MultiDraw Stats", "draw calls vbo batching chunks", "Batched chunk draw calls sent to the GPU");
        put("hud_cps_ratio", "Click Ratio", "lmb rmb ratio attack place ratio", "Left-to-right click ratio");
        put("hud_hit_counter", "Hit Counter", "landed hits total attacks", "Total hits landed during this session");
        put("hud_damage_taken", "Last Damage Taken", "damage indicator hearts lost", "Hearts lost on the last hit taken");
        put("hud_target_hud", "Target HUD", "target info enemy health opponent hud", "Name, health and distance of your PvP target");
        put("hud_mouse_stroker", "Mouse Strokes", "mouse radar aim tracker mouse movement", "Visualizes real-time mouse aim movement");
        put("hud_wasd_mini", "Mini Keystrokes", "compact wasd mini keys", "Compact movement key indicators");
        put("hud_sneak_badge", "Sneak Indicator", "crouch badge speedbridge ninja bridge", "Shows active sneaking/bridging state");
        put("hud_fly_speed", "Fly Speed", "flight speed creative fly boost", "Creative or spectator flight speed multiplier");
        put("hud_ping_graph", "Ping Graph", "latency chart network graph", "Recent network latency graph");
        put("hud_packet_rate", "Client Tick Rate", "client tps simulation rate", "Client 20 TPS tick stability");
        put("hud_weather_badge", "Weather Display", "rain thunder clear sky status", "Active world weather status");
        put("hud_moon_phase", "Moon Phase", "lunar cycle full moon slime chunk", "Current lunar phase");
        put("hud_durability_helmet", "Helmet Durability", "head armor points helmet wear", "Remaining helmet durability");
        put("hud_durability_chest", "Chestplate Durability", "chest armor points tunic wear", "Remaining chestplate durability");
        put("hud_durability_legs", "Leggings Durability", "pants armor points legs wear", "Remaining leggings durability");
        put("hud_durability_boots", "Boots Durability", "shoes armor points feet wear", "Remaining boots durability");
        put("hud_weapon_dura", "Weapon Durability", "sword durability tool wear", "Durability of the held weapon or tool");
        put("hud_hotbar_mirror", "Hotbar Mirror", "movable hotbar actionbar mirror", "Mirrors the 9 hotbar slots anywhere on screen");
        put("hud_inventory_grid", "Inventory HUD", "inventory viewer backpack hud inv display", "Live preview of your 27 inventory slots");
        put("hud_pearl_cooldown", "Ender Pearl Cooldown", "pearl timer enderpearl cooldown", "Cooldown timer after throwing an Ender Pearl");
        put("hud_gapple_cooldown", "Golden Apple Cooldown", "gapple timer regen timer", "Remaining regeneration timer after eating a Golden Apple");
        put("hud_custom_label", "Custom Text", "custom label custom tag text hud", "Customizable text badge on screen");

        // =====================================================================
        // 2. 30 PVP & CAMERA MODULES
        // =====================================================================
        put("pvp_crosshair", "Custom Crosshair", "crosshair reticle dot cross circle aim", "Custom crosshair shape, size, gap, outline and target highlight");
        put("pvp_fov", "FOV Changer", "custom fov dynamic fov speed fov quake pro field of view", "Separate FOV control for base, Speed, sprint, bow and slowness");
        put("pvp_zoom", "Zoom", "optifine zoom scroll zoom smooth zoom magnify", "Smooth keybind zoom with optional scroll wheel magnification");
        put("pvp_toggle_sprint", "Toggle Sprint", "togglesprint togglesneak toggle sneak autosprint sprint", "Latches sprint or sneak with a single key press");
        put("pvp_hit_color", "Hit Color", "damage color hit armor tint hurt flash", "Customizes the damage flash tint on hit entities");
        put("pvp_low_fire", "Low Fire", "lower fire reduced fire flame height fire overlay", "Lowers the first-person fire overlay on screen");
        put("pvp_no_hurtcam", "No Hurt Cam", "hurtcam hurt camera screen shake damage tilt", "Reduces or disables camera shake when taking damage");
        put("pvp_viewmodel", "Item Viewmodel", "animations hand position sword scale item transform", "Adjusts XYZ offset and scale of the held item");
        put("pvp_old_animations", "1.7 Visuals", "old animations 1.7 animations blockhit sword block eating", "Classic 1.7 sword block-hit, bow and eating animations");
        put("pvp_hitbox", "Hitboxes", "hitbox f3+b bounding box eye line look vector", "Customizes entity collision bounding boxes (F3+B)");
        put("pvp_particles_mult", "Particle Multiplier", "more particles crit particles sharpness stars", "Multiplies Critical and Sharpness hit particles");
        put("pvp_freelook", "Freelook", "perspective mod 360 camera free look alt look", "360-degree camera rotation around the player without changing movement direction");
        put("pvp_snaplook", "Snaplook", "rear view look behind instant third person", "Instant rear-view perspective while holding the key");
        put("pvp_bow_charge_tint", "Bow Charge", "bow indicator arrow pull charge bar", "Displays a bow pull progress bar below the crosshair");
        put("pvp_shield_sword_pos", "Sword Block Position", "blocking angle lower sword guard", "Slightly lowers the sword when blocking");
        put("pvp_damage_tint", "Damage Tint", "low health vignette blood border critical hp", "Subtle red vignette warning when health is low");
        put("pvp_rod_tracer_vis", "Fishing Rod Bobber", "rod hook bobber scale", "Adjusts the size of the fishing rod bobber");
        put("pvp_potion_glint_col", "Glint Color", "enchantment glint custom glint shiny", "Custom color and intensity for enchantment glint");
        put("pvp_minimal_bobbing", "Minimal View Bobbing", "no screen bobbing steady crosshair view bob", "Keeps hand bobbing while stabilizing camera shake");
        put("pvp_tall_grass_clear", "Clean Tall Grass", "grass hitbox flower outline", "Hides tall grass selection outlines while holding a sword");
        put("pvp_sound_hit_confirm", "Hit Sound Pitch", "combat audio hit sound", "Adjusts audio pitch of combat hit sounds");
        put("pvp_team_marker", "Team View", "teammate color team marker allies", "Colors HUD indicators based on equipped leather armor");
        put("pvp_respawn_fast_ui", "Clean Death Screen", "game over tint respawn ui", "Reduces the red tint opacity on the death screen");
        put("pvp_cursor_lock_fix", "Raw Mouse Fix", "cursor jump fix inventory mouse reset", "Prevents camera jumps when closing inventories");
        put("pvp_sprint_particles", "Hide Sprint Particles", "no footstep particles clean ground", "Hides your own sprinting footstep particles in first person");
        put("pvp_bow_trajectory_fov", "Bow Aim Stabilizer", "archer sensitivity bow damp", "Smooths mouse sensitivity while drawing a bow");
        put("pvp_hotbar_lock", "Slot Lock", "drop protection prevent sword drop lock slot", "Requires double-pressing drop before throwing a weapon");
        put("pvp_clean_water_overlay", "Clear Water", "underwater overlay clean water visibility", "Reduces the blue underwater overlay opacity");
        put("pvp_clean_pumpkin", "Clear Pumpkin", "no pumpkin blur transparent pumpkin overlay", "Hides or reduces the carved pumpkin blur overlay");
        put("pvp_low_shield_fire_ext", "Fire Extinguish Alert", "burn end indicator", "Subtle visual cue when fire burning ends");

        // =====================================================================
        // 3. 35 VISUAL & UI MODULES
        // =====================================================================
        put("vis_fullbright", "Fullbright", "gamma brightness night vision dark caves", "Maximum brightness in caves and dark areas");
        put("vis_block_overlay", "Block Outline", "block overlay selection box highlight block", "Customizes the outline and fill of the targeted block");
        put("vis_time_changer", "Time Changer", "timechanger custom time sunset night day", "Locks client-side sky time locally");
        put("vis_weather_changer", "Weather Changer", "weatherchanger no rain clear sky storm", "Controls client-side rain and storm rendering");
        put("vis_nametags", "Nametags", "name tags own nametag third person nameplates", "Shows your own nametag in F5 and customizes nameplate backgrounds");
        put("vis_scoreboard", "Scoreboard", "custom scoreboard hide red numbers sidebar", "Hides red sidebar numbers and adjusts vertical offset");
        put("vis_bossbar", "Bossbar", "boss bar wither bar dragon bar", "Resizes or hides the boss health bar");
        put("vis_tab_list", "Tab Editor", "tablist numeric ping ping numbers player list", "Displays numeric ping in ms inside the Tab player list");
        put("vis_tnt_timer", "TNT Timer", "tnt countdown explosion fuse primed tnt", "Displays a countdown timer in seconds above primed TNT");
        put("vis_item_physics", "Item Physics", "3d items dropped items spin ground items", "Controls rotation speed of dropped items on the ground");
        put("vis_motion_blur", "Motion Blur", "camera blur smooth frame blur", "Subtle camera motion blur during fast turns");
        put("vis_menu_blur", "Menu Blur", "gui background inventory blur container tint", "Dims the background behind chests and inventories");
        put("vis_inv_shift_fix", "Centered Inventory", "no potion shift fix inventory offset", "Prevents active potion effects from shifting the inventory off-center");
        put("vis_scrollable_tooltips", "Scrollable Tooltips", "scroll lore item description skyblock", "Scrolls long item lore tooltips with the mouse wheel");
        put("vis_clear_glass", "Clear Glass", "clean glass stained glass panes", "Improves clarity of glass blocks at a distance");
        put("vis_sky_color", "Custom Sky", "sky color atmosphere tint", "Customizes the upper sky zenith color");
        put("vis_fog_customizer", "Fog Customizer", "no fog render fog distance fog", "Pushes back or removes distance fog");
        put("vis_hurt_flash_sky", "No Lightning Flash", "anti flash thunder sky", "Disables full-screen white sky flashes during thunderstorms");
        put("vis_void_tint", "Clean Void Horizon", "no black void skywars horizon", "Removes the black void horizon below Y=60 in SkyWars and BedWars");
        put("vis_glint_particles", "Enchant Particles", "portal particles rune density", "Reduces portal and enchantment table particle density");
        put("vis_saturation_color", "UI Contrast", "vibrance crisp text", "Adjusts UI text contrast and sharpness");
        put("vis_vignette_ctrl", "No Vignette", "remove vignette dark screen corners", "Disables dark screen corner vignette shading");
        put("vis_titles_scale", "Titles", "title scale resize announcements subtitles", "Scales down large center-screen server titles");
        put("vis_hotbar_style", "Hotbar Numbers", "keybind numbers clean hotbar", "Displays slot numbers 1 to 9 on the hotbar");
        put("vis_xp_bar_clean", "Clean XP Bar", "experience level color", "Customizes the experience level number color");
        put("vis_crosshair_f3", "F3 Crosshair", "debug axes scale", "Scales down the XYZ debug axes in F3 mode");
        put("vis_rain_splash", "Minimal Rain", "rain splash droplets", "Reduces rain splash particles on the ground");
        put("vis_cloud_height", "Cloud Height", "raise clouds sky height", "Raises the cloud layer altitude");
        put("vis_cape_physics", "Smooth Capes", "cape physics cloak fps", "Culls player capes beyond a configurable distance");
        put("vis_armor_glint_toggle", "Distant Armor Readability", "team leather glint", "Disables enchantment glint on distant armor for clearer team colors");
        put("vis_bed_hide_plates", "Smart Sign Text", "sign text distance culling", "Renders sign text only when close enough to read");
        put("vis_beacon_beam", "Beacon Beam", "hide beacon light ray", "Optionally hides the vertical beacon light beam");
        put("vis_falling_blocks", "Smooth Falling Blocks", "falling sand gravel anvil", "Optimizes rendering of falling sand and gravel");
        put("vis_fireworks_ctrl", "Firework Limiter", "anti firework lag spark cap", "Caps firework particle spawns to prevent lag");
        put("vis_gui_font_hd", "Smooth Font", "custom font hd typography ttf", "Uses anti-aliased vector font on HUD and client menus");
        put("vis_custom_pointer", "Custom Mouse Pointer", "menu cursor custom cursor mouse pointer arrow", "Replaces the system cursor with a custom vector pointer in menus");

        // =====================================================================
        // 4. 26 HYPIXEL & CHAT MODULES
        // =====================================================================
        put("hyp_chat_pro", "Chat", "custom chat transparent chat timestamps", "Transparent chat background, [HH:mm] timestamps and text shadow");
        put("hyp_chat_tabs", "Chat Channel Tabs (All/Team/Party)", "all team party chat tabs channel bar hypixel", "Displays clickable ALL, TEAM and PARTY channel tabs above the chat bar");
        put("hyp_autogg", "AutoGG", "auto gg good game karma", "Automatically sends GG at the end of a match");
        put("hyp_autotip_reminder", "AutoTip Tracker", "karma reminder network xp", "Subtle periodic reminder during long sessions");
        put("hyp_nick_hider", "Nick Hider", "nickhider streamer mode hide name disguise", "Replaces your username on screen, in chat, tab and nametags");
        put("hyp_levelhead_tab", "LevelHead / Ping Color", "levelhead tab ping quality", "Color-codes player ping in Tab based on latency");
        put("hyp_bedwars_team_color", "BedWars Team Sync", "bedwars armor color match", "Syncs HUD accents with your BedWars team armor color");
        put("hyp_uhc_overlay", "UHC Overlay", "gold ingot scale golden apple size", "Slightly enlarges dropped golden apples and gold ingots");
        put("hyp_skywars_perk_timer", "SkyWars Strength Timer", "bulldozer perk kill buff", "Highlights active Strength duration after a kill");
        put("hyp_bridge_goal_alert", "Height Limit Overlay", "max build height warning bridge", "Warns when approaching the maximum block build height");
        put("hyp_chat_copy", "Copy Chat", "clipboard copy message", "Cleanly copies chat messages to the clipboard");
        put("hyp_compact_join_leave", "Lobby Spam Filter", "hide join messages hub filter", "Hides noisy lobby join andmystery box announcements");
        put("hyp_friend_highlight", "Chat Mention", "name highlight ping alert", "Highlights chat messages mentioning your username");
        put("hyp_party_chat_badge", "Party & Guild Badges", "channel tags team chat", "Distinguishes Party, Team and Guild messages from All chat");
        put("hyp_mystery_dust_mute", "Quiet Lobby", "mute hub explosions mystery box", "Lowers cosmetic sound volume in lobbies");
        put("hyp_bed_destroy_alert", "Bed Destroyed Alert", "bedwars title shift", "Shifts the Bed Destroyed title away from your crosshair");
        put("hyp_sumo_border_warn", "Sumo Edge Alert", "void border duel warning", "Visual warning near the arena edge in Sumo and Bridge");
        put("hyp_quick_play_menu", "QuickPlay", "quick play /lobby /rejoin", "Quick shortcut for common lobby and rejoin commands");
        put("hyp_hide_lobby_npcs_tags", "Hologram Culling", "lobby armorstand nametags fps", "Limits render distance of floating hologram text in lobbies");
        put("hyp_kill_counter", "Kill Counter", "session kills tracker", "Tracks your kills during the current session");
        put("hyp_winstreak_tracker", "Winstreak", "win streak tracker", "Tracks your local match win streak");
        put("hyp_chat_search", "Chat Filter", "keyword highlight chat search", "Highlights targeted keywords in chat");
        put("hyp_anti_portal_trap_ui", "Portal Chat", "gui in nether portal", "Keeps chat and inventory usable while standing in a Nether portal");
        put("hyp_tablist_ping_sort_col", "Ping Thresholds", "custom ms color limits", "Sets the latency threshold where ping turns red");
        put("hyp_guild_motd_clean", "Clean MOTD", "compact guild banner", "Collapses consecutive blank lines on server join");
        put("hyp_gg_filter", "Compact Chat", "stack gg anti spam flood", "Stacks duplicate GG messages at the end of a round");

        // =====================================================================
        // 5. 20 WORLD, WINDOW & AUDIO MODULES
        // =====================================================================
        put("wld_borderless", "Borderless Fullscreen", "windowed fullscreen instant f11 alt tab", "Instant F11 toggle and Alt-Tab without black screen flashes");
        put("wld_mute_unfocused", "Mute Unfocused", "mute on alt tab background audio", "Mutes game audio when the window loses focus");
        put("wld_async_screenshots", "Async Screenshots", "fast screenshot lagless f2", "Saves F2 screenshots on a background thread with zero frame stutter");
        put("wld_sound_limiter", "Sound Limiter", "tnt explosion audio cap", "Prevents audio clipping during heavy TNT explosions");
        put("wld_footstep_vol", "Footstep Clarity", "enemy footsteps audio", "Adjusts relative footstep sound volume");
        put("wld_portal_sound_mute", "Quiet Portals", "mute nether portal noise", "Lowers ambient Nether portal noise");
        put("wld_wither_dragon_mute", "Boss Sound Limiter", "quiet dragon wither roar", "Reduces Wither and Ender Dragon roar volume");
        put("wld_f3_enhancer", "Enhanced F3", "gpu telemetry debug screen", "Adds GPU MultiDraw batch and culling counters to the F3 screen");
        put("wld_waypoints_local", "Waypoints", "death coords last death location", "Remembers the XYZ coordinates of your last survival death");
        put("wld_chunk_borders", "Chunk Borders", "chunk grid 16x16 lines", "Displays 16x16 chunk boundary lines around the player");
        put("wld_lighting_debug", "Light Overlay", "mob spawn light level", "Highlights blocks dark enough for hostile mobs to spawn");
        put("wld_horse_stats", "Horse Stats", "mount speed jump inspection", "Displays horse movement speed and jump height");
        put("wld_book_page_num", "Fast Book Pages", "keyboard book navigation", "Flips book pages using keyboard arrow keys");
        put("wld_sign_search", "Quick Sign Close", "fast sign editing", "Confirms and closes signs immediately with Escape");
        put("wld_disconnect_confirm", "Disconnect Confirm", "prevent accidental quit", "Prevents accidentally disconnecting from a server via Escape");
        put("wld_server_reconnect", "Auto Reconnect", "fast rejoin server", "Adds a Reconnect button to rejoin the last server");
        put("wld_memory_cleaner", "Memory Cleaner", "world switch cache purge", "Frees stale buffers when switching worlds without GC freezes");
        put("wld_custom_main_menu", "MashClient Main Menu", "black hole title screen custom menu", "3D Black Hole main menu and gravitational ambient sound");
        put("wld_discord_status_fmt", "Server Privacy", "hide numeric server ip", "Hides numeric private server IPs on HUD badges");
        put("wld_auto_sprint_resume", "Inventory Move Sync", "resume keys after gui", "Restores held WASD movement keys right after closing a GUI or chat");

        // =====================================================================
        // 6. 28 ENGINE / FPS BOOST MODULES
        // =====================================================================
        put("eng_region_multidraw", "Region GPU MultiDraw", "chunk batching vbo ebo gl_triangles fps boost", "Batches chunk terrain into indexed GPU MultiDraw regional arenas");
        put("eng_island_core_cull", "Island Core Unloading", "bedwars skywars hollow island buried blocks culling", "Skips hidden blocks buried inside BedWars and SkyWars islands");
        put("eng_smart_chunk_updates", "Smart Dynamic Chunks", "player blocks beds generators instant chunk updates", "Prioritizes player-placed blocks, beds and generators over distant terrain");
        put("eng_menu_world_unload", "Max Menu Unloading", "shop container pause menu world unload fps", "Reduces world rendering distance and effects while a menu is open");
        put("eng_lazy_terrain_bfs", "Lazy Chunk Loading", "terrain bfs visibility graph cpu", "Reuses visible chunk graph while the camera is steady");
        put("eng_frustum_pvertex", "Fast Frustum Culling", "p-vertex aabb culling", "Tests 1 positive vertex per bounding box instead of 8");
        put("eng_fbo_blit_gl30", "Hardware FBO Blit", "glblitframebuffer dma framebuffer", "Transfers the final framebuffer via hardware OpenGL 3.0 blit");
        put("eng_no_gl_error", "No glGetError Stalls", "disable driver sync stalls", "Eliminates blocking OpenGL error checks every frame");
        put("eng_smart_animations", "Smart Animations", "only visible animated textures water lava", "Animates water, lava and fire textures only when visible on screen");
        put("eng_entity_culling", "Entity Culling", "raymarch occlusion culling behind walls", "Culls entities that are too far away or hidden behind solid walls");
        put("eng_item_frame_opt", "Fast Item Frames", "item frame fps fix map lag", "Limits render distance and GPU cost of item frames");
        put("eng_player_opt", "Player LOD", "distant players optimization lobby fps", "Simplifies secondary skin and armor layers on distant lobby players");
        put("eng_baked_items", "Fast Item Rendering", "precompiled 3d item models", "Merges 3D item quad faces into a single cached draw list");
        put("eng_tile_culling", "TileEntity Culling", "chest bed skull frustum culling", "Culls off-screen and distant chests, beds and skulls");
        put("eng_skull_cache", "Fast Skull Cache", "player head nbt uuid cache", "Caches decorative player head skins on servers");
        put("eng_fast_font", "Fast Font Cache", "o1 string width cache", "Caches string widths for chat, Tab list and nametags");
        put("eng_fast_scoreboard_gpu", "GPU Scoreboard Batch", "2-quad sidebar batching", "Renders the scoreboard background in a single batched draw call");
        put("eng_particle_engine", "Particle Culling", "fast particles max particle limit", "Skips off-screen particles and caps active particles per layer");
        put("eng_armor_stand_opt", "Armor Stand Optimizer", "skip hologram physics", "Skips unnecessary physics lookups on hologram ArmorStands");
        put("eng_biome_cache", "Biome Color Cache", "fast grass water foliage colors", "Caches grass, foliage and water biome colors");
        put("eng_smooth_lighting_zero", "Zero-Alloc Smooth Lighting", "ambient occlusion gc fix", "Reuses thread-local ambient occlusion buffers with zero heap allocations");
        put("eng_quad_radix_sort", "Fast Translucent Sort", "primitive water glass sorting", "Sorts translucent water and glass geometry on primitive arrays");
        put("eng_chunk_threads", "Multi-Core Chunk Threads", "parallel chunk compiler workers", "Compiles chunk meshes in parallel across CPU cores");
        put("eng_skin_pacing", "Skin Upload Pacing", "eliminate lobby skin microstutter", "Uploads at most 1 player skin per frame to eliminate lobby micro-stutters");
        put("eng_distant_glint", "Distant Glint Culling", "cull far enchantment shine", "Disables enchantment glint rendering beyond 20 blocks");
        put("eng_void_fog_skip", "Disable Void Fog Loop", "skip tick block sampling", "Skips the 1,000 per-tick void fog particle checks");
        put("eng_world_gc_guard", "Anti-Freeze GC Guard", "block system.gc world load freeze", "Blocks forced System.gc() stop-the-world pauses on world switches");
        put("eng_unfocused_saver", "Unfocused FPS Saver", "background frame rate cap", "Caps frame rate when the game window is unfocused or minimized");

        // =====================================================================
        // 7. SETTING NAMES (FR -> EN)
        // =====================================================================
        putSettingName("Échelle", "Scale");
        putSettingName("Arrière-plan", "Background");
        putSettingName("Opacité du fond", "Background Opacity");
        putSettingName("Arrondi", "Corner Radius");
        putSettingName("Bordure", "Border");
        putSettingName("Ombre du texte", "Text Shadow");
        putSettingName("Couleur du texte", "Text Color");
        putSettingName("Couleur d'accent", "Accent Color");
        putSettingName("Afficher le libellé", "Show Label");
        putSettingName("Crochets", "Brackets");
        putSettingName("Point d'accent", "Accent Dot");
        putSettingName("Format", "Format");
        putSettingName("Afficher les FPS", "Show FPS Inline");
        putSettingName("Couleur selon FPS", "Dynamic FPS Color");
        putSettingName("Clic droit", "Right CPS");
        putSettingName("Seuil jaune", "Warning Threshold");
        putSettingName("Masquer à l'arrêt", "Hide When Idle");
        putSettingName("Boutons souris", "Mouse Buttons");
        putSettingName("CPS sur les boutons", "CPS on Buttons");
        putSettingName("Barre espace", "Spacebar");
        putSettingName("Couleur d'appui", "Pressed Color");
        putSettingName("Disposition", "Layout");
        putSettingName("Durabilité", "Durability Mode");
        putSettingName("Objet en main", "Include Held Item");
        putSettingName("Clignoter en fin", "Blink When Low");
        putSettingName("Mode compact", "Compact Mode");
        putSettingName("Décimales", "Decimals");
        putSettingName("Hauteur Y", "Show Y Axis");
        putSettingName("Angle en degrés", "Show Degrees");
        putSettingName("Style", "Style");
        putSettingName("Remise à zéro", "Reset Delay");
        putSettingName("Expiration", "Combo Timeout");
        putSettingName("Unité", "Speed Unit");
        putSettingName("Axe vertical", "Include Vertical Y");
        putSettingName("Format 24h", "24-Hour Format");
        putSettingName("Secondes", "Show Seconds");
        putSettingName("Plafond du graphe", "Graph Ceiling");
        putSettingName("Plafond CPS", "Max CPS Ceiling");
        putSettingName("Nom de l'objet", "Show Item Name");
        putSettingName("Masquer à zéro", "Hide Zero Counts");
        putSettingName("Démarrer / Pause", "Start / Pause");
        putSettingName("Barre de vie", "Health Bar");
        putSettingName("Distance", "Show Distance");
        putSettingName("Amplitude", "Sensitivity");
        putSettingName("Plafond ms", "Max Latency Ceiling");
        putSettingName("Encadrer le slot", "Highlight Active Slot");
        putSettingName("Quantités", "Show Stack Counts");
        putSettingName("Durée", "Duration");
        putSettingName("Texte", "Preset Label");
        putSettingName("Forme", "Shape");
        putSettingName("Taille", "Size");
        putSettingName("Écart central", "Center Gap");
        putSettingName("Épaisseur", "Thickness");
        putSettingName("Contour noir", "Dark Outline");
        putSettingName("Largeur contour", "Outline Width");
        putSettingName("Couleur", "Color");
        putSettingName("Couleur sur cible", "Highlight Target");
        putSettingName("Teinte sur cible", "Target Color");
        putSettingName("Écart dynamique", "Dynamic Spread");
        putSettingName("Visible en F5", "Show in Third Person");
        putSettingName("Remplacer le FOV", "Override Base FOV");
        putSettingName("FOV de base", "Base FOV");
        putSettingName("FOV dynamique", "Dynamic FOV");
        putSettingName("Speed I", "Speed I Multiplier");
        putSettingName("Speed II", "Speed II Multiplier");
        putSettingName("Boost sprint", "Sprint FOV Boost");
        putSettingName("Visée à l'arc", "Bow Zoom Factor");
        putSettingName("En vol", "Flying Multiplier");
        putSettingName("Ignorer Lenteur", "Ignore Slowness");
        putSettingName("Touche", "Keybind");
        putSettingName("Niveau initial", "Initial Zoom");
        putSettingName("Molette de zoom", "Scroll to Zoom");
        putSettingName("Pas de molette", "Scroll Step");
        putSettingName("Zoom max", "Max Zoom");
        putSettingName("Transition douce", "Smooth Transition");
        putSettingName("Vitesse", "Speed");
        putSettingName("Caméra lissée", "Cinematic Camera");
        putSettingName("Sensibilité", "Mouse Sensitivity");
        putSettingName("Sprint maintenu", "Toggle Sprint");
        putSettingName("Sneak maintenu", "Toggle Sneak");
        putSettingName("Actif en vol", "Keep While Flying");
        putSettingName("Colorer l'armure", "Tint Armor");
        putSettingName("Hauteur", "Height Offset");
        putSettingName("Masquer en Créatif", "Hide in Creative");
        putSettingName("Intensité", "Intensity");
        putSettingName("Position X", "X Offset");
        putSettingName("Position Y", "Y Offset");
        putSettingName("Profondeur Z", "Z Depth");
        putSettingName("Block-hit 1.7", "1.7 Block-Hit");
        putSettingName("Arc 1.7", "1.7 Bow");
        putSettingName("Nourriture 1.7", "1.7 Eating");
        putSettingName("Hauteur des yeux", "Eye Height Line");
        putSettingName("Direction du regard", "Look Vector");
        putSettingName("Multiplicateur", "Multiplier");
        putSettingName("Sharpness constant", "Always Sharpness");
        putSettingName("Inverser l'axe Y", "Invert Y Axis");
        putSettingName("Rayon", "Radius");
        putSettingName("Abaissement", "Lower Offset");
        putSettingName("Seuil", "Health Threshold");
        putSettingName("Opacité", "Opacity");
        putSettingName("Animer la main", "Keep Hand Swing");
        putSettingName("Épée uniquement", "Sword Only");
        putSettingName("Tonalité", "Pitch");
        putSettingName("Lire l'armure cuir", "Read Leather Armor");
        putSettingName("Centrage strict", "Exact Centering");
        putSettingName("1re personne seule", "First Person Only");
        putSettingName("Délai double appui", "Double-Press Delay");
        putSettingName("Clarté", "Water Clarity");
        putSettingName("Repère bleu", "Blue Cue");
        putSettingName("Couleur du trait", "Outline Color");
        putSettingName("Remplir les faces", "Fill Block Faces");
        putSettingName("Couleur des faces", "Fill Color");
        putSettingName("Horaire", "Time Preset");
        putSettingName("Ticks (0-24000)", "Custom Ticks (0-24000)");
        putSettingName("Météo", "Weather Mode");
        putSettingName("Mon pseudo en F5", "Show Own Tag in F5");
        putSettingName("Sans fond", "Transparent Background");
        putSettingName("Sans chiffres rouges", "Hide Red Numbers");
        putSettingName("Masquer tout", "Hide Entire Scoreboard");
        putSettingName("Affichage", "Display Mode");
        putSettingName("Ping en ms", "Numeric Ping (ms)");
        putSettingName("En-tête serveur", "Show Header/Footer");
        putSettingName("Couleur dynamique", "Dynamic Fuse Color");
        putSettingName("Délai TNT", "TNT Fuse Mode");
        putSettingName("Vitesse de rotation", "Rotation Speed");
        putSettingName("Afficher les effets", "Show Potion Badges");
        putSettingName("Verres teintés", "Include Stained Glass");
        putSettingName("Densité", "Fog Density");
        putSettingName("Garder l'éclair", "Keep 3D Lightning Bolt");
        putSettingName("Ciel continu", "Smooth Horizon");
        putSettingName("Quantité", "Particle Rate");
        putSettingName("Contraste", "Contrast");
        putSettingName("Alerte bordure", "Keep WorldBorder Warning");
        putSettingName("Numéros 1-9", "Show Slot Numbers 1-9");
        putSettingName("Décalage", "Height Offset");
        putSettingName("Distance max", "Max Distance");
        putSettingName("Distance de lecture", "Reading Distance");
        putSettingName("Masquer le rayon", "Hide Beam");
        putSettingName("Lumière directe", "Fast Lighting");
        putSettingName("Plafond", "Particle Cap");
        putSettingName("Actif sur le HUD", "Smooth HUD Font");
        putSettingName("Contour", "Outline Color");
        putSettingName("Ombre portée", "Drop Shadow");
        putSettingName("Onde au clic", "Click Pulse");
        putSettingName("Fond transparent", "Transparent Background");
        putSettingName("Horodatage [HH:mm]", "Timestamps [HH:mm]");
        putSettingName("Routage auto (/ac, /pc)", "Auto Route (/ac, /pc)");
        putSettingName("Filtrer par canal", "Filter Chat by Channel");
        putSettingName("Message", "GG Phrase");
        putSettingName("Délai", "Delay");
        putSettingName("Intervalle", "Interval");
        putSettingName("Alias", "Replacement Alias");
        putSettingName("Seuil vert", "Green Ping Threshold");
        putSettingName("Détection auto", "Auto-Detect Team");
        putSettingName("Marge", "Warning Margin");
        putSettingName("Sans codes couleur", "Strip Color Codes");
        putSettingName("Garder amis et groupe", "Keep Friends & Party");
        putSettingName("Préfixe marqué", "Bold Channel Prefix");
        putSettingName("Volume", "Volume");
        putSettingName("Décalage Y", "Vertical Y Offset");
        putSettingName("Commande", "Quick Command");
        putSettingName("Portée max", "Max Distance");
        putSettingName("Par partie", "Reset Every Match");
        putSettingName("Palier tous les 5", "Milestone Every 5 Wins");
        putSettingName("Réduire la distorsion", "Reduce Portal Wobble");
        putSettingName("Seuil rouge", "Red Ping Threshold");
        putSettingName("Fusionner les vides", "Collapse Blank Lines");
        putSettingName("F11 direct", "Instant Win32 F11");
        putSettingName("Volume hors focus", "Unfocused Volume");
        putSettingName("Lien dans le chat", "Clickable Chat Link");
        putSettingName("Plafond par son", "Max Simultaneous Sounds");
        putSettingName("Couleurs", "Colorized Values");
        putSettingName("Noter dans le chat", "Print Coords in Chat");
        putSettingName("Seuil lumière", "Light Threshold");
        putSettingName("Touches fléchées", "Arrow Key Navigation");
        putSettingName("Validation Échap", "Confirm with Escape");
        putSettingName("Multijoueur seul", "Multiplayer Only");
        putSettingName("Cache des têtes", "Purge Skull Cache");
        putSettingName("Parallaxe souris", "Mouse Parallax");
        putSettingName("Son du trou noir", "Black Hole Ambient Audio");
        putSettingName("Masquer IP chiffrées", "Hide Numeric IPs");
        putSettingName("Resynchroniser ZQSD", "Resync WASD Keys");
        putSettingName("Anti-fissures", "Anti-Seam Padding");
        putSettingName("Garder lits et coffres", "Preserve Beds & Chests");
        putSettingName("Lits et générateurs", "Priority Beds & Generators");
        putSettingName("Reflets en boutique", "Optimize Shop Glint");
        putSettingName("Seuil angulaire", "Angular Threshold");
        putSettingName("Chunks et entités", "Chunks & Entities");
        putSettingName("Rendu net", "Crisp Scaling");
        putSettingName("Désactiver en jeu", "Disable In-Game Polling");
        putSettingName("Ignorer hors champ", "Skip Off-Screen Animations");
        putSettingName("Cachées par les murs", "Wall Occlusion Culling");
        putSettingName("Distance détails", "Full Detail Distance");
        putSettingName("Passe unique", "Single-Pass Rendering");
        putSettingName("Cache partagé", "Shared Thread-Safe Cache");
        putSettingName("Cache des largeurs", "Fast Width Cache");
        putSettingName("Sans allocations", "Zero Allocations");
        putSettingName("Plafond par couche", "Max Particles per Layer");
        putSettingName("Sans collisions", "Skip Collision Lookups");
        putSettingName("Mélange direct", "Fast Biome Blending");
        putSettingName("Par thread", "Thread-Local Buffers");
        putSettingName("Tri sur place", "In-Place Sorting");
        putSettingName("Threads (0 = Auto)", "Worker Threads (0 = Auto)");
        putSettingName("1 skin / frame", "1 Skin Upload / Frame");
        putSettingName("Complet de près", "Full Glint Up Close");
        putSettingName("Tous les mondes", "All Worlds");
        putSettingName("Actif en Solo", "Include Singleplayer");
        putSettingName("FPS hors focus", "Unfocused FPS Cap");
        putSettingName("Routage auto (/pc, /ac)", "Auto Route (/pc, /ac)");

        // =====================================================================
        // 8. SETTING DESCRIPTIONS (FR -> EN)
        // =====================================================================
        putSettingDesc("Taille de l'élément à l'écran", "Widget size multiplier on screen");
        putSettingDesc("Fond sombre derrière le texte", "Dark card background behind text");
        putSettingDesc("Transparence du fond", "Background card opacity");
        putSettingDesc("Rayon des angles", "Card corner rounding radius");
        putSettingDesc("Contour fin autour du widget", "Thin border outline around the widget");
        putSettingDesc("Ombre portée sous les caractères", "Drop shadow under text characters");
        putSettingDesc("Couleur principale ou mode Chroma", "Primary color or rainbow Chroma mode");
        putSettingDesc("Couleur des repères du widget", "Widget accent indicator color");
        putSettingDesc("Affiche le nom à côté de la valeur", "Shows the label next to the value");
        putSettingDesc("Entoure le texte de crochets", "Wraps the badge text in brackets");
        putSettingDesc("Affiche un point coloré à gauche", "Shows a colored accent dot on the left");
        putSettingDesc("Texte affiché", "Displayed badge text format");
        putSettingDesc("Ajoute les FPS à côté du nom", "Appends live FPS next to the client name");
        putSettingDesc("Vert >144, jaune >60, rouge <60", "Green >144, yellow >60, red <60 FPS");
        putSettingDesc("Affiche aussi le CPS clic droit", "Also displays right-click CPS");
        putSettingDesc("Colore le ping au-delà de ce seuil", "Highlights ping above this latency");
        putSettingDesc("Visible uniquement en courant", "Only visible while sprinting or sneaking");
        putSettingDesc("Affiche LMB et RMB sous les touches", "Shows LMB and RMB below movement keys");
        putSettingDesc("Affiche le CPS dans LMB et RMB", "Displays live CPS inside LMB and RMB");
        putSettingDesc("Affiche la barre de saut", "Displays the jump spacebar key");
        putSettingDesc("Couleur d'une touche pressée", "Highlight color when a key is pressed");
        putSettingDesc("Orientation des pièces", "Horizontal or vertical armor layout");
        putSettingDesc("Format d'affichage", "Durability display style");
        putSettingDesc("Ajoute l'objet tenu", "Also displays the currently held item");
        putSettingDesc("Clignote sous 8 secondes", "Blinks when effect is under 8 seconds");
        putSettingDesc("Réduit l'espacement vertical", "Reduces vertical row spacing");
        putSettingDesc("Sur 1 ou 3 lignes", "Single-line or 3-line layout");
        putSettingDesc("Précision affichée", "Number of decimal places");
        putSettingDesc("Affiche la coordonnée verticale", "Displays the vertical Y coordinate");
        putSettingDesc("Ajoute l'angle numérique", "Appends numeric yaw angle");
        putSettingDesc("Ruban ou badge simple", "Compass ribbon or simple cardinal badge");
        putSettingDesc("Délai d'effacement", "Seconds before reach resets to zero");
        putSettingDesc("Délai sans coup avant remise à zéro", "Seconds without a hit before combo resets");
        putSettingDesc("Unité affichée", "Displayed speed measurement unit");
        putSettingDesc("Inclut la vitesse de chute et saut", "Includes vertical jump and fall velocity");
        putSettingDesc("Affiche sur 24 heures", "Uses 24-hour time format");
        putSettingDesc("Affiche les secondes", "Includes seconds in the clock");
        putSettingDesc("Pourcentage ou mégaoctets", "Percentage or megabytes used");
        putSettingDesc("Échelle verticale", "Vertical graph scale ceiling");
        putSettingDesc("Affiche le nom à droite de l'icône", "Shows the item name next to its icon");
        putSettingDesc("Cache les ressources absentes", "Hides resources with zero count");
        putSettingDesc("Lance ou met en pause", "Starts or pauses the stopwatch");
        putSettingDesc("Réinitialise à 00:00", "Resets the stopwatch to 00:00");
        putSettingDesc("Affiche les PV de la cible", "Shows the target's health bar");
        putSettingDesc("Affiche la distance en blocs", "Shows distance to target in blocks");
        putSettingDesc("Échelle du tracé", "Mouse movement trace scale");
        putSettingDesc("Échelle maximale du graphe", "Maximum latency scale on the graph");
        putSettingDesc("Met en valeur le slot sélectionné", "Highlights the currently selected slot");
        putSettingDesc("Affiche la taille des piles", "Shows item stack counts");
        putSettingDesc("Temps du décompte", "Cooldown duration in seconds");
        putSettingDesc("Secondes de régénération", "Regeneration duration in seconds");
        putSettingDesc("Libellé affiché", "Displayed preset label");
        putSettingDesc("0 choisit selon le processeur", "0 auto-detects based on CPU cores");
        putSettingDesc("0% coupe totalement le son", "0% completely mutes audio");
        putSettingDesc("0% désactive la secousse, 100% garde Vanilla", "0% disables camera shake, 100% keeps Vanilla");
        putSettingDesc("0% rend le masque invisible", "0% makes the overlay invisible");
        putSettingDesc("0% supprime le brouillard", "0% removes distance fog completely");
        putSettingDesc("Accentue le nom du canal", "Highlights the channel name prefix");
        putSettingDesc("Accélère la compilation des chunks", "Speeds up chunk mesh compilation");
        putSettingDesc("Actif seulement avec une épée", "Active only while holding a sword");
        putSettingDesc("Action sélectionnée", "Selected quick command action");
        putSettingDesc("Active la course par bascule", "Toggles sprinting with a single press");
        putSettingDesc("Affiche la confirmation de capture", "Prints a clickable screenshot confirmation in chat");
        putSettingDesc("Affiche la ligne rouge des yeux", "Shows the red eye-height line");
        putSettingDesc("Affiche le chiffre dans chaque case", "Displays the slot number inside each hotbar slot");
        putSettingDesc("Affiche le texte supérieur du serveur", "Shows the server header and footer text");
        putSettingDesc("Affiche le vecteur de visée", "Shows the blue look direction vector");
        putSettingDesc("Affiche le viseur à la 3e personne", "Shows the crosshair in third-person view (F5)");
        putSettingDesc("Affiche les coordonnées localement", "Prints your death coordinates locally in chat");
        putSettingDesc("Affiche les particules bleues sur chaque coup", "Spawns blue sharpness particles on every hit");
        putSettingDesc("Affiche toujours l'éclair 3D", "Still renders the 3D lightning bolt geometry");
        putSettingDesc("Affiche toujours vos coéquipiers", "Always keeps friend and party messages visible");
        putSettingDesc("Affiche un léger anneau lors du clic gauche", "Shows a subtle pulse ring on left-click");
        putSettingDesc("Affiche un léger contour bleu", "Displays a subtle blue cue when fire ends");
        putSettingDesc("Affiche un repère tous les 5 gains", "Highlights every 5-win milestone");
        putSettingDesc("Ajoute l'heure devant chaque message", "Prepends [HH:mm] before each chat message");
        putSettingDesc("Ajoute un aplat translucide", "Fills block faces with a translucent tint");
        putSettingDesc("Ajoute une ombre aux lettres", "Renders a drop shadow behind chat text");
        putSettingDesc("Ajoute une ombre douce sous le pointeur", "Adds a soft drop shadow under the pointer");
        putSettingDesc("Ajuste le zoom à la molette au lieu de la hotbar", "Scroll wheel adjusts zoom level instead of hotbar slot");
        putSettingDesc("Allège le CPU dans les lobbys", "Reduces CPU overhead in crowded lobbies");
        putSettingDesc("Allège les reflets dans les grands coffres", "Optimizes enchantment glint inside large shop GUIs");
        putSettingDesc("Ambiance gravitationnelle dans le menu (activable/désactivable)", "Gravitational ambient audio on the main menu (toggleable)");
        putSettingDesc("Angle de vue par défaut", "Default base field of view angle");
        putSettingDesc("Animation classique d'épée en parade", "Classic 1.7 sword block-hit animation");
        putSettingDesc("Animation classique en mangeant", "Classic 1.7 eating and drinking animation");
        putSettingDesc("Applique la police aux widgets", "Uses smooth vector font on HUD widgets");
        putSettingDesc("Applique la teinte aux armures", "Applies hit color tint to worn armor pieces");
        putSettingDesc("Appliqué à toute la scène 3D", "Applies full brightness across the 3D world");
        putSettingDesc("Attente après la fin de partie", "Delay after match end before sending GG");
        putSettingDesc("Attente minimale avant reconnexion", "Minimum delay before reconnecting");
        putSettingDesc("Atténue l'ondulation violette", "Reduces purple portal screen distortion");
        putSettingDesc("Aucun changement à courte portée", "Preserves full detail at close range");
        putSettingDesc("Aucune synchronisation pilote superflue", "Skips blocking driver error synchronization");
        putSettingDesc("Auto détecte BedWars (2.6s) ou Vanilla (4.0s)", "Auto-detects BedWars (2.6s) or Vanilla (4.0s) fuse");
        putSettingDesc("Bascule Win32 sans recréer le contexte OpenGL", "Switches via Win32 without recreating the OpenGL context");
        putSettingDesc("Blocs ajoutés à Y=128", "Blocks added above default Y=128 cloud height");
        putSettingDesc("Blocs avant le plafond", "Blocks below build limit to trigger warning");
        putSettingDesc("Blocs avant le vide", "Blocks from arena edge to trigger warning");
        putSettingDesc("Bordure sombre autour du viseur", "Dark outline around the crosshair");
        putSettingDesc("Cache complètement le panneau", "Hides the entire scoreboard sidebar");
        putSettingDesc("Cache la colonne de scores à droite", "Hides the red score numbers on the right");
        putSettingDesc("Cache le feu en mode Créatif", "Hides the fire overlay completely in Creative mode");
        putSettingDesc("Cache uniquement le faisceau", "Hides only the vertical light beam");
        putSettingDesc("Change de teinte sur une entité", "Changes crosshair color when aiming at an entity");
        putSettingDesc("Colore les valeurs clés", "Colorizes key telemetry values on the F3 screen");
        putSettingDesc("Compatible multi-cœurs", "Thread-safe across multi-core chunk workers");
        putSettingDesc("Conserve le balancement du bras", "Keeps first-person hand swing bobbing");
        putSettingDesc("Conserve le sprint en vol", "Maintains sprint boost while flying");
        putSettingDesc("Couleur de bordure du pointeur", "Pointer border outline color");
        putSettingDesc("Couleur de mise en valeur", "Highlight color for mentioned messages");
        putSettingDesc("Couleur des arêtes", "Block outline edge color");
        putSettingDesc("Couleur des lignes", "Chunk border grid line color");
        putSettingDesc("Couleur du cadre 3D", "3D hitbox wireframe color");
        putSettingDesc("Couleur du chiffre", "Experience level number color");
        putSettingDesc("Couleur principale du pointeur", "Primary pointer fill color");
        putSettingDesc("Couleur principale ou Chroma", "Primary color or rainbow Chroma mode");
        putSettingDesc("Couleur quand une entité est visée", "Crosshair color when aiming at an entity");
        putSettingDesc("Densité au sol", "Rain splash particle density on the ground");
        putSettingDesc("Distance caméra", "Camera distance in Freelook third-person view");
        putSettingDesc("Distance d'affichage des cadres", "Maximum item frame render distance");
        putSettingDesc("Distance d'affichage des coffres", "Maximum chest, bed and skull render distance");
        putSettingDesc("Distance en blocs", "Maximum render distance in blocks");
        putSettingDesc("Décalage horizontal", "Horizontal X position offset");
        putSettingDesc("Décalage vers le bas", "Downward vertical offset when blocking");
        putSettingDesc("Décalage vertical", "Vertical Y position offset");
        putSettingDesc("Décalage vertical des flammes", "Vertical offset of the first-person fire overlay");
        putSettingDesc("Décalage vertical en pixels", "Vertical scoreboard offset in pixels");
        putSettingDesc("Désactiver verrouille un FOV fixe", "Disabling locks a static FOV without speed changes");
        putSettingDesc("Désactivé en Solo", "Only active on multiplayer servers");
        putSettingDesc("Détecte la teinte BedWars", "Auto-detects your BedWars team color");
        putSettingDesc("Empêche Lenteur de réduire le FOV", "Prevents Slowness from shrinking your FOV");
        putSettingDesc("Espace au centre", "Center gap radius in pixels");
        putSettingDesc("Facilite la lecture sur fond clair", "Improves readability against bright backgrounds");
        putSettingDesc("Facteur FOV arc tendu", "FOV multiplier at full bow draw");
        putSettingDesc("Facteur appliqué en visée", "Mouse sensitivity multiplier while aiming a bow");
        putSettingDesc("Facteur de particules par coup", "Hit particle multiplier per attack");
        putSettingDesc("Facteur de rotation", "Dropped item spin speed multiplier");
        putSettingDesc("Facteur de taille", "Scale multiplier");
        putSettingDesc("Facteur de zoom de départ", "Initial zoom magnification factor");
        putSettingDesc("Facteur par cran de molette", "Zoom step per mouse wheel notch");
        putSettingDesc("Fenêtre de confirmation", "Double-press confirmation window in ms");
        putSettingDesc("Fenêtre de regroupement", "Time window in seconds to stack duplicate messages");
        putSettingDesc("Ferme et enregistre d'un coup", "Confirms and closes the sign editor with Escape");
        putSettingDesc("Force du flou", "Motion blur intensity");
        putSettingDesc("Forme du pointeur dans les menus", "Pointer shape inside menus");
        putSettingDesc("Formule affichée", "GG phrase sent at match end");
        putSettingDesc("Garde l'indicateur rouge WorldBorder", "Preserves the red WorldBorder proximity warning");
        putSettingDesc("Garde la liste des potions à gauche", "Still displays active potion icons on the left");
        putSettingDesc("Gauche et droite changent de page", "Left and right arrow keys turn book pages");
        putSettingDesc("Grossissement maximal", "Maximum scroll zoom magnification");
        putSettingDesc("Hauteur du son", "Audio pitch multiplier");
        putSettingDesc("Inclut les vitres colorées", "Also applies to stained glass blocks");
        putSettingDesc("Intensité du filtre rouge", "Red death screen tint opacity");
        putSettingDesc("Intensité du reflet", "Enchantment glint brightness");
        putSettingDesc("Interpolation à l'entrée et sortie", "Smooth zoom-in and zoom-out interpolation");
        putSettingDesc("Inverse le mouvement vertical", "Inverts vertical camera pitch in Freelook");
        putSettingDesc("Largeur des arêtes", "Block outline line thickness");
        putSettingDesc("Largeur des traits", "Crosshair line thickness");
        putSettingDesc("Latence affichée en vert", "Latency threshold shown in green");
        putSettingDesc("Latence haute en ms", "Latency threshold shown in red (ms)");
        putSettingDesc("Lecture directe sans recalcul", "Direct lighting lookup without per-frame recalculation");
        putSettingDesc("Les garde visibles en F5", "Still shows your own sprint particles in F5");
        putSettingDesc("Lissage de la souris pendant le zoom", "Enables cinematic mouse smoothing while zooming");
        putSettingDesc("Lit l'état réel du clavier", "Reads physical keyboard state after closing a GUI");
        putSettingDesc("Lit la couleur de l'armure équipée", "Reads the color of equipped leather armor");
        putSettingDesc("Longueur des traits ou rayon", "Crosshair line length or radius");
        putSettingDesc("Léger suivi du curseur", "Subtle mouse parallax on the 3D black hole");
        putSettingDesc("Maintenir pour activer la vue libre", "Hold key to rotate camera 360° around player");
        putSettingDesc("Maintenir pour voir derrière", "Hold key for instant rear-view camera");
        putSettingDesc("Maintenir pour zoomer", "Hold key to zoom in");
        putSettingDesc("Minutes entre deux rappels", "Minutes between reminder notifications");
        putSettingDesc("Moment de la journée", "Locked client-side time of day");
        putSettingDesc("Multiplicateur en vol", "FOV multiplier while flying");
        putSettingDesc("Multiplicateur sous Speed I", "FOV multiplier with Speed I active");
        putSettingDesc("Multiplicateur sous Speed II", "FOV multiplier with Speed II active");
        putSettingDesc("N'affiche que les messages du canal sélectionné", "Shows only messages from the selected chat tab");
        putSettingDesc("Affiche uniquement les messages du canal actif", "Shows only messages from the active chat channel");
        putSettingDesc("Ne touche jamais aux lits ni aux coffres", "Always preserves beds, chests and surface blocks");
        putSettingDesc("Niveau d'assombrissement", "Background dimming opacity behind menus");
        putSettingDesc("Niveau d'éclairage", "Brightness gamma level");
        putSettingDesc("Niveau de contraste", "UI text contrast level");
        putSettingDesc("Niveau maximal", "Maximum volume cap");
        putSettingDesc("Niveau maximal signalé", "Block light threshold highlighted in red");
        putSettingDesc("Niveau résiduel", "Background audio volume when unfocused");
        putSettingDesc("Niveau sonore", "Sound volume percentage");
        putSettingDesc("Niveau sonore en lobby", "Cosmetic sound volume in lobbies");
        putSettingDesc("Nom affiché localement", "Replacement name displayed locally");
        putSettingDesc("Nombre de cœurs déclenchant l'alerte", "Health threshold in hearts to trigger vignette");
        putSettingDesc("Occurrences max sur un même tick", "Maximum simultaneous instances per tick");
        putSettingDesc("Ouverture de caméra en sprint", "FOV boost while sprinting");
        putSettingDesc("Particules affichées", "Percentage of enchant/portal particles shown");
        putSettingDesc("Particules conservées", "Percentage of firework particles kept");
        putSettingDesc("Particules simultanées max", "Maximum active particles per layer");
        putSettingDesc("Passe du vert au rouge avant l'explosion", "Transitions from green to red as fuse runs out");
        putSettingDesc("Pixels par cran de molette", "Pixels scrolled per mouse wheel notch");
        putSettingDesc("Plafond fenêtre inactive", "Maximum FPS while window is unfocused");
        putSettingDesc("Portée avec rendu complet", "Distance within which all skin layers render");
        putSettingDesc("Portée d'affichage des capes", "Maximum cape render distance in blocks");
        putSettingDesc("Portée d'affichage des entités", "Maximum entity render distance in blocks");
        putSettingDesc("Portée du reflet d'armure", "Maximum distance for armor enchantment glint");
        putSettingDesc("Portée du texte", "Maximum distance to render sign text");
        putSettingDesc("Position au-dessus du centre", "Vertical offset from center screen");
        putSettingDesc("Priorité immédiate aux zones de jeu", "Immediately rebuilds player-modified chunks");
        putSettingDesc("Précision des nombres", "Decimal precision for horse stats");
        putSettingDesc("Préfixe automatiquement vos messages selon l'onglet actif", "Automatically prefixes outgoing messages for the selected tab");
        putSettingDesc("Purge les skins inutilisés entre deux serveurs", "Evicts unused head textures when switching worlds");
        putSettingDesc("Rapidité de la transition", "Zoom transition speed");
        putSettingDesc("Recalcule uniquement sur rotation notable", "Recomputes visibility only when camera moves");
        putSettingDesc("Relâché automatiquement à l'ouverture d'un menu", "Automatically releases sneak when opening a GUI");
        putSettingDesc("Remet à zéro au changement de carte", "Resets counter when switching worlds");
        putSettingDesc("Remplace les IP personnelles", "Masks numeric private server IPs");
        putSettingDesc("Remplace les barres par la latence chiffrée", "Replaces signal bars with numeric ms ping");
        putSettingDesc("Replace le curseur au centre exact", "Recenters mouse cursor when closing inventories");
        putSettingDesc("Retire le rectangle noir du chat", "Removes the dark background box behind chat");
        putSettingDesc("Retire le rectangle sombre", "Removes the dark background plate behind nametags");
        putSettingDesc("Retire les préfixes de formatage", "Strips formatting codes when copying chat");
        putSettingDesc("Réduction de l'opacité sous-marine", "Reduces underwater overlay opacity");
        putSettingDesc("Réduit les boucles par image", "Reduces world render distance while a GUI is open");
        putSettingDesc("Réutilise la même liste entre les frames", "Reuses pre-allocated buffers across frames");
        putSettingDesc("S'applique aussi aux mondes locaux", "Also applies in singleplayer worlds");
        putSettingDesc("S'écarte légèrement en sautant ou à l'arc", "Expands slightly when jumping or drawing a bow");
        putSettingDesc("Sans copies intermédiaires", "Direct GPU framebuffer blit without intermediate copies");
        putSettingDesc("Sans flou de redimensionnement", "Crisp nearest-neighbor framebuffer blitz");
        putSettingDesc("Secondes suivies", "Strength perk countdown duration in seconds");
        putSettingDesc("Sensibilité souris pendant le zoom", "Mouse sensitivity percentage while zoomed");
        putSettingDesc("Style du viseur", "Crosshair shape style");
        putSettingDesc("Supprime les jonctions visibles entre chunks", "Prevents sub-pixel seams between adjacent chunks");
        putSettingDesc("Supprime les sauts de ligne répétés", "Collapses consecutive empty lines in chat");
        putSettingDesc("Taille au sol", "Scale multiplier for dropped golden apples/ingots");
        putSettingDesc("Taille de l'objet", "Held item scale multiplier");
        putSettingDesc("Taille de la barre", "Bossbar scale percentage");
        putSettingDesc("Taille des axes", "F3 debug crosshair scale percentage");
        putSettingDesc("Teinte au zénith", "Upper sky zenith color");
        putSettingDesc("Teinte de remplissage", "Block face fill color and opacity");
        putSettingDesc("Teinte du mot-clé", "Highlight color for matched keywords");
        putSettingDesc("Teinte du reflet", "Custom enchantment glint color");
        putSettingDesc("Teinte et opacité à l'impact", "Damage flash color and opacity on hit");
        putSettingDesc("Tenue d'arc style 1.7", "Classic 1.7 bow holding and drawing position");
        putSettingDesc("Test d'occlusion derrière les blocs opaques", "Skips entities hidden behind solid walls");
        putSettingDesc("Unifie la couleur de l'horizon", "Removes the dark void horizon below Y=60");
        putSettingDesc("Utilise la valeur ci-dessous", "Overrides vanilla FOV with the value below");
        putSettingDesc("Utilisé en mode Personnalisé", "Custom world time ticks (used in Custom mode)");
        putSettingDesc("Visible à la 3e personne", "Renders your own nametag in F5 third-person view");
        putSettingDesc("Écart autour du viseur", "Distance from crosshair in pixels");
        putSettingDesc("Échelle du flotteur", "Fishing rod bobber scale multiplier");
        putSettingDesc("Échelle du pointeur", "Custom pointer size multiplier");
        putSettingDesc("Économise du temps CPU à chaque tick", "Skips 1,000 random void fog checks every tick");
        putSettingDesc("Éléments visibles", "Bossbar display mode");
        putSettingDesc("Épaisseur de la bordure", "Crosshair outline thickness in pixels");
        putSettingDesc("État local du ciel", "Client-side weather state");
        putSettingDesc("Évite le décodage répété", "Caches decoded player skull textures");
        putSettingDesc("Évite le recalcul par tick", "Caches string widths for zero-cost lookups");
        putSettingDesc("Évite les envois GPU inutiles", "Only uploads animated textures visible on screen");
        putSettingDesc("Évite les saccades en entrant au lobby", "Limits skin texture uploads to 1 per frame");

        // =====================================================================
        // 9. MODE OPTIONS (FR -> EN)
        // =====================================================================
        putMode("Sprint • Actif", "Sprint • Active");
        putMode("Horizontale", "Horizontal");
        putMode("Verticale", "Vertical");
        putMode("Barre", "Bar");
        putMode("Points", "Points");
        putMode("Pourcentage", "Percent");
        putMode("1 ligne", "1 Line");
        putMode("3 lignes", "3 Lines");
        putMode("Ruban", "Ribbon");
        putMode("Cardinal", "Cardinal");
        putMode("blocs/tick", "blocks/tick");
        putMode("Mo / Max", "MB / Max");
        putMode("Croix", "Cross");
        putMode("Croix + Point", "Cross + Dot");
        putMode("Point", "Dot");
        putMode("Cercle", "Circle");
        putMode("Chevron", "Chevron");
        putMode("Carré", "Square");
        putMode("Midi", "Noon");
        putMode("Coucher de soleil", "Sunset");
        putMode("Minuit", "Midnight");
        putMode("Aube", "Dawn");
        putMode("Personnalisé", "Custom");
        putMode("Dégagé", "Clear");
        putMode("Pluie", "Rain");
        putMode("Orage", "Storm");
        putMode("Barre + Texte", "Bar + Text");
        putMode("Texte seul", "Text Only");
        putMode("Masqué", "Hidden");
        putMode("Flèche Système", "System Arrow");
        putMode("Flèche Mash", "Mash Arrow");
        putMode("Diamant", "Diamond");
        putMode("Bien joué", "Well played");
        putMode("Vous", "You");
        putMode("Joueur", "Player");
    }

    private MashEnglishCatalog() {
    }

    private static void put(String id, String englishName, String englishAliases, String englishDesc) {
        ENTRIES.put(id, new Entry(englishName, englishAliases, englishDesc));
    }

    private static void putSettingName(String fr, String en) {
        SETTING_NAMES.put(fr, en);
    }

    private static void putSettingDesc(String fr, String en) {
        SETTING_DESCS.put(fr, en);
    }

    private static void putMode(String fr, String en) {
        MODE_OPTIONS.put(fr, en);
    }

    public static Entry lookup(String moduleId) {
        return ENTRIES.get(moduleId);
    }

    public static String translateDescription(String moduleId, String frDesc) {
        Entry e = ENTRIES.get(moduleId);
        if (e != null && e.englishDesc != null && !e.englishDesc.isEmpty()) {
            return e.englishDesc;
        }
        return frDesc != null ? frDesc : "";
    }

    public static String translateSettingName(String settingId, String frName) {
        if (frName == null) {
            return "";
        }
        String mapped = SETTING_NAMES.get(frName);
        return mapped != null ? mapped : frName;
    }

    public static String translateSettingDesc(String settingId, String frDesc) {
        if (frDesc == null) {
            return "";
        }
        String mapped = SETTING_DESCS.get(frDesc);
        return mapped != null ? mapped : frDesc;
    }

    public static String translateModeOption(String frOpt) {
        if (frOpt == null) {
            return "";
        }
        String mapped = MODE_OPTIONS.get(frOpt);
        return mapped != null ? mapped : frOpt;
    }
}

package dev.purefps.core;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.ResourceLocation;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * Cache ultra-rapide O(1) zéro-allocation pour les crânes custom (Hypixel Lobbies, SkyBlock, BedWars, Menus) :
 * 1. Cache par identité NBTTagCompound -> GameProfile (élimine UUID.fromString + LinkedHashMultimap à chaque frame).
 * 2. Cache par identité GameProfile -> ResourceLocation (élimine Guava LoadingCache + FilenameUtils + StringBuilder + ResourceLocation à chaque frame).
 */
public final class FastSkullCache {

    private static final int SIZE = 1024;
    private static final int MASK = SIZE - 1;

    private static final NBTTagCompound[] TAG_KEYS = new NBTTagCompound[SIZE];
    private static final GameProfile[] TAG_PROFILES = new GameProfile[SIZE];

    private static final GameProfile[] PROFILE_KEYS = new GameProfile[SIZE];
    private static final ResourceLocation[] PROFILE_LOCATIONS = new ResourceLocation[SIZE];
    private static final long[] PROFILE_RETRY_MS = new long[SIZE];

    private FastSkullCache() {
    }

    public static void clear() {
        Arrays.fill(TAG_KEYS, null);
        Arrays.fill(TAG_PROFILES, null);
        Arrays.fill(PROFILE_KEYS, null);
        Arrays.fill(PROFILE_LOCATIONS, null);
        Arrays.fill(PROFILE_RETRY_MS, 0L);
    }

    /**
     * Remplace NBTUtil.readGameProfileFromNBT(skullOwnerTag) dans LayerCustomHead & TileEntityItemStackRenderer.
     */
    public static GameProfile readCachedProfile(NBTTagCompound skullOwnerTag) {
        if (skullOwnerTag == null) {
            return null;
        }
        int h = System.identityHashCode(skullOwnerTag);
        int slot0 = (h ^ (h >>> 11)) & MASK;
        if (TAG_KEYS[slot0] == skullOwnerTag) {
            return TAG_PROFILES[slot0];
        }
        int slot1 = (slot0 + 1) & MASK;
        if (TAG_KEYS[slot1] == skullOwnerTag) {
            return TAG_PROFILES[slot1];
        }

        GameProfile parsed = NBTUtil.readGameProfileFromNBT(skullOwnerTag);
        if (parsed != null) {
            if (TAG_KEYS[slot0] != null) {
                TAG_KEYS[slot1] = TAG_KEYS[slot0];
                TAG_PROFILES[slot1] = TAG_PROFILES[slot0];
            }
            TAG_KEYS[slot0] = skullOwnerTag;
            TAG_PROFILES[slot0] = parsed;
        }
        return parsed;
    }

    /**
     * Résout la texture d'un crâne joueur en O(1) (~1.5 ns) sans Guava LoadingCache ni allocation de String/ResourceLocation.
     */
    public static ResourceLocation resolveSkullTexture(GameProfile profile) {
        if (profile == null) {
            return DefaultPlayerSkin.getDefaultSkinLegacy();
        }

        int h = System.identityHashCode(profile);
        int slot0 = (h ^ (h >>> 11)) & MASK;
        if (PROFILE_KEYS[slot0] == profile) {
            long retryAt = PROFILE_RETRY_MS[slot0];
            if (retryAt == Long.MAX_VALUE || System.currentTimeMillis() < retryAt) {
                return PROFILE_LOCATIONS[slot0];
            }
        }
        int slot1 = (slot0 + 1) & MASK;
        if (PROFILE_KEYS[slot1] == profile) {
            long retryAt = PROFILE_RETRY_MS[slot1];
            if (retryAt == Long.MAX_VALUE || System.currentTimeMillis() < retryAt) {
                return PROFILE_LOCATIONS[slot1];
            }
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSkinManager() == null) {
            return DefaultPlayerSkin.getDefaultSkinLegacy();
        }

        SkinManager skinManager = mc.getSkinManager();
        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> map = skinManager.loadSkinFromCache(profile);
        ResourceLocation resolved;
        long nextRetry;

        MinecraftProfileTexture skinTex = map != null ? map.get(MinecraftProfileTexture.Type.SKIN) : null;
        if (skinTex != null) {
            resolved = skinManager.loadSkin(skinTex, MinecraftProfileTexture.Type.SKIN);
            nextRetry = Long.MAX_VALUE; // Définitif jusqu'au rechargement des ressources
        } else {
            UUID uuid = EntityPlayer.getUUID(profile);
            resolved = DefaultPlayerSkin.getDefaultSkin(uuid);
            // Si le profil attend une réponse asynchrone Mojang, ne re-tester que dans 1 seconde au lieu de 400x/sec
            nextRetry = System.currentTimeMillis() + 1000L;
        }

        if (PROFILE_KEYS[slot0] != null && PROFILE_KEYS[slot0] != profile) {
            PROFILE_KEYS[slot1] = PROFILE_KEYS[slot0];
            PROFILE_LOCATIONS[slot1] = PROFILE_LOCATIONS[slot0];
            PROFILE_RETRY_MS[slot1] = PROFILE_RETRY_MS[slot0];
        }
        PROFILE_KEYS[slot0] = profile;
        PROFILE_LOCATIONS[slot0] = resolved;
        PROFILE_RETRY_MS[slot0] = nextRetry;
        return resolved;
    }

    public static ResourceLocation resolveVanillaSkullTexture(GameProfile profile) {
        if (profile == null) {
            return DefaultPlayerSkin.getDefaultSkinLegacy();
        }
        Minecraft mc = Minecraft.getMinecraft();
        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> map = mc.getSkinManager().loadSkinFromCache(profile);
        if (map.containsKey(MinecraftProfileTexture.Type.SKIN)) {
            return mc.getSkinManager().loadSkin(map.get(MinecraftProfileTexture.Type.SKIN), MinecraftProfileTexture.Type.SKIN);
        }
        return DefaultPlayerSkin.getDefaultSkin(EntityPlayer.getUUID(profile));
    }
}

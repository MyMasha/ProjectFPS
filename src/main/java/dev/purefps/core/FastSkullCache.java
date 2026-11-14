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
 * Cache atomique O(1) 100 % thread-safe et sans NPE pour les crânes custom (Hypixel Lobbies, SkyBlock, BedWars) :
 * - Utilise des entrées immuables (TagEntry / ProfileEntry) publiées en une seule écriture de référence atomique JMM :
 *   aucune course possible avec clear() lors d'un S48PacketResourcePackSend sur un thread Netty, et impossibilité
 *   absolue de retourner null vers TextureManager.bindTexture().
 */
public final class FastSkullCache {

    private static final int SIZE = 1024;
    private static final int MASK = SIZE - 1;

    private static final class TagEntry {
        final NBTTagCompound key;
        final GameProfile profile;

        TagEntry(NBTTagCompound key, GameProfile profile) {
            this.key = key;
            this.profile = profile;
        }
    }

    private static final class ProfileEntry {
        final GameProfile key;
        final ResourceLocation location;
        final long retryMs;

        ProfileEntry(GameProfile key, ResourceLocation location, long retryMs) {
            this.key = key;
            this.location = location;
            this.retryMs = retryMs;
        }
    }

    private static final TagEntry[] TAG_ENTRIES = new TagEntry[SIZE];
    private static final ProfileEntry[] PROFILE_ENTRIES = new ProfileEntry[SIZE];

    private FastSkullCache() {
    }

    public static void clear() {
        Arrays.fill(TAG_ENTRIES, null);
        Arrays.fill(PROFILE_ENTRIES, null);
    }

    public static GameProfile readCachedProfile(NBTTagCompound skullOwnerTag) {
        if (skullOwnerTag == null) {
            return null;
        }
        int h = System.identityHashCode(skullOwnerTag);
        int slot0 = (h ^ (h >>> 11)) & MASK;
        TagEntry e0 = TAG_ENTRIES[slot0];
        if (e0 != null && e0.key == skullOwnerTag) {
            return e0.profile;
        }
        int slot1 = (slot0 + 1) & MASK;
        TagEntry e1 = TAG_ENTRIES[slot1];
        if (e1 != null && e1.key == skullOwnerTag) {
            return e1.profile;
        }

        GameProfile parsed = NBTUtil.readGameProfileFromNBT(skullOwnerTag);
        if (parsed != null) {
            if (e0 != null && e0.key != skullOwnerTag) {
                TAG_ENTRIES[slot1] = e0;
            }
            TAG_ENTRIES[slot0] = new TagEntry(skullOwnerTag, parsed);
        }
        return parsed;
    }

    public static ResourceLocation resolveSkullTexture(GameProfile profile) {
        ResourceLocation fallback = DefaultPlayerSkin.getDefaultSkinLegacy();
        if (profile == null) {
            return fallback;
        }

        int h = System.identityHashCode(profile);
        int slot0 = (h ^ (h >>> 11)) & MASK;
        ProfileEntry e0 = PROFILE_ENTRIES[slot0];
        if (e0 != null && e0.key == profile && e0.location != null) {
            if (e0.retryMs == Long.MAX_VALUE || System.currentTimeMillis() < e0.retryMs) {
                return e0.location;
            }
        }
        int slot1 = (slot0 + 1) & MASK;
        ProfileEntry e1 = PROFILE_ENTRIES[slot1];
        if (e1 != null && e1.key == profile && e1.location != null) {
            if (e1.retryMs == Long.MAX_VALUE || System.currentTimeMillis() < e1.retryMs) {
                return e1.location;
            }
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSkinManager() == null) {
            return fallback;
        }

        SkinManager skinManager = mc.getSkinManager();
        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> map = skinManager.loadSkinFromCache(profile);
        ResourceLocation resolved;
        long nextRetry;

        MinecraftProfileTexture skinTex = map != null ? map.get(MinecraftProfileTexture.Type.SKIN) : null;
        if (skinTex != null) {
            resolved = skinManager.loadSkin(skinTex, MinecraftProfileTexture.Type.SKIN);
            nextRetry = Long.MAX_VALUE;
        } else {
            UUID uuid = EntityPlayer.getUUID(profile);
            resolved = DefaultPlayerSkin.getDefaultSkin(uuid);
            nextRetry = System.currentTimeMillis() + 1000L;
        }
        if (resolved == null) {
            resolved = fallback;
        }

        if (e0 != null && e0.key != profile) {
            PROFILE_ENTRIES[slot1] = e0;
        }
        PROFILE_ENTRIES[slot0] = new ProfileEntry(profile, resolved, nextRetry);
        return resolved;
    }

    public static ResourceLocation resolveVanillaSkullTexture(GameProfile profile) {
        if (profile == null) {
            return DefaultPlayerSkin.getDefaultSkinLegacy();
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSkinManager() == null) {
            return DefaultPlayerSkin.getDefaultSkinLegacy();
        }
        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> map = mc.getSkinManager().loadSkinFromCache(profile);
        if (map != null && map.containsKey(MinecraftProfileTexture.Type.SKIN)) {
            ResourceLocation loc = mc.getSkinManager().loadSkin(map.get(MinecraftProfileTexture.Type.SKIN), MinecraftProfileTexture.Type.SKIN);
            if (loc != null) {
                return loc;
            }
        }
        ResourceLocation def = DefaultPlayerSkin.getDefaultSkin(EntityPlayer.getUUID(profile));
        return def != null ? def : DefaultPlayerSkin.getDefaultSkinLegacy();
    }
}

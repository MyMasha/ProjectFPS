package dev.purefps.core;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.ResourceLocation;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Cache atomique O(1) 100 % thread-safe et sans NPE pour les crânes custom (Hypixel Lobbies, SkyBlock, BedWars) :
 * - Utilise des entrées immuables (TagEntry / ProfileEntry / ContentEntry) publiées en écriture atomique JMM :
 *   aucune course possible avec clear() lors d'un S48PacketResourcePackSend sur un thread Netty, et impossibilité
 *   absolue de retourner null vers TextureManager.bindTexture().
 * - Cache secondaire par contenu Base64 (`textures`) : deux GameProfile distincts portant la même tête SkyBlock /
 *   BedWars partagent instantanément le même ResourceLocation sans rappeler SkinManager.
 */
public final class FastSkullCache {

    private static final int SIZE = 1024;
    private static final int MASK = SIZE - 1;
    private static final int CONTENT_SIZE = 512;
    private static final int CONTENT_MASK = CONTENT_SIZE - 1;

    public static final MinecraftProfileTexture SENTINEL_TEXTURE =
            new MinecraftProfileTexture("https://purefps.local/sentinel", null);
    private static final Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> SENTINEL_SKIN_MAP =
            Collections.singletonMap(MinecraftProfileTexture.Type.SKIN, SENTINEL_TEXTURE);
    private static ResourceLocation pendingFastSkullTexture;

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

    private static final class ContentEntry {
        final String key;
        final ResourceLocation location;

        ContentEntry(String key, ResourceLocation location) {
            this.key = key;
            this.location = location;
        }
    }

    private static final TagEntry[] TAG_ENTRIES = new TagEntry[SIZE];
    private static final ProfileEntry[] PROFILE_ENTRIES = new ProfileEntry[SIZE];
    private static final ContentEntry[] CONTENT_ENTRIES = new ContentEntry[CONTENT_SIZE];

    private FastSkullCache() {
    }

    public static void clear() {
        Arrays.fill(TAG_ENTRIES, null);
        Arrays.fill(PROFILE_ENTRIES, null);
        Arrays.fill(CONTENT_ENTRIES, null);
        pendingFastSkullTexture = null;
    }

    public static Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> beginFastSkullLookup(GameProfile profile) {
        pendingFastSkullTexture = resolveSkullTexture(profile);
        return SENTINEL_SKIN_MAP;
    }

    public static ResourceLocation consumeFastSkullTexture() {
        ResourceLocation resolved = pendingFastSkullTexture;
        pendingFastSkullTexture = null;
        return resolved != null ? resolved : DefaultPlayerSkin.getDefaultSkinLegacy();
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

        // Cache secondaire par valeur de texture Base64 (têtes décoratives identiques sur des GameProfile distincts)
        String contentKey = extractTextureContentKey(profile);
        if (contentKey != null) {
            ResourceLocation byContent = lookupContentCache(contentKey);
            if (byContent != null) {
                if (e0 != null && e0.key != profile) {
                    PROFILE_ENTRIES[slot1] = e0;
                }
                PROFILE_ENTRIES[slot0] = new ProfileEntry(profile, byContent, Long.MAX_VALUE);
                return byContent;
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
            if (resolved != null && contentKey != null) {
                storeContentCache(contentKey, resolved);
            }
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

    private static String extractTextureContentKey(GameProfile profile) {
        try {
            if (profile.getProperties() != null) {
                Collection<Property> props = profile.getProperties().get("textures");
                if (props != null && !props.isEmpty()) {
                    for (Property prop : props) {
                        if (prop != null && prop.getValue() != null && !prop.getValue().isEmpty()) {
                            return prop.getValue();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static ResourceLocation lookupContentCache(String key) {
        int h = key.hashCode();
        int c0 = (h ^ (h >>> 11)) & CONTENT_MASK;
        ContentEntry ce0 = CONTENT_ENTRIES[c0];
        if (ce0 != null && key.equals(ce0.key)) {
            return ce0.location;
        }
        int c1 = (c0 + 1) & CONTENT_MASK;
        ContentEntry ce1 = CONTENT_ENTRIES[c1];
        if (ce1 != null && key.equals(ce1.key)) {
            return ce1.location;
        }
        return null;
    }

    private static void storeContentCache(String key, ResourceLocation loc) {
        int h = key.hashCode();
        int c0 = (h ^ (h >>> 11)) & CONTENT_MASK;
        ContentEntry existing = CONTENT_ENTRIES[c0];
        if (existing != null && !key.equals(existing.key)) {
            CONTENT_ENTRIES[(c0 + 1) & CONTENT_MASK] = existing;
        }
        CONTENT_ENTRIES[c0] = new ContentEntry(key, loc);
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

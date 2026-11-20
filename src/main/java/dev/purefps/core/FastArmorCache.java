package dev.purefps.core;

import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * Table O(1) pré-calculée des ResourceLocation d'armures Vanilla :
 * Élimine les 4 à 8 appels String.format("%s:textures/models/armor/%s_layer_%d%s.png") + HashMap.get
 * exécutés par LayerArmorBase.getArmorResource sur chaque joueur équipé à chaque frame.
 */
public final class FastArmorCache {

    // 5 matériaux Vanilla (LEATHER=0, CHAIN=1, IRON=2, GOLD=3, DIAMOND=4) x 2 couches (layer 1 / layer 2) x 2 types (base / overlay)
    private static final ResourceLocation[] VANILLA_TABLE = new ResourceLocation[20];

    static {
        String[] names = {"leather", "chainmail", "iron", "gold", "diamond"};
        for (int mat = 0; mat < names.length; mat++) {
            String n = names[mat];
            int base = mat << 2;
            VANILLA_TABLE[base] = new ResourceLocation("minecraft:textures/models/armor/" + n + "_layer_1.png");
            VANILLA_TABLE[base + 1] = new ResourceLocation("minecraft:textures/models/armor/" + n + "_layer_1_overlay.png");
            VANILLA_TABLE[base + 2] = new ResourceLocation("minecraft:textures/models/armor/" + n + "_layer_2.png");
            VANILLA_TABLE[base + 3] = new ResourceLocation("minecraft:textures/models/armor/" + n + "_layer_2_overlay.png");
        }
    }

    private FastArmorCache() {
    }

    public static ResourceLocation lookupVanillaArmor(ItemStack stack, int slot, String type) {
        if (stack == null) {
            return null;
        }
        Item item = stack.getItem();
        if (item == null || item.getClass() != ItemArmor.class) {
            return null;
        }
        ItemArmor armor = (ItemArmor) item;
        ItemArmor.ArmorMaterial mat = armor.getArmorMaterial();
        if (mat == null) {
            return null;
        }
        int ord = mat.ordinal();
        if (ord < 0 || ord >= 5) {
            return null;
        }
        int overlayBit;
        if (type == null) {
            overlayBit = 0;
        } else if ("overlay".equals(type)) {
            overlayBit = 1;
        } else {
            return null;
        }
        int layerBit = (slot == 2) ? 2 : 0;
        return VANILLA_TABLE[(ord << 2) + layerBit + overlayBit];
    }

    public static ResourceLocation lookupByOrdinalForTest(int matOrdinal, boolean leggingsLayer, boolean overlay) {
        if (matOrdinal < 0 || matOrdinal >= 5) {
            return null;
        }
        return VANILLA_TABLE[(matOrdinal << 2) + (leggingsLayer ? 2 : 0) + (overlay ? 1 : 0)];
    }
}

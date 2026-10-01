package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.CompoundTag;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.Entry;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.ListTag;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.NbtValue;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact data-pack enchantments shipped by Ars Nouveau 5.11.3 for Minecraft 1.21.1. */
final class ArsNouveauEnchantmentRegistry {
    static final String SHIM_ID = "ars-nouveau-5.11.3";
    static final String REQUIRED_NAMESPACE = "ars_nouveau";
    static final String REGISTRY_ID = "minecraft:enchantment";
    static final String SOURCE_COMMIT = "251e44df01150e2b7ed9185e4a9e9feb16b50e8b";
    static final int ENTRY_COUNT = 3;

    private static final int INTERNAL_MAXIMUM_PACKET_BYTES = 65_536;
    private static final byte[] PACKET_BODY = createPacketBody();
    private static final String PACKET_SHA256 = sha256(PACKET_BODY);

    private ArsNouveauEnchantmentRegistry() {
    }

    static RegistryShimPacket packet(int configuredMaximumBytes) {
        if (PACKET_BODY.length > configuredMaximumBytes) {
            throw new IllegalArgumentException(
                    SHIM_ID + " requires " + PACKET_BODY.length
                            + " bytes but maximum-registry-shim-bytes is "
                            + configuredMaximumBytes);
        }
        return new RegistryShimPacket(
                SHIM_ID,
                REQUIRED_NAMESPACE,
                REGISTRY_ID,
                ENTRY_COUNT,
                PACKET_BODY,
                PACKET_SHA256);
    }

    static byte[] packetBodyForTest() {
        return PACKET_BODY.clone();
    }

    static String packetSha256ForTest() {
        return PACKET_SHA256;
    }

    private static byte[] createPacketBody() {
        try {
            return MinecraftRegistryPacketCodec.encode(
                    REGISTRY_ID,
                    List.of(
                            attributedEnchantment(
                                    "mana_boost",
                                    5,
                                    3,
                                    "ars_nouveau:ars_nouveau.perk.max_mana",
                                    "ars_nouveau:enchantment.max_mana",
                                    25.0F),
                            attributedEnchantment(
                                    "mana_regen",
                                    5,
                                    3,
                                    "ars_nouveau:ars_nouveau.perk.mana_regen",
                                    "ars_nouveau:enchantment.mana_regen",
                                    2.0F),
                            reactiveEnchantment()),
                    INTERNAL_MAXIMUM_PACKET_BYTES);
        } catch (ProtocolViolationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Entry attributedEnchantment(
            String name,
            int weight,
            int maximumLevel,
            String attribute,
            String effectId,
            float amountPerLevel) {
        LinkedHashMap<String, NbtValue> root = definitionHeader(name);
        root.put("effects", attributeEffects(attribute, effectId, amountPerLevel));
        appendDefinitionTail(
                root,
                weight,
                maximumLevel,
                "armor",
                MinecraftRegistryPacketCodec.stringTag("#minecraft:enchantable/armor"));
        return new Entry("ars_nouveau:" + name, new CompoundTag(root));
    }

    private static Entry reactiveEnchantment() {
        CompoundTag everyItem = new CompoundTag(Map.of(
                "type", MinecraftRegistryPacketCodec.stringTag("neoforge:any")));
        LinkedHashMap<String, NbtValue> root = definitionHeader("reactive");
        appendDefinitionTail(root, 1, 4, "any", everyItem);
        return new Entry(
                "ars_nouveau:reactive",
                new CompoundTag(root));
    }

    private static LinkedHashMap<String, NbtValue> definitionHeader(String name) {
        LinkedHashMap<String, NbtValue> root = new LinkedHashMap<>();
        root.put("anvil_cost", MinecraftRegistryPacketCodec.intTag(1));
        root.put("description", translatedDescription(name));
        return root;
    }

    private static void appendDefinitionTail(
            LinkedHashMap<String, NbtValue> root,
            int weight,
            int maximumLevel,
            String slot,
            NbtValue supportedItems) {
        root.put("max_cost", cost(12, 11));
        root.put("max_level", MinecraftRegistryPacketCodec.intTag(maximumLevel));
        root.put("min_cost", cost(1, 11));
        root.put("slots", MinecraftRegistryPacketCodec.stringList(slot));
        root.put("supported_items", supportedItems);
        root.put("weight", MinecraftRegistryPacketCodec.intTag(weight));
    }

    private static CompoundTag translatedDescription(String name) {
        return new CompoundTag(Map.of(
                "translate",
                MinecraftRegistryPacketCodec.stringTag(
                        "enchantment.ars_nouveau." + name)));
    }

    private static CompoundTag cost(int base, int perLevelAboveFirst) {
        LinkedHashMap<String, NbtValue> cost = new LinkedHashMap<>();
        cost.put("base", MinecraftRegistryPacketCodec.intTag(base));
        cost.put(
                "per_level_above_first",
                MinecraftRegistryPacketCodec.intTag(perLevelAboveFirst));
        return new CompoundTag(cost);
    }

    private static CompoundTag attributeEffects(
            String attribute,
            String effectId,
            float amountPerLevel) {
        LinkedHashMap<String, NbtValue> amount = new LinkedHashMap<>();
        amount.put("type", MinecraftRegistryPacketCodec.stringTag("minecraft:linear"));
        amount.put("base", MinecraftRegistryPacketCodec.floatTag(amountPerLevel));
        amount.put(
                "per_level_above_first",
                MinecraftRegistryPacketCodec.floatTag(amountPerLevel));

        LinkedHashMap<String, NbtValue> effect = new LinkedHashMap<>();
        effect.put("amount", new CompoundTag(amount));
        effect.put("attribute", MinecraftRegistryPacketCodec.stringTag(attribute));
        effect.put("id", MinecraftRegistryPacketCodec.stringTag(effectId));
        effect.put("operation", MinecraftRegistryPacketCodec.stringTag("add_value"));

        return new CompoundTag(Map.of(
                "minecraft:attributes",
                new ListTag(List.of(new CompoundTag(effect)))));
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}

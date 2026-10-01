package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.CompoundTag;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.Entry;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.NbtValue;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact networkable data-pack registry shipped by Forbidden Arcanus 2.6.1 for Minecraft 1.21.1. */
final class ForbiddenArcanusItemModifierRegistry {
    static final String SHIM_ID = "forbidden-arcanus-2.6.1";
    static final String REQUIRED_NAMESPACE = "forbidden_arcanus";
    static final String REGISTRY_ID = "forbidden_arcanus:item_modifier";
    static final String SOURCE_COMMIT = "f62af610d550e0d033f6c5cd166e40062638c44b";
    static final int ENTRY_COUNT = 6;

    private static final int INTERNAL_MAXIMUM_PACKET_BYTES = 65_536;
    private static final byte[] PACKET_BODY = createPacketBody();
    private static final String PACKET_SHA256 = sha256(PACKET_BODY);

    private ForbiddenArcanusItemModifierRegistry() {
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
                            itemEntry(
                                    "aquatic",
                                    -10_845_453,
                                    -14_463_028,
                                    itemPredicate("#minecraft:head_armor"),
                                    List.of()),
                            itemEntry(
                                    "demolishing",
                                    -9_481_136,
                                    -11_650_521,
                                    abilityPredicate("valhelsia_core:any_of"),
                                    List.of()),
                            itemEntry(
                                    "eternal",
                                    -5_589_601,
                                    -13_551_304,
                                    componentPredicate(),
                                    List.of("minecraft:damage", "minecraft:max_damage")),
                            itemEntry(
                                    "fiery",
                                    -28_928,
                                    -11_008_506,
                                    abilityPredicate("valhelsia_core:any_of"),
                                    List.of()),
                            itemEntry(
                                    "magnetized",
                                    -3_618_345,
                                    -11_048_605,
                                    itemPredicate("#minecraft:foot_armor"),
                                    List.of()),
                            itemEntry(
                                    "soulbound",
                                    -5_850_634,
                                    -542_503,
                                    itemPredicate(
                                            "#forbidden_arcanus:modifier/soulbound_applicable"),
                                    List.of())),
                    INTERNAL_MAXIMUM_PACKET_BYTES);
        } catch (ProtocolViolationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Entry itemEntry(
            String name,
            int tooltipStart,
            int tooltipEnd,
            CompoundTag predicate,
            List<String> componentsToRemove) {
        LinkedHashMap<String, NbtValue> root = new LinkedHashMap<>();
        if (!componentsToRemove.isEmpty()) {
            root.put(
                    "components_to_remove",
                    MinecraftRegistryPacketCodec.stringList(
                            componentsToRemove.toArray(String[]::new)));
        }
        root.put("display", display(name, tooltipStart, tooltipEnd));
        root.put(
                "incompatible_enchantments",
                MinecraftRegistryPacketCodec.stringTag(
                        "#forbidden_arcanus:modifier/" + name + "_incompatible"));
        root.put(
                "incompatible_items",
                MinecraftRegistryPacketCodec.stringTag(
                        "#forbidden_arcanus:modifier/" + name + "_incompatible"));
        root.put("predicate", predicate);
        return new Entry("forbidden_arcanus:" + name, new CompoundTag(root));
    }

    private static CompoundTag display(String name, int start, int end) {
        LinkedHashMap<String, NbtValue> translatedName = new LinkedHashMap<>();
        translatedName.put(
                "translate",
                MinecraftRegistryPacketCodec.stringTag(
                        "modifier.forbidden_arcanus." + name));

        LinkedHashMap<String, NbtValue> tooltipColor = new LinkedHashMap<>();
        tooltipColor.put("end", MinecraftRegistryPacketCodec.intTag(end));
        tooltipColor.put("start", MinecraftRegistryPacketCodec.intTag(start));

        LinkedHashMap<String, NbtValue> display = new LinkedHashMap<>();
        display.put("name", new CompoundTag(translatedName));
        display.put(
                "texture",
                MinecraftRegistryPacketCodec.stringTag(
                        "forbidden_arcanus:textures/gui/tooltip/" + name + ".png"));
        display.put("tooltip_color", new CompoundTag(tooltipColor));
        return new CompoundTag(display);
    }

    private static CompoundTag itemPredicate(String itemOrTag) {
        return new CompoundTag(Map.of(
                "items", MinecraftRegistryPacketCodec.stringTag(itemOrTag)));
    }

    private static CompoundTag abilityPredicate(String booleanPredicate) {
        LinkedHashMap<String, NbtValue> ability = new LinkedHashMap<>();
        ability.put(
                "neoforge:item_ability",
                MinecraftRegistryPacketCodec.stringList(
                        "pickaxe_dig", "axe_dig", "shovel_dig", "hoe_dig"));
        return nestedPredicate(booleanPredicate, new CompoundTag(ability));
    }

    private static CompoundTag componentPredicate() {
        LinkedHashMap<String, NbtValue> components = new LinkedHashMap<>();
        components.put(
                "valhelsia_core:has_component",
                MinecraftRegistryPacketCodec.stringList(
                        "minecraft:max_damage", "minecraft:damage"));
        return nestedPredicate("valhelsia_core:all_of", new CompoundTag(components));
    }

    private static CompoundTag nestedPredicate(String id, CompoundTag body) {
        LinkedHashMap<String, NbtValue> selector = new LinkedHashMap<>();
        selector.put(id, body);
        return new CompoundTag(Map.of("predicates", new CompoundTag(selector)));
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

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

/**
 * Exact Minecraft 1.21.1 datapack enchantments shipped by Ad Astra: Giselle Addon 8.1.
 *
 * <p>This is an adaptive registry-rescue shim, not an admission profile. It is selected only when
 * the observed client contract advertises the parent Ad Astra network namespace. Every encoded
 * field uses vanilla enchantment codecs; no Giselle-specific serializer or effect type is
 * synthesized.</p>
 */
final class AdAstraGiselleEnchantmentRegistry {
    static final String SHIM_ID = "ad-astra-giselle-addon-8.1";
    static final String REQUIRED_NAMESPACE = "ad_astra";
    static final String REGISTRY_ID = "minecraft:enchantment";
    static final String SOURCE_COMMIT = "068f631ab769c5d22c007c6f266d8d436286bb5d";
    static final int ENTRY_COUNT = 4;

    private static final int INTERNAL_MAXIMUM_PACKET_BYTES = 65_536;
    private static final byte[] PACKET_BODY = createPacketBody();
    private static final String PACKET_SHA256 = sha256(PACKET_BODY);

    private AdAstraGiselleEnchantmentRegistry() {
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
                            enchantment(
                                    "space_breathing",
                                    "head",
                                    "#minecraft:enchantable/head_armor"),
                            enchantment(
                                    "space_fire_proof",
                                    "chest",
                                    "#minecraft:enchantable/chest_armor"),
                            enchantment(
                                    "acid_rain_proof",
                                    "chest",
                                    "#minecraft:enchantable/chest_armor"),
                            enchantment(
                                    "gravity_normalizing",
                                    "feet",
                                    "#minecraft:enchantable/foot_armor")),
                    INTERNAL_MAXIMUM_PACKET_BYTES);
        } catch (ProtocolViolationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Entry enchantment(String name, String slot, String supportedItems) {
        LinkedHashMap<String, NbtValue> root = new LinkedHashMap<>();
        root.put("anvil_cost", MinecraftRegistryPacketCodec.intTag(1));
        root.put("description", translatedDescription(name));
        root.put("max_cost", zeroCost());
        root.put("max_level", MinecraftRegistryPacketCodec.intTag(1));
        root.put("min_cost", zeroCost());
        root.put("slots", MinecraftRegistryPacketCodec.stringList(slot));
        root.put("supported_items", MinecraftRegistryPacketCodec.stringTag(supportedItems));
        root.put("weight", MinecraftRegistryPacketCodec.intTag(10));
        return new Entry("ad_astra_giselle_addon:" + name, new CompoundTag(root));
    }

    private static CompoundTag translatedDescription(String name) {
        return new CompoundTag(Map.of(
                "translate",
                MinecraftRegistryPacketCodec.stringTag(
                        "enchantment.ad_astra_giselle_addon." + name)));
    }

    private static CompoundTag zeroCost() {
        LinkedHashMap<String, NbtValue> cost = new LinkedHashMap<>();
        cost.put("base", MinecraftRegistryPacketCodec.intTag(0));
        cost.put("per_level_above_first", MinecraftRegistryPacketCodec.intTag(0));
        return new CompoundTag(cost);
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

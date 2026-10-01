package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class CompatibilityPackTest {
    @Test
    void loadsEveryPartOfACapturedPack() throws Exception {
        CompatibilityPack pack = CompatibilityPack.load(
                "synthetic.obpack", CompatibilityPackFixtures.pack("synthetic-1"));

        assertEquals("synthetic-1", pack.packId());
        assertEquals("pack-synthetic-1", pack.catalogId());
        assertEquals(5, pack.serverChannels().channelCount());
        assertEquals(List.of("alpha-server.toml", "Nested/beta.toml"), pack.serverConfigNames());
        assertArrayEquals(
                CompatibilityPackFixtures.configPayload(
                        "alpha-server.toml", "value = 0\n".getBytes()),
                pack.serverConfigs().getFirst().encodedPayload());
        assertEquals(
                Atm10Normal81ServerConfigCatalog.nameSequenceSha256(pack.serverConfigNames()),
                pack.serverConfigNameSequenceSha256());

        // Registries Paper never sends are appended whole, in capture order.
        assertEquals(List.of("testmod:widgets", "testmod:biome_data"),
                pack.tailRegistries().stream().map(RegistryShimPacket::registryId).toList());
        assertTrue(pack.tailRegistries().stream()
                .allMatch(packet -> CompatibilityPack.isPackShimId(packet.shimId())));
        // Registries Paper sends are only extended with what vanilla 1.21.1 lacks; the
        // overridden vanilla plains stays Paper's.
        assertEquals(List.of("minecraft:worldgen/biome"),
                pack.paperRegistryExtensions().stream().map(RegistryShimPacket::registryId).toList());
        assertEquals(List.of("testmod:glow_biome", "minecraft:custom_peaks"),
                entryIds(pack.paperRegistryExtensions().getFirst()));
        assertTrue(pack.paperRegistryExtensions().stream()
                .allMatch(packet -> CompatibilityPack.isPackExtensionShimId(packet.shimId())
                        && !CompatibilityPack.isPackShimId(packet.shimId())));
        // The enchantment registry is both a whole replacement and a fallback extension.
        assertEquals(List.of("minecraft:sharpness", "testmod:shiny"),
                entryIds(pack.enchantmentRegistry().orElseThrow()));
        assertEquals(List.of("testmod:shiny"), entryIds(pack.enchantmentExtension().orElseThrow()));
        assertTrue(CompatibilityPack.isPackExtensionShimId(
                pack.enchantmentExtension().orElseThrow().shimId()));
        assertTrue(pack.quarantinedRegistries().isEmpty());

        assertTrue(pack.blockStates().isPresent());
        assertEquals(26_684, pack.blockStates().orElseThrow().sourceStateCount());
        assertEquals(List.of("minecraft:skeleton_skull#waterlogged"), pack.extraVanillaBlockProperties());
    }

    @Test
    void tagsAreOfferedOnlyForRegistriesThePackDelivers() throws Exception {
        CompatibilityPack pack = CompatibilityPack.load(
                "synthetic.obpack", CompatibilityPackFixtures.pack("synthetic-1"));

        Map<String, Map<String, int[]>> tailOnly = pack.tagsFor(Set.of("testmod:widgets"));
        assertEquals(Set.of("testmod:widgets"), tailOnly.keySet());
        assertArrayEquals(new int[] {0, 1}, tailOnly.get("testmod:widgets").get("testmod:all"));

        Map<String, Map<String, int[]>> withEnchantments =
                pack.tagsFor(Set.of("testmod:widgets", "minecraft:enchantment"));
        assertEquals(Set.of("testmod:widgets", "minecraft:enchantment"), withEnchantments.keySet());
        // Static registries such as blocks keep Paper's ids and are never re-tagged.
        assertFalse(pack.tagsFor(Set.of("minecraft:block")).containsKey("minecraft:block"));
    }

    @Test
    void anyEntryChangedAfterCaptureIsRejected() {
        byte[] tampered = CompatibilityPackFixtures.packWithoutManifestUpdate("synthetic-1",
                entries -> entries.put("server-configs/000.bin",
                        CompatibilityPackFixtures.configPayload("alpha-server.toml", new byte[] {1})));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CompatibilityPack.load("tampered.obpack", tampered));
        assertTrue(failure.getMessage().contains("hash mismatch"));
    }

    @Test
    void manifestMustListExactlyThePackEntries() {
        byte[] extra = CompatibilityPackFixtures.packWithoutManifestUpdate("synthetic-1",
                entries -> entries.put("extra.txt", new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> CompatibilityPack.load("x.obpack", extra));

        byte[] missing = CompatibilityPackFixtures.packWithoutManifestUpdate("synthetic-1",
                entries -> entries.remove("manifest.sha256"));
        assertThrows(IllegalArgumentException.class, () -> CompatibilityPack.load("x.obpack", missing));
    }

    @Test
    void unsafeEntryNamesAreRejected() {
        byte[] traversal = CompatibilityPackFixtures.pack("synthetic-1",
                entries -> entries.put("../escape.toml", new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> CompatibilityPack.load("x.obpack", traversal));
    }

    @Test
    void configPayloadMustNameItsManifestEntry() {
        byte[] renamed = CompatibilityPackFixtures.pack("synthetic-1", entries -> {
            byte[] payload = CompatibilityPackFixtures.configPayload("other-server.toml",
                    "value = 0\n".getBytes());
            entries.put("server-configs/000.bin", payload);
            String properties = new String(entries.get("server-configs.properties"));
            String newSha = CompatibilityPackFixtures.hex(CompatibilityPackFixtures.sha().digest(payload));
            properties = properties.replaceFirst("config.0.encoded-sha256=[0-9a-f]{64}",
                    "config.0.encoded-sha256=" + newSha);
            entries.put("server-configs.properties", properties.getBytes());
        });
        assertThrows(IllegalArgumentException.class, () -> CompatibilityPack.load("x.obpack", renamed));
    }

    @Test
    void tagMembersOutsideADeliveredRegistryAreRejected() {
        byte[] invalid = CompatibilityPackFixtures.pack("synthetic-1", entries -> entries.put(
                "tags/full-update-tags.bin",
                CompatibilityPackFixtures.tags(Map.of(
                        "testmod:widgets", Map.of("testmod:all", new int[] {0, 2})))));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CompatibilityPack.load("x.obpack", invalid));
        assertTrue(failure.getMessage().contains("outside testmod:widgets"));
    }

    @Test
    void extensionsCarryTheCapturedEntryBytesUnchanged() throws Exception {
        Map<String, byte[]> entries = CompatibilityPackFixtures.entries("synthetic-1", ignored -> { }, true);
        byte[] biome = entries.get("registries/wire-known-pack/001-minecraft_worldgen_biome.bin");
        CompatibilityPack pack = CompatibilityPack.load(
                "synthetic.obpack", CompatibilityPackFixtures.zip(entries));
        assertArrayEquals(
                MinecraftRegistryPacketCodec.selectEntries(
                        biome, id -> !id.equals("minecraft:plains"), 1_048_576).orElseThrow(),
                pack.paperRegistryExtensions().getFirst().packetBody());
    }

    @Test
    void aQuarantinedRegistryWithholdsOnlyTheRegistriesThatReferenceIt() throws Exception {
        // A non-vanilla biome that relies on a known-pack placeholder cannot be delivered.
        byte[] pack = CompatibilityPackFixtures.pack("synthetic-1", entries ->
                CompatibilityPackFixtures.replaceRegistry(entries, "minecraft:worldgen/biome",
                        CompatibilityPackFixtures.rawRegistry("minecraft:worldgen/biome", List.of(
                                new CompatibilityPackFixtures.RawEntry("minecraft:plains", null),
                                new CompatibilityPackFixtures.RawEntry("testmod:glow_biome", null)))));
        CompatibilityPack loaded = CompatibilityPack.load("x.obpack", pack);

        assertTrue(loaded.paperRegistryExtensions().isEmpty());
        // testmod:biome_data names testmod:glow_biome, so the client would fail to load it.
        assertEquals(List.of("testmod:widgets"),
                loaded.tailRegistries().stream().map(RegistryShimPacket::registryId).toList());
        assertTrue(loaded.enchantmentRegistry().isPresent());
        assertTrue(loaded.enchantmentExtension().isPresent());
        assertEquals(2, loaded.quarantinedRegistries().size());
        assertTrue(loaded.quarantinedRegistries().get(0).startsWith("minecraft:worldgen/biome ("));
        assertTrue(loaded.quarantinedRegistries().get(1).startsWith(
                "testmod:biome_data (withheld: references testmod:glow_biome)"));
    }

    @Test
    void anEntryOfAnotherRegistryWithTheSameIdNeverSatisfiesAReference() throws Exception {
        // testmod:widgets also has an entry named testmod:glow_biome; that is not a biome.
        byte[] pack = CompatibilityPackFixtures.pack("synthetic-1", entries -> {
            CompatibilityPackFixtures.replaceRegistry(entries, "minecraft:worldgen/biome",
                    CompatibilityPackFixtures.rawRegistry("minecraft:worldgen/biome", List.of(
                            new CompatibilityPackFixtures.RawEntry("minecraft:plains", null),
                            new CompatibilityPackFixtures.RawEntry("testmod:glow_biome", null))));
            CompatibilityPackFixtures.replaceRegistry(entries, "testmod:widgets",
                    CompatibilityPackFixtures.rawRegistry("testmod:widgets", List.of(
                            new CompatibilityPackFixtures.RawEntry("testmod:first", Map.of("color", "red")),
                            new CompatibilityPackFixtures.RawEntry(
                                    "testmod:glow_biome", Map.of("color", "blue")))));
        });
        CompatibilityPack loaded = CompatibilityPack.load("x.obpack", pack);
        assertEquals(List.of("testmod:widgets"),
                loaded.tailRegistries().stream().map(RegistryShimPacket::registryId).toList());
        assertTrue(loaded.quarantinedRegistries().stream().anyMatch(entry -> entry.startsWith(
                "testmod:biome_data (withheld: references testmod:glow_biome)")));
    }

    @Test
    void vanillaPlaceholdersAreTheOnlyOnesAPaperRegistryMayCarry() throws Exception {
        byte[] pack = CompatibilityPackFixtures.pack("synthetic-1", entries ->
                CompatibilityPackFixtures.replaceRegistry(entries, "minecraft:worldgen/biome",
                        CompatibilityPackFixtures.rawRegistry("minecraft:worldgen/biome", List.of(
                                new CompatibilityPackFixtures.RawEntry("minecraft:plains", null),
                                new CompatibilityPackFixtures.RawEntry(
                                        "testmod:glow_biome", Map.of("effects", "x"))))));
        CompatibilityPack loaded = CompatibilityPack.load("x.obpack", pack);
        assertTrue(loaded.quarantinedRegistries().isEmpty());
        assertEquals(List.of("testmod:glow_biome"),
                entryIds(loaded.paperRegistryExtensions().getFirst()));
    }

    @Test
    void anUnreadableRegistryWithholdsEveryRegistryDelivery() throws Exception {
        byte[] pack = CompatibilityPackFixtures.pack("synthetic-1", entries -> {
            String name = "registries/wire-known-pack/002-testmod_widgets.bin";
            byte[] broken = {0x01, 0x41};
            entries.put(name, broken);
            String manifest = new String(entries.get("registries/wire-known-pack.properties"));
            manifest = manifest.replaceFirst("packet.2.sha256=[0-9a-f]{64}", "packet.2.sha256="
                    + CompatibilityPackFixtures.hex(CompatibilityPackFixtures.sha().digest(broken)));
            entries.put("registries/wire-known-pack.properties", manifest.getBytes());
            entries.put("tags/full-update-tags.bin", CompatibilityPackFixtures.tags(Map.of()));
        });
        CompatibilityPack loaded = CompatibilityPack.load("x.obpack", pack);
        // Its entry ids are unknown, so no other registry can be proven not to reference them.
        assertTrue(loaded.tailRegistries().isEmpty());
        assertTrue(loaded.paperRegistryExtensions().isEmpty());
        assertTrue(loaded.enchantmentRegistry().isEmpty());
        assertTrue(loaded.enchantmentExtension().isEmpty());
        assertTrue(loaded.quarantinedRegistries().getFirst().startsWith("testmod:widgets ("));
        assertTrue(loaded.quarantinedRegistries().stream().skip(1)
                .allMatch(entry -> entry.contains("withheld: a quarantined registry")));
        // Everything else in the pack is unaffected.
        assertEquals(2, loaded.serverConfigs().size());
        assertTrue(loaded.blockStates().isPresent());
    }

    private static List<String> entryIds(RegistryShimPacket packet) throws Exception {
        return MinecraftRegistryPacketCodec.inspect(packet.packetBody(), 1_048_576).entryIds();
    }

    @Test
    void blockStateManifestMustBeKeyedToThePackId() {
        byte[] mismatched = CompatibilityPackFixtures.pack("synthetic-1", entries -> {
            String manifest = new String(entries.get("block-states/block-state-map.properties"));
            entries.put("block-states/block-state-map.properties",
                    manifest.replace("profile-id=synthetic-1", "profile-id=other").getBytes());
        });
        assertThrows(IllegalArgumentException.class, () -> CompatibilityPack.load("x.obpack", mismatched));
    }

    @Test
    void notAZipIsRejectedWithoutSideEffects() {
        assertThrows(IllegalArgumentException.class,
                () -> CompatibilityPack.load("x.obpack", new byte[] {1, 2, 3}));
    }

    @Test
    void serverQueryRoundTripsThroughTheLobbyDecoder() throws Exception {
        byte[] query = CompatibilityPackFixtures.query(
                CompatibilityPackFixtures.SERVER_CONFIGURATION, CompatibilityPackFixtures.SERVER_PLAY);
        NeoForgeHandshakeCodec.Registry registry = NeoForgeHandshakeCodec.decodeLobbyQuery(
                query, new br.com.atmbrasil.lobby.velocity.protocol.ProtocolLimits(
                        1_048_576, 1_048_576, 2, 16_384, 16_384, 1_024, 1_024));
        assertEquals(CompatibilityPackFixtures.SERVER_PLAY,
                registry.channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL));
    }
}

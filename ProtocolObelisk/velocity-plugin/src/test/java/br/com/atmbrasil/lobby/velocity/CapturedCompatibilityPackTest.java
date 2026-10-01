package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * End-to-end checks against packs really captured from official ServerFiles.
 *
 * <p>Captured packs are operator artifacts and are not committed. Run with
 * {@code -PprotocolObeliskRealPacks=/abs/dir/with/obpacks}; without it these tests are skipped.</p>
 */
final class CapturedCompatibilityPackTest {
    private static final ProtocolLimits LIMITS =
            new ProtocolLimits(1_048_576, 1_048_576, 2, 16_384, 16_384, 1_024, 1_024);

    @Test
    void everyCapturedPackLoadsCompletelyAndNegotiatesOnlyWithItself() throws Exception {
        List<CompatibilityPack> packs = loadRealPacks();
        assumeTrue(!packs.isEmpty(), "set -PprotocolObeliskRealPacks to run captured-pack checks");
        for (CompatibilityPack pack : packs) {
            assertTrue(pack.quarantinedRegistries().isEmpty(),
                    pack.packId() + " quarantined " + pack.quarantinedRegistries());
            assertTrue(pack.enchantmentRegistry().isPresent(), pack.packId());
            assertTrue(pack.enchantmentExtension().isPresent(), pack.packId());
            assertTrue(!pack.paperRegistryExtensions().isEmpty(), pack.packId());
            assertTrue(pack.blockStates().isPresent(), pack.packId());
            assertTrue(pack.serverConfigs().size() > 100, pack.packId());
            // A client advertising exactly this server's channels is what NeoForge would accept.
            CompatibilityPackSelector.Selection selection = CompatibilityPackSelector.select(
                    767, pack.serverChannels(), packs, "");
            assertEquals(CompatibilityPackSelector.Status.NEGOTIATED, selection.status(), pack.packId());
            assertEquals(pack.packId(), selection.pack().orElseThrow().packId());
            // Every delivered registry's tags must reference only entries of that registry.
            Set<String> delivered = new java.util.HashSet<>();
            pack.tailRegistries().forEach(packet -> delivered.add(packet.registryId()));
            delivered.add(CompatibilityPack.ENCHANTMENT_REGISTRY_ID);
            assertTrue(pack.tagsFor(delivered).size() >= 1, pack.packId());
        }
    }

    @Test
    void olderRealClientsNeverNegotiateANewerCapturedPack() throws Exception {
        List<CompatibilityPack> packs = loadRealPacks();
        assumeTrue(!packs.isEmpty(), "set -PprotocolObeliskRealPacks to run captured-pack checks");
        for (String fixture : List.of(
                "atm10-normal/7.3/client-neoforge-response.bin",
                "atm10-normal/8.0/client-neoforge-response.bin")) {
            NeoForgeHandshakeCodec.Registry client = NeoForgeHandshakeCodec.decodeLobbyQuery(
                    CompatibilityPackFixtures.resource(fixture), LIMITS);
            CompatibilityPackSelector.Selection selection =
                    CompatibilityPackSelector.select(767, client, packs, "");
            assertEquals(CompatibilityPackSelector.Status.NO_COMPATIBLE_PACK, selection.status(), fixture);
        }
    }

    /**
     * The client fails its whole registry load when a delivered entry names one it never
     * received. Every identifier string in every delivered packet that names an entry the real
     * server has must therefore be either Paper's vanilla entry or delivered by the pack, in both
     * enchantment modes. Entries are compared per registry: the same identifier is often an entry
     * of several registries, and one of them being delivered says nothing about the others.
     */
    @Test
    void everyReferenceInDeliveredRegistriesResolvesOnTheClient() throws Exception {
        List<Path> files = realPackFiles();
        assumeTrue(!files.isEmpty(), "set -PprotocolObeliskRealPacks to run captured-pack checks");
        for (Path file : files) {
            CompatibilityPack pack = CompatibilityPack.load(file);
            Set<String> serverEntries = new HashSet<>();
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    if (entry.getName().startsWith("registries/wire-known-pack/")
                            && entry.getName().endsWith(".bin")) {
                        MinecraftRegistryPacketCodec.Inspection inspection =
                                MinecraftRegistryPacketCodec.inspect(zip.readAllBytes(), 1_048_576);
                        inspection.entryIds().forEach(id ->
                                serverEntries.add(qualified(inspection.registryId(), id)));
                    }
                }
            }
            for (RegistryShimPacket extension : pack.paperRegistryExtensions()) {
                assertTrue(entryIds(extension).stream().noneMatch(id ->
                        Minecraft1211VanillaRegistries.isVanillaEntry(extension.registryId(), id)),
                        extension.registryId());
            }

            List<RegistryShimPacket> replaced = new ArrayList<>(pack.paperRegistryExtensions());
            replaced.addAll(pack.tailRegistries());
            replaced.add(pack.enchantmentRegistry().orElseThrow());
            List<RegistryShimPacket> extended = new ArrayList<>(pack.paperRegistryExtensions());
            extended.addAll(pack.tailRegistries());
            extended.add(pack.enchantmentExtension().orElseThrow());
            for (List<RegistryShimPacket> delivered : List.of(replaced, extended)) {
                Set<String> received = new HashSet<>();
                Minecraft1211VanillaRegistries.registries().forEach(registry ->
                        Minecraft1211VanillaRegistries.entries(registry).forEach(id ->
                                received.add(qualified(registry, id))));
                for (RegistryShimPacket packet : delivered) {
                    entryIds(packet).forEach(id -> received.add(qualified(packet.registryId(), id)));
                }
                Set<String> missingIds = new HashSet<>();
                for (String entry : serverEntries) {
                    if (!received.contains(entry)) {
                        missingIds.add(entry.substring(entry.indexOf('|') + 1));
                    }
                }
                for (RegistryShimPacket packet : delivered) {
                    for (String value : MinecraftRegistryPacketCodec.nbtStrings(
                            packet.packetBody(), 1_048_576)) {
                        String id = value.indexOf(':') < 0 ? "minecraft:" + value : value;
                        assertTrue(!missingIds.contains(id),
                                pack.packId() + ": " + packet.registryId() + " names " + id
                                        + " which the client would not receive");
                    }
                }
            }
        }
    }

    private static String qualified(String registryId, String entryId) {
        return registryId + "|" + entryId;
    }

    private static List<String> entryIds(RegistryShimPacket packet) throws Exception {
        return MinecraftRegistryPacketCodec.inspect(packet.packetBody(), 1_048_576).entryIds();
    }

    private static List<CompatibilityPack> loadRealPacks() throws Exception {
        List<CompatibilityPack> packs = new ArrayList<>();
        for (Path file : realPackFiles()) {
            packs.add(CompatibilityPack.load(file));
        }
        return packs;
    }

    private static List<Path> realPackFiles() throws Exception {
        String directory = System.getProperty("protocolobelisk.realPacks", "");
        if (directory.isBlank()) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(Path.of(directory))) {
            return files.filter(path -> path.toString().endsWith(".obpack")).sorted().toList();
        }
    }
}

package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class Atm10Normal81EnchantmentRegistryTest {
    private static final int MAXIMUM_PACKET_BYTES = 1_048_576;

    @Test
    void embeddedWireKnownPackExportMatchesEveryIndependentPin() throws Exception {
        Fixture fixture = embeddedFixture();
        RegistryShimPacket packet = Atm10Normal81EnchantmentRegistry.loadForTest(
                fixture.loader(), MAXIMUM_PACKET_BYTES);
        MinecraftRegistryPacketCodec.Inspection inspection =
                MinecraftRegistryPacketCodec.inspect(
                        packet.packetBody(), MAXIMUM_PACKET_BYTES);

        assertEquals(Atm10Normal81EnchantmentRegistry.SHIM_ID, packet.shimId());
        assertEquals("minecraft", packet.requiredNamespace());
        assertEquals(Atm10Normal81EnchantmentRegistry.REGISTRY_ID, packet.registryId());
        assertEquals(Atm10Normal81EnchantmentRegistry.ENTRY_COUNT, packet.entryCount());
        assertEquals(Atm10Normal81EnchantmentRegistry.PACKET_BYTES, packet.packetBytes());
        assertEquals(Atm10Normal81EnchantmentRegistry.PACKET_SHA256, packet.sha256());
        assertEquals(
                Integer.toString(Atm10Normal81EnchantmentRegistry.PACKET_SEQUENCE_INDEX),
                fixture.property("packet-sequence-index"));
        assertEquals(
                Integer.toString(Atm10Normal81EnchantmentRegistry.ENTRIES_WITH_DATA),
                fixture.property("entries-with-data"));
        assertEquals(
                Integer.toString(Atm10Normal81EnchantmentRegistry.KNOWN_PACK_PLACEHOLDERS),
                fixture.property("known-pack-placeholders"));
        assertEquals(
                Atm10Normal81EnchantmentRegistry.ENTRY_SEQUENCE_SHA256,
                fixture.property("entry-sequence-sha256"));
        assertEquals(
                Atm10Normal81EnchantmentRegistry.ENTRY_COUNT,
                inspection.entryIds().size());
        assertEquals(
                Atm10Normal81EnchantmentRegistry.ENTRIES_WITH_DATA,
                inspection.entriesWithData());
        assertTrue(inspection.entryIdsWithData().containsAll(
                Atm10Normal81EnchantmentRegistry.REQUIRED_SENTINEL_ENTRY_IDS));
        assertEquals(RegistryShimReceipt.from(packet), RegistryShimReceipt.from(packet));

        byte[] first = packet.packetBody();
        byte[] second = packet.packetBody();
        assertNotSame(first, second);
        assertArrayEquals(first, second);
        first[first.length - 1] ^= 0x01;
        assertArrayEquals(second, packet.packetBody());
    }

    @Test
    void bothResourcesMayBeAbsentButAnIncompletePairIsRejected() throws Exception {
        assertTrue(Atm10Normal81EnchantmentRegistry.loadIfPresentForTest(
                new ResourceClassLoader(null, null), MAXIMUM_PACKET_BYTES).isEmpty());

        Fixture fixture = embeddedFixture();
        assertThrows(IllegalStateException.class, () ->
                Atm10Normal81EnchantmentRegistry.loadIfPresentForTest(
                        new ResourceClassLoader(fixture.properties(), null),
                        MAXIMUM_PACKET_BYTES));
        assertThrows(IllegalStateException.class, () ->
                Atm10Normal81EnchantmentRegistry.loadIfPresentForTest(
                        new ResourceClassLoader(null, fixture.packet()),
                        MAXIMUM_PACKET_BYTES));
    }

    @Test
    void runtimeQuarantinesMissingOrCorruptEnrichmentWithoutThrowing() throws Exception {
        Atm10Normal81EnchantmentRegistry.RuntimeResolution missing =
                Atm10Normal81EnchantmentRegistry.runtimeResolutionForTest(
                        new ResourceClassLoader(null, null));
        assertTrue(missing.packet().isEmpty());
        assertTrue(missing.quarantineFailure().isPresent());

        Fixture fixture = embeddedFixture();
        Fixture corrupt = fixture.withProperty("packet-sha256", "0".repeat(64));
        Atm10Normal81EnchantmentRegistry.RuntimeResolution quarantined =
                Atm10Normal81EnchantmentRegistry.runtimeResolutionForTest(corrupt.loader());
        assertTrue(quarantined.packet().isEmpty());
        assertTrue(quarantined.quarantineFailure().isPresent());

        Atm10Normal81EnchantmentRegistry.RuntimeResolution valid =
                Atm10Normal81EnchantmentRegistry.runtimeResolutionForTest(fixture.loader());
        assertTrue(valid.packet().isPresent());
        assertTrue(valid.quarantineFailure().isEmpty());
    }

    @Test
    void everyExactIdentityAndSizePropertyIsIndependentlyPinned() throws Exception {
        Fixture fixture = embeddedFixture();
        for (Map.Entry<String, String> mutation : Map.ofEntries(
                        Map.entry("packet-sha256", "0".repeat(64)),
                        Map.entry("packet-bytes", "63658"),
                        Map.entry("entry-count", "138"),
                        Map.entry("entries-with-data", "99"),
                        Map.entry("known-pack-placeholders", "40"),
                        Map.entry("packet-sequence-index", "10"),
                        Map.entry("entry-sequence-sha256", "1".repeat(64)),
                        Map.entry("registry-id", "example:enchantment"),
                        Map.entry("full-client-contract-sha256", "2".repeat(64)))
                .entrySet()) {
            Fixture mutated = fixture.withProperty(mutation.getKey(), mutation.getValue());
            assertThrows(IllegalStateException.class, () ->
                    Atm10Normal81EnchantmentRegistry.loadForTest(
                            mutated.loader(), MAXIMUM_PACKET_BYTES), mutation.getKey());
        }
    }

    @Test
    void selfConsistentMutatedPayloadAndMetadataCannotReplaceIndependentPins()
            throws Exception {
        Fixture exact = embeddedFixture();
        byte[] mutatedPacket = exact.packet();
        byte[] needle = "enchantment.eternal_starlight.abyssal_touch"
                .getBytes(StandardCharsets.UTF_8);
        int offset = indexOf(mutatedPacket, needle);
        assertTrue(offset >= 0, "reviewed NBT translation string was not found");
        mutatedPacket[offset + needle.length - 1] = (byte) 'i';

        MinecraftRegistryPacketCodec.Inspection inspection =
                MinecraftRegistryPacketCodec.inspect(
                        mutatedPacket, MAXIMUM_PACKET_BYTES);
        assertEquals(
                Atm10Normal81EnchantmentRegistry.ENTRY_COUNT,
                inspection.entryIds().size());
        assertEquals(
                Atm10Normal81EnchantmentRegistry.ENTRIES_WITH_DATA,
                inspection.entriesWithData());

        Fixture selfConsistentMutation = exact.withPacket(mutatedPacket);
        assertEquals(
                sha256(mutatedPacket), selfConsistentMutation.property("packet-sha256"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                Atm10Normal81EnchantmentRegistry.loadForTest(
                        selfConsistentMutation.loader(), MAXIMUM_PACKET_BYTES));
        assertTrue(failure.getMessage().contains("packet-sha256"));
    }

    @Test
    void packetBudgetTrailingBytesAndCanonicalPropertyGrammarAreFailClosed()
            throws Exception {
        Fixture fixture = embeddedFixture();
        assertThrows(IllegalArgumentException.class, () ->
                Atm10Normal81EnchantmentRegistry.loadForTest(
                        fixture.loader(), Atm10Normal81EnchantmentRegistry.PACKET_BYTES - 1));

        byte[] trailingPacket = Arrays.copyOf(
                fixture.packet(), Atm10Normal81EnchantmentRegistry.PACKET_BYTES + 1);
        Fixture trailing = fixture.withPacket(trailingPacket);
        assertThrows(IllegalStateException.class, () ->
                Atm10Normal81EnchantmentRegistry.loadForTest(
                        trailing.loader(), MAXIMUM_PACKET_BYTES));

        byte[] duplicateProperty = (new String(
                        fixture.properties(), StandardCharsets.US_ASCII)
                + "entry-count=139\n").getBytes(StandardCharsets.US_ASCII);
        assertThrows(IllegalStateException.class, () ->
                Atm10Normal81EnchantmentRegistry.loadForTest(
                        new ResourceClassLoader(duplicateProperty, fixture.packet()),
                        MAXIMUM_PACKET_BYTES));

        byte[] commentedProperty = ("# unreviewed comment\n"
                + new String(fixture.properties(), StandardCharsets.US_ASCII))
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IllegalStateException.class, () ->
                Atm10Normal81EnchantmentRegistry.loadForTest(
                        new ResourceClassLoader(commentedProperty, fixture.packet()),
                        MAXIMUM_PACKET_BYTES));
    }

    private static Fixture embeddedFixture() throws IOException {
        ClassLoader loader = Atm10Normal81EnchantmentRegistry.class.getClassLoader();
        return new Fixture(
                requiredResource(loader, Atm10Normal81EnchantmentRegistry.PROPERTIES_RESOURCE),
                requiredResource(loader, Atm10Normal81EnchantmentRegistry.PACKET_RESOURCE));
    }

    private static byte[] requiredResource(ClassLoader loader, String name) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(name)) {
            if (stream == null) {
                throw new AssertionError("required embedded test resource is missing: " + name);
            }
            return stream.readAllBytes();
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            int index = 0;
            while (index < needle.length && haystack[start + index] == needle[index]) {
                index++;
            }
            if (index == needle.length) {
                return start;
            }
        }
        return -1;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private record Fixture(byte[] properties, byte[] packet) {
        private Fixture {
            properties = properties.clone();
            packet = packet.clone();
        }

        @Override
        public byte[] properties() {
            return properties.clone();
        }

        @Override
        public byte[] packet() {
            return packet.clone();
        }

        private String property(String key) {
            String prefix = key + '=';
            return new String(properties, StandardCharsets.US_ASCII).lines()
                    .filter(line -> line.startsWith(prefix))
                    .map(line -> line.substring(prefix.length()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "missing fixture property: " + key));
        }

        private Fixture withProperty(String key, String value) {
            String text = new String(properties, StandardCharsets.US_ASCII);
            String prefix = key + '=';
            StringBuilder replaced = new StringBuilder();
            boolean found = false;
            for (String line : text.split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith(prefix)) {
                    replaced.append(prefix).append(value).append('\n');
                    found = true;
                } else {
                    replaced.append(line).append('\n');
                }
            }
            if (!found) {
                throw new AssertionError("missing fixture property: " + key);
            }
            return new Fixture(
                    replaced.toString().getBytes(StandardCharsets.US_ASCII), packet);
        }

        private Fixture withPacket(byte[] replacement) {
            Fixture changedLength = withProperty(
                    "packet-bytes", Integer.toString(replacement.length));
            Fixture changedDigest = changedLength.withProperty(
                    "packet-sha256", sha256(replacement));
            return new Fixture(changedDigest.properties, replacement);
        }

        private ClassLoader loader() {
            return new ResourceClassLoader(properties, packet);
        }
    }

    private static final class ResourceClassLoader extends ClassLoader {
        private final byte[] properties;
        private final byte[] packet;

        private ResourceClassLoader(byte[] properties, byte[] packet) {
            super(null);
            this.properties = properties == null ? null : properties.clone();
            this.packet = packet == null ? null : packet.clone();
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (name.equals(Atm10Normal81EnchantmentRegistry.PROPERTIES_RESOURCE)
                    && properties != null) {
                return new ByteArrayInputStream(properties);
            }
            if (name.equals(Atm10Normal81EnchantmentRegistry.PACKET_RESOURCE)
                    && packet != null) {
                return new ByteArrayInputStream(packet);
            }
            return null;
        }
    }
}

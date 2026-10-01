package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NeoForgeFrozenRegistryProfileTest {
    private static final int PROTOCOL = 767;
    private static final int PAYLOAD_LIMIT = 1_048_576;
    private static final int TOTAL_LIMIT = 3_145_728;

    @Test
    void reviewedAttributeSnapshotContainsTheFormerlyMissingNumericId() throws Exception {
        NeoForgeFrozenRegistryProfile profile = load(defaultLoader()).frozenRegistries();
        NeoForgeFrozenRegistryProfile.RegistryPayload attributes = profile.registryPayloads()
                .stream()
                .filter(payload -> payload.registryName().equals("minecraft:attribute"))
                .findFirst()
                .orElseThrow();
        Cursor cursor = new Cursor(attributes.bytes());

        assertEquals("minecraft:attribute", cursor.readString());
        assertEquals(118, cursor.readVarInt());
        String id115 = null;
        for (int index = 0; index < attributes.entryCount(); index++) {
            int id = cursor.readVarInt();
            String name = cursor.readString();
            if (id == 115) {
                id115 = name;
            }
        }
        assertEquals("modern_industrialization:infinite_damage", id115);
        assertEquals(2, cursor.readVarInt());
    }

    @Test
    void transactionPayloadsAndConfigurationContractAreDefensiveAndExact() throws Exception {
        NeoForgeFrozenRegistryProfile profile = load(defaultLoader()).frozenRegistries();
        byte[] first = profile.registryPayloads().getFirst().bytes();
        byte[] original = first.clone();
        first[0] ^= 0x7F;

        assertArrayEquals(original, profile.registryPayloads().getFirst().bytes());
        assertTrue(NeoForgeFrozenRegistryProfile.inspectConfigurationChannels(
                NeoForgeFrozenRegistryProfile.reviewedConfigurationChannels()).exact());
        List<Channel> wrong = new ArrayList<>(
                NeoForgeFrozenRegistryProfile.reviewedConfigurationChannels());
        wrong.set(2, new Channel(
                NeoForgeFrozenRegistryProfile.REGISTRY_CHANNEL,
                "2",
                Flow.CLIENTBOUND,
                true));
        assertFalse(NeoForgeFrozenRegistryProfile.inspectConfigurationChannels(wrong).exact());
    }

    @Test
    void corruptedFrozenRegistryFailsClosedBeforeTheProfileIsPublished() {
        String target = SilentGearEmbeddedProfile.RESOURCE_ROOT
                + "frozen-registries/041-minecraft_attribute.bin";
        ClassLoader delegate = defaultLoader();
        ClassLoader corrupted = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                try (InputStream original = delegate.getResourceAsStream(name)) {
                    if (original == null) {
                        return null;
                    }
                    byte[] bytes = original.readAllBytes();
                    if (name.equals(target)) {
                        bytes[bytes.length - 1] ^= 0x01;
                    }
                    return new ByteArrayInputStream(bytes);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        };

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> load(corrupted));
        assertTrue(exception.getMessage().contains("hash mismatch"));
    }

    private static SilentGearEmbeddedProfile load(ClassLoader loader) throws IOException {
        return SilentGearEmbeddedProfile.loadReviewed(
                loader, PROTOCOL, PAYLOAD_LIMIT, TOTAL_LIMIT);
    }

    private static ClassLoader defaultLoader() {
        return NeoForgeFrozenRegistryProfileTest.class.getClassLoader();
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int index;

        private Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        private int readVarInt() {
            int value = 0;
            for (int position = 0; position < 5; position++) {
                int current = bytes[index++] & 0xFF;
                value |= (current & 0x7F) << (position * 7);
                if ((current & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException("oversized test VarInt");
        }

        private String readString() {
            int length = readVarInt();
            String value = new String(bytes, index, length, StandardCharsets.UTF_8);
            index += length;
            return value;
        }
    }
}

package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

final class EmbeddedRegistryTagsProfileTest {
    private static final String ROOT =
            SilentGearEmbeddedProfile.ATM10_NORMAL_8_0_RESOURCE_ROOT;

    @Test
    void reviewedNeoVitaeTagPacketLoadsExactly() throws Exception {
        EmbeddedRegistryTagsProfile profile =
                EmbeddedRegistryTagsProfile.loadAtm10Normal80(defaultLoader());

        assertEquals("neovitae:sentient_upgrades", profile.registryId());
        assertEquals(6, profile.tags().size());
        assertEquals(101, profile.totalMembers());
        assertEquals(267, profile.packetBytes());
        assertEquals(
                "3d11e2231295e07d3a5bbd1326defa18e1b519cc64f75b6e8f2903e9101c6001",
                profile.sha256());
        assertTag(profile, "neovitae:sentient_start", 15);
        assertTag(profile, "neovitae:tooltip_order", 28);
        assertTag(profile, "neovitae:trainer", 15);
        assertTrue(profile.tags().stream()
                .flatMapToInt(tag -> java.util.Arrays.stream(tag.memberIds()))
                .allMatch(member -> member >= 0 && member < 44));
    }

    @Test
    void packetAndVelocityMapRemainDefensivelyOwned() throws Exception {
        EmbeddedRegistryTagsProfile profile =
                EmbeddedRegistryTagsProfile.loadAtm10Normal80(defaultLoader());
        byte[] firstBody = profile.packetBody();
        byte[] expectedBody = firstBody.clone();
        firstBody[firstBody.length - 1] ^= 0x01;

        Map<String, Map<String, int[]>> firstMap = profile.velocityTagMap();
        int[] firstMembers = firstMap.get(profile.registryId()).get("neovitae:trainer");
        int[] expectedMembers = firstMembers.clone();
        firstMembers[0] = 43;

        assertArrayEquals(expectedBody, profile.packetBody());
        assertArrayEquals(
                expectedMembers,
                profile.velocityTagMap().get(profile.registryId()).get("neovitae:trainer"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> firstMap.put("minecraft:block", Map.of()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> firstMap.get(profile.registryId()).put("neovitae:fake", new int[] {0}));
    }

    @Test
    void corruptedPacketOrManifestFailsClosed() {
        ClassLoader corruptedPacket = mutatingLoader(
                ROOT + "dynamic-registry-tags.bin",
                bytes -> {
                    bytes[bytes.length - 1] ^= 0x01;
                    return bytes;
                });
        ClassLoader missingRequiredTag = mutatingLoader(
                ROOT + "dynamic-registry-tags.properties",
                bytes -> new String(bytes, StandardCharsets.ISO_8859_1)
                        .replace("tag.5.name=neovitae:trainer", "tag.5.name=neovitae:fake")
                        .getBytes(StandardCharsets.ISO_8859_1));
        ClassLoader changedMembership = mutatingLoader(
                ROOT + "dynamic-registry-tags.properties",
                bytes -> new String(bytes, StandardCharsets.ISO_8859_1)
                        .replace(
                                "tag.2.member-ids=7,16,10,8,9,11,12,13,14,15,17,18,19,20,21",
                                "tag.2.member-ids=7,16,10,8,9,11,12,13,14,15,17,18,19,20,22")
                        .getBytes(StandardCharsets.ISO_8859_1));

        for (ClassLoader mutation : List.of(
                corruptedPacket, missingRequiredTag, changedMembership)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> EmbeddedRegistryTagsProfile.loadAtm10Normal80(mutation));
        }
    }

    @Test
    void absentProfileResourceFailsClosed() {
        ClassLoader missing = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }
        };

        assertThrows(
                IOException.class,
                () -> EmbeddedRegistryTagsProfile.loadAtm10Normal80(missing));
    }

    @Test
    void malformedPacketStructuresFailClosed() throws Exception {
        byte[] exact = EmbeddedRegistryTagsProfile
                .loadAtm10Normal80(defaultLoader())
                .packetBody();
        byte[] truncated = java.util.Arrays.copyOf(exact, exact.length - 1);
        byte[] trailing = java.util.Arrays.copyOf(exact, exact.length + 1);
        byte[] nonCanonicalRegistryCount = new byte[exact.length + 1];
        nonCanonicalRegistryCount[0] = (byte) 0x81;
        nonCanonicalRegistryCount[1] = 0;
        System.arraycopy(exact, 1, nonCanonicalRegistryCount, 2, exact.length - 1);

        for (byte[] invalid : List.of(
                truncated,
                trailing,
                nonCanonicalRegistryCount,
                packetWithMembers(0, 0),
                packetWithMembers(44))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> EmbeddedRegistryTagsProfile.validatePacketStructure(invalid));
        }
    }

    private static void assertTag(
            EmbeddedRegistryTagsProfile profile, String tagId, int members) {
        EmbeddedRegistryTagsProfile.TagEntry tag = profile.tags().stream()
                .filter(candidate -> candidate.tagId().equals(tagId))
                .findFirst()
                .orElseThrow();
        assertEquals(members, tag.memberIds().length);
    }

    private static ClassLoader defaultLoader() {
        return EmbeddedRegistryTagsProfileTest.class.getClassLoader();
    }

    private static ClassLoader mutatingLoader(
            String targetResource, UnaryOperator<byte[]> mutation) {
        ClassLoader delegate = defaultLoader();
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                try (InputStream original = delegate.getResourceAsStream(name)) {
                    if (original == null) {
                        return null;
                    }
                    byte[] bytes = original.readAllBytes();
                    if (name.equals(targetResource)) {
                        bytes = mutation.apply(bytes);
                    }
                    return new ByteArrayInputStream(bytes);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        };
    }

    private static byte[] packetWithMembers(int... members) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarInt(output, 1);
        writeString(output, "neovitae:sentient_upgrades");
        writeVarInt(output, 1);
        writeString(output, "neovitae:test");
        writeVarInt(output, members.length);
        for (int member : members) {
            writeVarInt(output, member);
        }
        return output.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        int remaining = value;
        do {
            int next = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                next |= 0x80;
            }
            output.write(next);
        } while (remaining != 0);
    }
}

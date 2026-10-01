package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class VelocityTagsInjectorTest {
    @Test
    void adapterUsesVelocityMappingAndStandardPacketWithoutHardCodedId() throws Exception {
        Path sourcePath = Path.of(
                "src/main/java/br/com/atmbrasil/lobby/velocity/VelocityTagsInjector.java");
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);

        assertTrue(source.contains(
                "com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket"));
        assertTrue(source.contains("getPacketId"));
        assertTrue(source.contains("packetConstructor.newInstance(profile.velocityTagMap())"));
        assertTrue(source.contains("currentState != configurationState"));
        assertFalse(source.contains("setAccessible"));
        assertFalse(source.contains("DeferredByteBufHolder"));
    }
}

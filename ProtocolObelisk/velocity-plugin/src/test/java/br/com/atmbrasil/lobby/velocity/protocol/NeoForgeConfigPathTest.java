package br.com.atmbrasil.lobby.velocity.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NeoForgeConfigPathTest {
    @Test
    void acceptsFlatAndNestedCanonicalTomlIdentifiers() {
        assertTrue(NeoForgeConfigPath.isValid("securitycraft-server.toml"));
        assertTrue(NeoForgeConfigPath.isValid("ars_nouveau/rewind.toml"));
        assertTrue(NeoForgeConfigPath.isValid("example/nested/config.toml"));
        assertTrue(NeoForgeConfigPath.isValid("Mekanism/general.toml"));
        assertTrue(NeoForgeConfigPath.isValid("Mekanism/generators-gear.toml"));
    }

    @Test
    void rejectsTraversalAbsolutePlatformAndMalformedPaths() {
        assertFalse(NeoForgeConfigPath.isValid(null));
        assertFalse(NeoForgeConfigPath.isValid(""));
        assertFalse(NeoForgeConfigPath.isValid("../rewind.toml"));
        assertFalse(NeoForgeConfigPath.isValid("ars_nouveau/../rewind.toml"));
        assertFalse(NeoForgeConfigPath.isValid("/ars_nouveau/rewind.toml"));
        assertFalse(NeoForgeConfigPath.isValid("C:/Mekanism/general.toml"));
        assertFalse(NeoForgeConfigPath.isValid("ars_nouveau\\rewind.toml"));
        assertFalse(NeoForgeConfigPath.isValid("ars_nouveau//rewind.toml"));
        assertFalse(NeoForgeConfigPath.isValid("Mekanism/./general.toml"));
        assertFalse(NeoForgeConfigPath.isValid("Mekanism/.hidden.toml"));
        assertFalse(NeoForgeConfigPath.isValid("Mekanism/general\n.toml"));
        assertFalse(NeoForgeConfigPath.isValid("ars_nouveau/rewind config.toml"));
        assertFalse(NeoForgeConfigPath.isValid("Mékanism/general.toml"));
        assertFalse(NeoForgeConfigPath.isValid("ars_nouveau/rewind.txt"));
        assertFalse(NeoForgeConfigPath.isValid("a".repeat(124) + ".toml"));
    }
}

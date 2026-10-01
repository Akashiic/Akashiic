package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.CompoundTag;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.Entry;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class Atm10Normal82GiselleEnchantmentExtensionTest {
    private static final int MAXIMUM_BYTES = 1_048_576;

    @Test
    void reviewedExtensionLoadsWithPinnedBytesAndExactFourEntries() throws Exception {
        RegistryShimPacket packet = Atm10Normal82GiselleEnchantmentExtension.packet(
                MAXIMUM_BYTES);
        var inspection = MinecraftRegistryPacketCodec.inspect(
                packet.packetBody(), MAXIMUM_BYTES);

        assertEquals(Atm10Normal82GiselleEnchantmentExtension.SHIM_ID, packet.shimId());
        assertEquals(Atm10Normal82GiselleEnchantmentExtension.PACKET_BYTES,
                packet.packetBytes());
        assertEquals(Atm10Normal82GiselleEnchantmentExtension.PACKET_SHA256,
                packet.sha256());
        assertEquals(Atm10Normal82GiselleEnchantmentExtension.ENTRY_IDS,
                inspection.entryIds());
        assertEquals(4, inspection.entriesWithData());
    }

    @Test
    void extensionIsNotAFullRegistryReplacementAndDoesNotWeaken81Gate() {
        RegistryShimPacket full81 = Atm10Normal81EnchantmentRegistry.packet(MAXIMUM_BYTES);

        assertTrue(RegistryShimCatalog.selectPaperRegistryReplacement(
                        767,
                        List.of(full81),
                        Atm10Normal81EnchantmentRegistry.FULL_CLIENT_CONTRACT_SHA256)
                .isPresent());
        assertFalse(RegistryShimCatalog.selectPaperRegistryReplacement(
                        767, List.of(full81), "not-the-8.1-structural-contract")
                .isPresent());
    }

    @Test
    void duplicateReviewedIdIsRejectedInsteadOfOverridingPaper() throws Exception {
        RegistryShimPacket extension = Atm10Normal82GiselleEnchantmentExtension.packet(
                MAXIMUM_BYTES);
        byte[] paper = MinecraftRegistryPacketCodec.encode(
                Atm10Normal82GiselleEnchantmentExtension.REGISTRY_ID,
                List.of(new Entry(
                        "ad_astra_giselle_addon:space_breathing",
                        new CompoundTag(Map.of()))),
                MAXIMUM_BYTES);

        assertThrows(
                ProtocolViolationException.class,
                () -> MinecraftRegistryPacketCodec.mergeDistinctEntries(
                        paper, extension.packetBody(), MAXIMUM_BYTES));
    }
}

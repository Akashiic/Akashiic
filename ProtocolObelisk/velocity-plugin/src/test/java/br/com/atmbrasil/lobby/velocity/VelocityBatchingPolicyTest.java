package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class VelocityBatchingPolicyTest {
    @Test
    void frozenTransactionsCompileToOneReusableExactWirePlanPerProfile() throws Exception {
        SilentGearProfileCatalog profiles = SilentGearProfileCatalog.loadReviewed(
                VelocityBatchingPolicyTest.class.getClassLoader(),
                767,
                1_048_576,
                3_145_728);

        assertFalse(profiles.profiles().isEmpty());
        for (SilentGearEmbeddedProfile profile : profiles.profiles()) {
            VelocityPluginMessageBatchSender.Batch batch =
                    VelocityPluginMessageBatchSender.Batch
                            .frozenRegistryTransaction(profile);
            assertEquals(profile.profileId(), batch.profileId());
            assertEquals(profile.frozenRegistries().registryCount() + 2, batch.packetCount());
            assertEquals(profile.frozenRegistries().totalTransactionBytes(), batch.totalBytes());
            assertEquals(profile.frozenRegistries().sequenceSha256(), batch.sequenceSha256());
        }
    }

    @Test
    void adaptersPreservePacketOrderAndUseExactlyOneFinalFlushPerBatch() throws Exception {
        String pluginBatchSource = Files.readString(Path.of(
                "src/main/java/br/com/atmbrasil/lobby/velocity/"
                        + "VelocityPluginMessageBatchSender.java"), StandardCharsets.UTF_8);
        String registryBatchSource = Files.readString(Path.of(
                "src/main/java/br/com/atmbrasil/lobby/velocity/VelocityRegistryInjector.java"),
                StandardCharsets.UTF_8);

        assertTrue(pluginBatchSource.contains("delayedWriteMethod.invoke"));
        assertTrue(pluginBatchSource.contains("writeMethod.invoke"));
        assertTrue(pluginBatchSource.contains("sendPlay(Player player, Batch batch)"));
        assertTrue(pluginBatchSource.contains("Velocity StateRegistry.PLAY is unavailable"));
        assertTrue(pluginBatchSource.indexOf("delayedWriteMethod.invoke")
                < pluginBatchSource.indexOf("writeMethod.invoke"));
        assertTrue(registryBatchSource.contains("injectBatch("));
        assertTrue(registryBatchSource.contains("delayedWriteMethod.invoke"));
        assertTrue(registryBatchSource.contains("writeMethod.invoke"));
        assertFalse(pluginBatchSource.contains("setAccessible"));
        assertFalse(registryBatchSource.contains("setAccessible"));
    }
}

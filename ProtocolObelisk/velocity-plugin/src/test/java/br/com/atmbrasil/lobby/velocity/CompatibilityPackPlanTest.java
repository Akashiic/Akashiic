package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class CompatibilityPackPlanTest {
    @Test
    void packReplacesTheBaselineWithItsCompleteCapturedTransaction() throws Exception {
        CompatibilityPack pack = pack();
        TransientServerConfigPlanner.Plan base = TransientServerConfigPlanner.plan(
                List.of("neoforge-server.toml", "alpha-server.toml"), List.of(), false, 0);

        TransientServerConfigPlanner.Plan plan =
                TransientServerConfigPlanner.withCompatibilityPack(base, pack, true);

        assertEquals(pack.serverConfigNames(), plan.configs());
        assertTrue(plan.reviewedCatalogApplied());
        assertEquals("pack-synthetic-1", plan.reviewedCatalogId());
        assertEquals(pack.serverConfigPayloadSequenceSha256(),
                plan.reviewedCatalogPayloadSequenceSha256());
        assertEquals(1, plan.retainedReviewedCatalogConfigCount());
        assertEquals(1, plan.addedReviewedCatalogConfigCount());
        assertEquals(1, plan.discardedBaseConfigCount());
        assertEquals(pack.serverConfigEncodedBytes(), plan.reviewedCatalogEncodedBytes());
    }

    @Test
    void emptyModeKeepsEveryNameWithDistinctPayloadIdentity() throws Exception {
        CompatibilityPack pack = pack();
        TransientServerConfigPlanner.Plan base = TransientServerConfigPlanner.plan(
                List.of(), List.of(), false, 0);
        TransientServerConfigPlanner.Plan empty =
                TransientServerConfigPlanner.withCompatibilityPack(base, pack, false);

        assertEquals(pack.serverConfigNames(), empty.configs());
        assertTrue(TransientServerConfigPlanner.isCompatibilityPackCatalog(empty.reviewedCatalogId()));
        assertTrue(empty.reviewedCatalogId().endsWith(TransientServerConfigPlanner.EMPTY_CONTENTS_SUFFIX));
        assertNotEquals(pack.serverConfigPayloadSequenceSha256(),
                empty.reviewedCatalogPayloadSequenceSha256());
    }

    @Test
    void emptyPayloadHashingMatchesTheBytesActuallySent() throws Exception {
        for (String name : List.of("alpha-server.toml", "Mekanism/general.toml", "a".repeat(123) + ".toml")) {
            assertArrayEquals(
                    NeoForgeHandshakeCodec.encodeConfigFilePayload(name, new byte[0], 1_048_576),
                    TransientServerConfigPlanner.emptyConfigPayload(name));
        }
    }

    @Test
    void aReviewedCatalogIsNeverOverlaidByAPack() throws Exception {
        TransientServerConfigPlanner.Plan base = TransientServerConfigPlanner.plan(
                List.of(), List.of(), false, 0);
        TransientServerConfigPlanner.Plan withPack =
                TransientServerConfigPlanner.withCompatibilityPack(base, pack(), true);
        assertThrows(IllegalArgumentException.class,
                () -> TransientServerConfigPlanner.withCompatibilityPack(withPack, pack(), true));
    }

    @Test
    void recipeReleaseRecordsTheCompatibilityPackBasis() throws Exception {
        CompatibilityPack pack = pack();
        PaperRecipeLifecyclePolicy.Evaluation evaluation = PaperRecipeLifecyclePolicy.decide(
                new PaperRecipeLifecyclePolicy.Context(
                        true,
                        true,
                        true,
                        "b".repeat(64),
                        Set.of("testmod"),
                        Set.of(),
                        pack.serverConfigNames(),
                        pack.catalogId(),
                        pack.serverConfigNameSequenceSha256(),
                        pack.serverConfigPayloadSequenceSha256(),
                        pack.serverConfigEncodedBytes(),
                        Set.of()),
                Optional.empty(),
                Optional.empty());
        assertTrue(evaluation.release());
        assertEquals(PaperRecipeLifecyclePolicy.Basis.COMPATIBILITY_PACK, evaluation.basis());

        PaperRecipeLifecyclePolicy.Evaluation incomplete = PaperRecipeLifecyclePolicy.decide(
                new PaperRecipeLifecyclePolicy.Context(
                        true,
                        false,
                        true,
                        "b".repeat(64),
                        Set.of("testmod"),
                        Set.of(),
                        pack.serverConfigNames(),
                        pack.catalogId(),
                        pack.serverConfigNameSequenceSha256(),
                        pack.serverConfigPayloadSequenceSha256(),
                        pack.serverConfigEncodedBytes(),
                        Set.of()),
                Optional.empty(),
                Optional.empty());
        assertTrue(!incomplete.release());
    }

    private static CompatibilityPack pack() throws Exception {
        return CompatibilityPack.load("synthetic.obpack", CompatibilityPackFixtures.pack("synthetic-1"));
    }
}

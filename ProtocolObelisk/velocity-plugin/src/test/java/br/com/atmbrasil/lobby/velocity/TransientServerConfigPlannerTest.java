package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class TransientServerConfigPlannerTest {
    @Test
    void provenBaselineStaysFirstAndClientNamespacesDeriveDeterministically() {
        TransientServerConfigPlanner.Plan plan = TransientServerConfigPlanner.plan(
                List.of("securitycraft-server.toml", "utilitarian-server.toml"),
                List.of("zeta", "utilitarian", "example", "example"),
                true,
                16);

        assertEquals(
                List.of(
                        "securitycraft-server.toml",
                        "utilitarian-server.toml",
                        "example-server.toml",
                        "zeta-server.toml"),
                plan.configs());
        assertEquals(2, plan.derivedConfigCount());
        assertEquals(0, plan.omittedDerivedConfigCount());
        assertEquals(0, plan.ignoredNamespaceCount());
        assertFalse(plan.reviewedCatalogApplied());
    }

    @Test
    void derivationIsBoundedAndRejectsPathLikeOrOversizedCandidates() {
        TransientServerConfigPlanner.Plan plan = TransientServerConfigPlanner.plan(
                List.of("baseline-server.toml"),
                List.of("valid", "later", "bad..namespace", "a".repeat(117)),
                true,
                1);

        assertEquals(
                List.of("baseline-server.toml", "later-server.toml"),
                plan.configs());
        assertEquals(1, plan.derivedConfigCount());
        assertEquals(1, plan.omittedDerivedConfigCount());
        assertEquals(2, plan.ignoredNamespaceCount());
    }

    @Test
    void disabledDerivationReturnsOnlyTheReviewedBaseline() {
        TransientServerConfigPlanner.Plan plan = TransientServerConfigPlanner.plan(
                List.of("securitycraft-server.toml", "ars_nouveau/rewind.toml"),
                List.of("utilitarian"),
                false,
                512);

        assertEquals(
                List.of("securitycraft-server.toml", "ars_nouveau/rewind.toml"),
                plan.configs());
        assertEquals(0, plan.derivedConfigCount());
        assertEquals(TransientServerConfigPlanner.NO_REVIEWED_CATALOG, plan.reviewedCatalogId());
    }

    @Test
    void malformedBaselineAndNegativeLimitFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                TransientServerConfigPlanner.plan(
                        List.of("../unsafe-server.toml"), List.of(), true, 1));
        assertThrows(IllegalArgumentException.class, () ->
                TransientServerConfigPlanner.plan(
                        List.of("ars_nouveau//rewind.toml"), List.of(), true, 1));
        assertThrows(IllegalArgumentException.class, () ->
                TransientServerConfigPlanner.plan(
                        List.of("safe-server.toml"), List.of(), true, -1));
        assertThrows(IllegalArgumentException.class, () ->
                TransientServerConfigPlanner.plan(
                        List.of("safe-server.toml", "safe-server.toml"),
                        List.of(),
                        true,
                        1));
    }

    @Test
    void exactAgentPlanUsesOnlyTheAuthenticatedRealConfigSet() {
        TransientServerConfigPlanner.Plan plan = TransientServerConfigPlanner.exact(List.of(
                "Ars_Nouveau/rewind.toml",
                "mekanism-server.toml"));

        assertEquals(
                List.of("Ars_Nouveau/rewind.toml", "mekanism-server.toml"),
                plan.configs());
        assertFalse(plan.reviewedCatalogApplied());
        assertThrows(IllegalArgumentException.class, () ->
                TransientServerConfigPlanner.exact(List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                TransientServerConfigPlanner.exact(List.of(
                        "same-server.toml", "same-server.toml")));
    }

    @Test
    void exactAtm10Normal81ContractReplacesGuessesWithAllRuntimeConfigs() {
        Atm10Normal81ServerConfigCatalog.Catalog catalog =
                Atm10Normal81ServerConfigCatalog.catalog();
        TransientServerConfigPlanner.Plan baseline = TransientServerConfigPlanner.plan(
                List.of("securitycraft-server.toml", "guessed-server.toml"),
                List.of(),
                false,
                0);

        TransientServerConfigPlanner.Plan exact =
                TransientServerConfigPlanner.withReviewedContractCatalog(
                        baseline,
                        767,
                        Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                        Optional.of(catalog));

        assertEquals(catalog.fileNames(), exact.configs());
        assertTrue(exact.reviewedCatalogApplied());
        assertEquals(catalog.id(), exact.reviewedCatalogId());
        assertEquals(288, exact.reviewedCatalogCandidateCount());
        assertEquals(287, exact.addedReviewedCatalogConfigCount());
        assertEquals(1, exact.retainedReviewedCatalogConfigCount());
        assertEquals(1, exact.discardedBaseConfigCount());
        assertEquals(651_792, exact.reviewedCatalogEncodedBytes());
        assertFalse(exact.configs().contains("guessed-server.toml"));
    }

    @Test
    void exactCatalogSelectionDoesNotDependOnAStructuralProfile() {
        Atm10Normal81ServerConfigCatalog.Catalog catalog =
                Atm10Normal81ServerConfigCatalog.catalog();
        TransientServerConfigPlanner.Plan capabilityOnly = TransientServerConfigPlanner.plan(
                List.of("securitycraft-server.toml"), List.of(), false, 0);

        TransientServerConfigPlanner.Plan exact =
                TransientServerConfigPlanner.withReviewedContractCatalog(
                        capabilityOnly,
                        Atm10Normal81ServerConfigCatalog.PROTOCOL_VERSION,
                        Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                        Optional.of(catalog));

        assertTrue(exact.reviewedCatalogApplied());
        assertEquals(Atm10Normal81ServerConfigCatalog.CONFIG_COUNT, exact.configs().size());
        assertTrue(exact.configs().contains("theurgy-server.toml"));
        assertTrue(exact.configs().contains("jei-server.toml"));
    }

    @Test
    void protocolContractOrCatalogFailureLeavesAdmissionPlanUntouched() {
        Atm10Normal81ServerConfigCatalog.Catalog catalog =
                Atm10Normal81ServerConfigCatalog.catalog();
        TransientServerConfigPlanner.Plan baseline = TransientServerConfigPlanner.plan(
                List.of("securitycraft-server.toml"), List.of(), false, 0);

        assertSame(baseline, TransientServerConfigPlanner.withReviewedContractCatalog(
                baseline,
                766,
                Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                Optional.of(catalog)));
        assertSame(baseline, TransientServerConfigPlanner.withReviewedContractCatalog(
                baseline, 767, "0".repeat(64), Optional.of(catalog)));
        assertSame(baseline, TransientServerConfigPlanner.withReviewedContractCatalog(
                baseline,
                767,
                Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                Optional.empty()));
        assertFalse(baseline.reviewedCatalogApplied());
    }

    @Test
    void catalogApplicationIsIdempotentAndExactAgentExtrasAreDiscarded() {
        Atm10Normal81ServerConfigCatalog.Catalog catalog =
                Atm10Normal81ServerConfigCatalog.catalog();
        TransientServerConfigPlanner.Plan agent = TransientServerConfigPlanner.exact(List.of(
                "theurgy-server.toml",
                "agent-only-server.toml"));
        TransientServerConfigPlanner.Plan exact =
                TransientServerConfigPlanner.withReviewedContractCatalog(
                        agent,
                        767,
                        Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                        Optional.of(catalog));

        assertEquals(1, exact.retainedReviewedCatalogConfigCount());
        assertEquals(1, exact.discardedBaseConfigCount());
        assertFalse(exact.configs().contains("agent-only-server.toml"));
        assertSame(exact, TransientServerConfigPlanner.withReviewedContractCatalog(
                exact,
                767,
                Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                Optional.of(catalog)));
    }

    @Test
    void inconsistentReviewedCatalogEvidenceFailsClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                new TransientServerConfigPlanner.Plan(
                        List.of("example-server.toml"),
                        0,
                        0,
                        0,
                        Atm10Normal81ServerConfigCatalog.CATALOG_ID,
                        Atm10Normal81ServerConfigCatalog.NAME_SEQUENCE_SHA256,
                        Atm10Normal81ServerConfigCatalog.PAYLOAD_SEQUENCE_SHA256,
                        288,
                        287,
                        1,
                        0,
                        651_792));
        assertThrows(IllegalArgumentException.class, () ->
                new TransientServerConfigPlanner.Plan(
                        List.of("example-server.toml"),
                        0,
                        0,
                        0,
                        TransientServerConfigPlanner.NO_REVIEWED_CATALOG,
                        "1".repeat(64),
                        "",
                        0,
                        0,
                        0,
                        0,
                        0));
    }
}

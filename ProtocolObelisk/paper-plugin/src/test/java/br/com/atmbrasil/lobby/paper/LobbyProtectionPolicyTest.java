package br.com.atmbrasil.lobby.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LobbyProtectionPolicyTest {
    @Test
    void visitorsRemainRestrictedInsideTheLobbyWorld() {
        assertTrue(LobbyProtectionPolicy.shouldRestrict(
                false, "world", "world", false));
        assertFalse(LobbyProtectionPolicy.shouldRestrict(
                false, "world", "resource_world", false));
    }

    @Test
    void buildPermissionBypassesRestrictionsButNotLobbyMembership() {
        assertTrue(LobbyProtectionPolicy.isInScope(false, "world", "world"));
        assertFalse(LobbyProtectionPolicy.shouldRestrict(
                false, "world", "world", true));
    }

    @Test
    void entireServerProtectionStillRespectsTheBuilderBypass() {
        assertTrue(LobbyProtectionPolicy.shouldRestrict(
                true, "world", "resource_world", false));
        assertFalse(LobbyProtectionPolicy.shouldRestrict(
                true, "world", "resource_world", true));
    }

    @Test
    void genericUiCancellationIsOptInEvenForRestrictedVisitors() {
        assertFalse(LobbyProtectionPolicy.shouldCancelGenericUiEvents(
                false, true, "world", "world", false));
        assertTrue(LobbyProtectionPolicy.shouldCancelGenericUiEvents(
                true, true, "world", "world", false));
    }

    @Test
    void genericUiCancellationStillRespectsScopeAndBuilderBypass() {
        assertFalse(LobbyProtectionPolicy.shouldCancelGenericUiEvents(
                true, false, "world", "resource_world", false));
        assertFalse(LobbyProtectionPolicy.shouldCancelGenericUiEvents(
                true, true, "world", "world", true));
    }
}

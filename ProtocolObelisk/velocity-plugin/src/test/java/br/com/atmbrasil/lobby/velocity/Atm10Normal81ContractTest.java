package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class Atm10Normal81ContractTest {
    @Test
    void structuralIdentityRequiresProtocolAndCompleteLowercaseHash() {
        assertTrue(Atm10Normal81Contract.matchesStructuralIdentity(
                767, Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256));
        assertFalse(Atm10Normal81Contract.matchesStructuralIdentity(
                766, Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256));
        assertFalse(Atm10Normal81Contract.matchesStructuralIdentity(
                767, Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256.toUpperCase()));
        assertFalse(Atm10Normal81Contract.matchesStructuralIdentity(767, null));
    }
}

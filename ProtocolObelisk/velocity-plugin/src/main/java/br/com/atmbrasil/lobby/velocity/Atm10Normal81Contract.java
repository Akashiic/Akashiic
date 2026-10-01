package br.com.atmbrasil.lobby.velocity;

import java.util.Objects;

/** Exact observed client identity for ATM10 Normal 8.1 on Minecraft 1.21.1. */
final class Atm10Normal81Contract {
    static final String ID = "atm10-normal-8.1-neoforge-21.1.249";
    static final int MINECRAFT_PROTOCOL = 767;
    static final String FULL_CLIENT_CONTRACT_SHA256 =
            "9d06683c97b68f2bf5093c15d35a95ab14e8dbde67c6c9e65509cdaebed607a3";

    private Atm10Normal81Contract() {
    }

    /**
     * Matches only the complete captured wire contract.
     *
     * <p>This identity may select reviewed structural enrichment. It is deliberately not an
     * admission, rejection or routing predicate.</p>
     */
    static boolean matchesStructuralIdentity(
            int minecraftProtocol, String fullClientContractSha256) {
        return minecraftProtocol == MINECRAFT_PROTOCOL
                && Objects.equals(
                        FULL_CLIENT_CONTRACT_SHA256, fullClientContractSha256);
    }
}

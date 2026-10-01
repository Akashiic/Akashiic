package br.com.atmbrasil.lobby.velocity;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Bounded registry enrichment for the ATM10 8.2 session observed on 2026-09-28.
 *
 * <p>The incident reports Forbidden Arcanus 2.6.1 and an absent magnetized item modifier.
 * Its six definitions/codec were reviewed at upstream commit
 * f62af610d550e0d033f6c5cd166e40062638c44b. The existing 2,747-byte packet is reused unchanged.
 * A channel fingerprint is a session-correlation selector, NOT cryptographic attestation of
 * a mod JAR or datapack set. This evidence is deliberately limited to this one registry and
 * grants no full-pack, frozen-registry, BlockState, recipe, config-catalog or admission proof.
 * Unknown contracts retain the existing adaptive admission policy without this enrichment.</p>
 */
final class Atm10Normal82ArcanusEvidence {
    static final int PROTOCOL_VERSION = 767;
    static final String OBSERVED_CLIENT_CONTRACT_SHA256 =
            "65ddebdbecf3a09d57c2fcc3ce39099df539fdf9260181c1e31b90afdc3f9577";
    static final String PACKET_SHA256 =
            "bd73a8eb99de591e7bbcf2a16ffef24e69d17f30a6bdcbd691b4dc02c2d26db3";
    static final int PACKET_BYTES = 2_747;

    private Atm10Normal82ArcanusEvidence() {
    }

    static boolean matches(
            int clientProtocol,
            String observedClientContractSha256,
            Collection<String> advertisedNamespaces) {
        Objects.requireNonNull(advertisedNamespaces, "advertisedNamespaces");
        return clientProtocol == PROTOCOL_VERSION
                && OBSERVED_CLIENT_CONTRACT_SHA256.equals(observedClientContractSha256)
                && advertisedNamespaces.contains(
                        ForbiddenArcanusItemModifierRegistry.REQUIRED_NAMESPACE);
    }

    /** Respects the operator's candidate list and rejects conflicting or tampered descriptors. */
    static List<RegistryShimPacket> selectReviewed(List<RegistryShimPacket> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        List<RegistryShimPacket> relevant = candidates.stream()
                .filter(packet -> packet.shimId().equals(RegistryShimCatalog.FORBIDDEN_ARCANUS_2_6_1)
                        || packet.registryId().equals(ForbiddenArcanusItemModifierRegistry.REGISTRY_ID))
                .toList();
        if (relevant.isEmpty()) {
            return List.of();
        }
        if (relevant.size() != 1) {
            throw new IllegalArgumentException("ATM10 8.2 Arcanus registry has conflicting candidates");
        }
        RegistryShimPacket packet = relevant.getFirst();
        if (!packet.shimId().equals(RegistryShimCatalog.FORBIDDEN_ARCANUS_2_6_1)
                || !packet.requiredNamespace().equals(ForbiddenArcanusItemModifierRegistry.REQUIRED_NAMESPACE)
                || !packet.registryId().equals(ForbiddenArcanusItemModifierRegistry.REGISTRY_ID)
                || packet.entryCount() != ForbiddenArcanusItemModifierRegistry.ENTRY_COUNT
                || packet.packetBytes() != PACKET_BYTES
                || !packet.sha256().equals(PACKET_SHA256)
                || !sha256(packet.packetBody()).equals(PACKET_SHA256)) {
            throw new IllegalArgumentException("ATM10 8.2 Arcanus registry differs from reviewed bytes");
        }
        return List.of(packet);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for registry validation", exception);
        }
    }
}

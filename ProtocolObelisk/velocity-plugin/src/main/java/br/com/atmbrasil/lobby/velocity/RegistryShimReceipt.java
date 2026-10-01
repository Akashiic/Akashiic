package br.com.atmbrasil.lobby.velocity;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable proof that one exact registry-shim packet completed its clientbound write.
 *
 * <p>A receipt carries identity and content metadata only; it deliberately does not retain the
 * packet body. Callers must create it from the packet whose asynchronous write completed, rather
 * than treating selection of a shim as proof of delivery.</p>
 */
record RegistryShimReceipt(
        String shimId,
        String registryId,
        int entryCount,
        int packetBytes,
        String sha256) {
    private static final Pattern SHIM_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern RESOURCE_LOCATION = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    RegistryShimReceipt {
        Objects.requireNonNull(shimId, "shimId");
        Objects.requireNonNull(registryId, "registryId");
        Objects.requireNonNull(sha256, "sha256");
        if (!SHIM_ID.matcher(shimId).matches()) {
            throw new IllegalArgumentException("registry shim receipt id is invalid");
        }
        if (!RESOURCE_LOCATION.matcher(registryId).matches()) {
            throw new IllegalArgumentException("registry shim receipt registry id is invalid");
        }
        if (entryCount < 1 || entryCount > 65_535
                || packetBytes < 1 || packetBytes > 1_048_576) {
            throw new IllegalArgumentException("registry shim receipt is outside bounds");
        }
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("registry shim receipt SHA-256 is invalid");
        }
    }

    /**
     * Produces the metadata receipt for a packet and verifies that its declared digest is true.
     *
     * <p>This method does not itself prove that a network write completed. The write-completion
     * callback is the only valid place for a caller to retain the returned receipt.</p>
     */
    static RegistryShimReceipt from(RegistryShimPacket packet) {
        Objects.requireNonNull(packet, "packet");
        String actualSha256 = sha256(packet.packetBody());
        if (!actualSha256.equals(packet.sha256())) {
            throw new IllegalArgumentException(
                    "registry shim packet digest differs from its payload");
        }
        return new RegistryShimReceipt(
                packet.shimId(),
                packet.registryId(),
                packet.entryCount(),
                packet.packetBytes(),
                actualSha256);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }
}

package br.com.atmbrasil.lobby.velocity;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable registry packet descriptor with defensive payload ownership. */
record RegistryShimPacket(
        String shimId,
        String requiredNamespace,
        String registryId,
        int entryCount,
        byte[] packetBody,
        String sha256) {
    private static final Pattern SHIM_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern RESOURCE_LOCATION = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    RegistryShimPacket {
        Objects.requireNonNull(shimId, "shimId");
        Objects.requireNonNull(requiredNamespace, "requiredNamespace");
        Objects.requireNonNull(registryId, "registryId");
        Objects.requireNonNull(packetBody, "packetBody");
        Objects.requireNonNull(sha256, "sha256");
        if (!SHIM_ID.matcher(shimId).matches()) {
            throw new IllegalArgumentException("registry shim id is invalid");
        }
        if (!NAMESPACE.matcher(requiredNamespace).matches()) {
            throw new IllegalArgumentException("registry shim namespace is invalid");
        }
        if (!RESOURCE_LOCATION.matcher(registryId).matches()) {
            throw new IllegalArgumentException("registry shim registry id is invalid");
        }
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("registry shim SHA-256 is invalid");
        }
        if (entryCount < 1 || entryCount > 65_535
                || packetBody.length < 1 || packetBody.length > 1_048_576) {
            throw new IllegalArgumentException("registry shim packet is outside bounds");
        }
        packetBody = packetBody.clone();
    }

    @Override
    public byte[] packetBody() {
        return packetBody.clone();
    }

    int packetBytes() {
        return packetBody.length;
    }

    /**
     * Returns a fresh read-only Netty view without cloning the immutable reviewed payload.
     *
     * <p>Every call owns an independent reader index and reference count. The backing array is
     * private, was defensively copied by the canonical constructor and is never exposed through a
     * writable view; releasing one send therefore cannot mutate or invalidate a later send.</p>
     */
    ByteBuf newReadOnlyPacketBody() {
        return Unpooled.wrappedBuffer(packetBody).asReadOnly();
    }

    boolean appliesTo(Set<String> advertisedNamespaces) {
        Objects.requireNonNull(advertisedNamespaces, "advertisedNamespaces");
        return advertisedNamespaces.contains(requiredNamespace);
    }
}

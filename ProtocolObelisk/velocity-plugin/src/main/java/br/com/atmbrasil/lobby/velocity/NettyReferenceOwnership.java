package br.com.atmbrasil.lobby.velocity;

import io.netty.util.ReferenceCounted;

/** Contains Netty ownership release failures at the ProtocolObelisk pipeline boundary. */
final class NettyReferenceOwnership {
    private NettyReferenceOwnership() {
    }

    /**
     * Releases one owned reference without allowing an ABI/runtime failure to tear down a channel.
     *
     * <p>The direct {@link ReferenceCounted} interface is deliberately used instead of
     * {@code ReferenceCountUtil}. A legacy production build proved that linking against the wrong
     * {@code safeRelease(Object)} descriptor can otherwise close the backend connection while a
     * server transition is in progress.</p>
     */
    static void release(Object value) {
        try {
            if (value instanceof ReferenceCounted referenceCounted) {
                referenceCounted.release();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The packet is already fenced. Ownership cleanup must not reopen the protocol path.
        }
    }
}

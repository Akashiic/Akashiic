package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyLobbyRegisterGuardHandlerTest {
    private static final LegacyLobbyRegisterSanitizer.Settings SETTINGS =
            new LegacyLobbyRegisterSanitizer.Settings(16, Set.of("necrotempus:main"));

    @Test
    void bulkForgeReplayIsRewrittenWithExactOwnershipTransfer() {
        RecordingPacketAccess packetAccess = new RecordingPacketAccess();
        RecordingListener listener = new RecordingListener();
        LegacyLobbyRegisterGuardHandler handler =
                handler(packetAccess, listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestPluginMessage original = packet("minecraft:register", forgePayload(true));
        int readerIndex = original.content().readerIndex();

        assertTrue(channel.writeOutbound(original));

        assertEquals(0, original.refCnt());
        assertEquals(readerIndex, packetAccess.observedReaderIndex.get());
        TestPluginMessage replacement = channel.readOutbound();
        assertEquals("minecraft:register", replacement.channel);
        assertArrayEquals(
                "necrotempus:main".getBytes(StandardCharsets.UTF_8),
                bytes(replacement.content()));
        assertEquals(1, packetAccess.copyCalls.get());
        assertEquals(1, packetAccess.replacementCalls.get());
        assertEquals(1, handler.rewrittenPackets());
        assertEquals(0, handler.droppedPackets());
        assertEquals(1, listener.rewritten.get());
        replacement.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void oversizedControlPayloadDropsBeforeAnyCopyOrAllocation() {
        RecordingPacketAccess packetAccess = new RecordingPacketAccess();
        packetAccess.reportedPayloadBytes =
                LegacyLobbyRegisterSanitizer.MAXIMUM_INSPECTION_BYTES + 1;
        RecordingListener listener = new RecordingListener();
        LegacyLobbyRegisterGuardHandler handler =
                handler(packetAccess, listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestPluginMessage original = packet("REGISTER", new byte[] {1});

        assertFalse(channel.writeOutbound(original));

        assertEquals(0, original.refCnt());
        assertEquals(0, packetAccess.copyCalls.get());
        assertEquals(0, packetAccess.replacementCalls.get());
        assertEquals(1, handler.droppedPackets());
        assertEquals(1, listener.dropped.get());
        assertTrue(listener.lastDropReason.get().contains("bounded inspection limit"));
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void unsafeBulkReplayWithoutLobbyChannelDropsButHandlerStaysActive() {
        RecordingPacketAccess packetAccess = new RecordingPacketAccess();
        RecordingListener listener = new RecordingListener();
        LegacyLobbyRegisterGuardHandler handler = handler(packetAccess, listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestPluginMessage original = packet("REGISTER", forgePayload(false));

        assertFalse(channel.writeOutbound(original));

        assertEquals(0, original.refCnt());
        assertTrue(handler.active());
        assertEquals(1, handler.droppedPackets());
        assertEquals(1, listener.dropped.get());
        assertEquals(0, listener.failures.get());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void unrelatedPluginMessagePassesByIdentityWithoutReadingItsPayload() {
        RecordingPacketAccess packetAccess = new RecordingPacketAccess();
        LegacyLobbyRegisterGuardHandler handler =
                handler(packetAccess, new RecordingListener());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestPluginMessage original = packet("necrotempus:main", new byte[] {3, 4});

        assertTrue(channel.writeOutbound(original));

        assertSame(original, channel.readOutbound());
        assertEquals(0, packetAccess.copyCalls.get());
        assertEquals(1, original.refCnt());
        original.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void inspectionFailureFailsClosedAndQuarantinesOnlyPluginMessages() {
        RecordingPacketAccess packetAccess = new RecordingPacketAccess();
        packetAccess.copyFailure = new IllegalStateException("simulated content ABI failure");
        RecordingListener listener = new RecordingListener();
        LegacyLobbyRegisterGuardHandler handler = handler(packetAccess, listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestPluginMessage first = packet("REGISTER", forgePayload(true));

        assertFalse(channel.writeOutbound(first));
        assertEquals(0, first.refCnt());
        assertFalse(handler.active());
        assertEquals(1, listener.failures.get());

        TestPluginMessage quarantined = packet("necrotempus:main", new byte[] {1});
        assertFalse(channel.writeOutbound(quarantined));
        assertEquals(0, quarantined.refCnt());
        assertEquals(1, listener.failures.get());

        Object vanillaPacket = new Object();
        assertTrue(channel.writeOutbound(vanillaPacket));
        assertSame(vanillaPacket, channel.readOutbound());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void replacementFailureReleasesOriginalAndReportsOnlyOnce() {
        RecordingPacketAccess packetAccess = new RecordingPacketAccess();
        packetAccess.replacementFailure = new IllegalStateException("constructor changed");
        RecordingListener listener = new RecordingListener();
        LegacyLobbyRegisterGuardHandler handler = handler(packetAccess, listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestPluginMessage original = packet("REGISTER", forgePayload(true));

        assertFalse(channel.writeOutbound(original));

        assertEquals(0, original.refCnt());
        assertFalse(handler.active());
        assertEquals(1, listener.failures.get());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void replacementAliasingOriginalIsReleasedExactlyOnceByFailClosedOwner() {
        RecordingPacketAccess packetAccess = new RecordingPacketAccess();
        packetAccess.returnOriginalAsReplacement = true;
        RecordingListener listener = new RecordingListener();
        LegacyLobbyRegisterGuardHandler handler = handler(packetAccess, listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestPluginMessage original = packet("REGISTER", forgePayload(true));

        assertFalse(channel.writeOutbound(original));

        assertEquals(0, original.refCnt());
        assertEquals(1, original.releaseAttempts());
        assertFalse(handler.active());
        assertEquals(1, listener.failures.get());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void listenerFailuresAreContainedAndNotificationsAreBounded() {
        RecordingPacketAccess packetAccess = new RecordingPacketAccess();
        RecordingListener listener = new RecordingListener();
        listener.throwFromDiagnostics = true;
        LegacyLobbyRegisterGuardHandler handler = handler(packetAccess, listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        for (int index = 0; index < 10; index++) {
            assertFalse(channel.writeOutbound(packet("REGISTER", forgePayload(false))));
        }

        assertTrue(handler.active());
        assertEquals(10, handler.droppedPackets());
        assertEquals(8, listener.dropped.get());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void protocolFenceAndSettingsIdentityAreDefensive() {
        RecordingPacketAccess packetAccess = new RecordingPacketAccess();
        RecordingListener listener = new RecordingListener();
        assertThrows(
                IllegalArgumentException.class,
                () -> new LegacyLobbyRegisterGuardHandler(
                        767, SETTINGS, packetAccess, listener));

        LegacyLobbyRegisterGuardHandler handler = handler(packetAccess, listener);
        assertTrue(handler.matches(5, SETTINGS));
        assertFalse(handler.matches(
                5,
                new LegacyLobbyRegisterSanitizer.Settings(
                        15, Set.of("necrotempus:main"))));
        assertFalse(handler.matches(767, SETTINGS));
    }

    @Test
    void ownershipReleaseContainsDoubleReleaseFailure() {
        ByteBuf buffer = Unpooled.buffer(1).writeByte(1);
        NettyReferenceOwnership.release(buffer);
        assertEquals(0, buffer.refCnt());

        NettyReferenceOwnership.release(buffer);
        assertEquals(0, buffer.refCnt());
    }

    private static LegacyLobbyRegisterGuardHandler handler(
            RecordingPacketAccess packetAccess,
            RecordingListener listener) {
        return new LegacyLobbyRegisterGuardHandler(5, SETTINGS, packetAccess, listener);
    }

    private static TestPluginMessage packet(String channel, byte[] payload) {
        return new TestPluginMessage(channel, Unpooled.wrappedBuffer(payload));
    }

    private static byte[] forgePayload(boolean includeAllowed) {
        StringBuilder payload = new StringBuilder("FML\0FML|HS");
        if (includeAllowed) {
            payload.append("\0necrotempus:main");
        }
        for (int index = 0; index < 30; index++) {
            payload.append('\0').append("mod").append(index).append(":channel");
        }
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] result = new byte[buffer.readableBytes()];
        buffer.duplicate().readBytes(result);
        return result;
    }

    private static final class RecordingPacketAccess
            implements LegacyLobbyRegisterGuardHandler.PacketAccess {
        private final AtomicInteger copyCalls = new AtomicInteger();
        private final AtomicInteger replacementCalls = new AtomicInteger();
        private final AtomicInteger observedReaderIndex = new AtomicInteger(-1);
        private int reportedPayloadBytes = -1;
        private RuntimeException copyFailure;
        private RuntimeException replacementFailure;
        private boolean returnOriginalAsReplacement;

        @Override
        public boolean isPluginMessage(Object message) {
            return message instanceof TestPluginMessage;
        }

        @Override
        public String outerChannel(Object message) {
            return ((TestPluginMessage) message).channel;
        }

        @Override
        public int readablePayloadBytes(Object message) {
            if (reportedPayloadBytes >= 0) {
                return reportedPayloadBytes;
            }
            return ((TestPluginMessage) message).content().readableBytes();
        }

        @Override
        public byte[] copyPayload(Object message, int expectedBytes) {
            copyCalls.incrementAndGet();
            if (copyFailure != null) {
                throw copyFailure;
            }
            ByteBuf content = ((TestPluginMessage) message).content();
            observedReaderIndex.set(content.readerIndex());
            return bytes(content);
        }

        @Override
        public Object replacement(Object message, String outerChannel, byte[] payload) {
            replacementCalls.incrementAndGet();
            if (replacementFailure != null) {
                throw replacementFailure;
            }
            if (returnOriginalAsReplacement) {
                return message;
            }
            return packet(outerChannel, payload);
        }
    }

    private static final class RecordingListener
            implements LegacyLobbyRegisterGuardHandler.Listener {
        private final AtomicInteger rewritten = new AtomicInteger();
        private final AtomicInteger dropped = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicReference<String> lastDropReason = new AtomicReference<>();
        private boolean throwFromDiagnostics;

        @Override
        public void rewritten(
                int originalChannelCount, List<String> retainedChannels, long sequence) {
            rewritten.incrementAndGet();
            if (throwFromDiagnostics) {
                throw new AssertionError("simulated diagnostics failure");
            }
        }

        @Override
        public void dropped(int originalChannelCount, String reason, long sequence) {
            dropped.incrementAndGet();
            lastDropReason.set(reason);
            if (throwFromDiagnostics) {
                throw new AssertionError("simulated diagnostics failure");
            }
        }

        @Override
        public void failed(Throwable failure) {
            failures.incrementAndGet();
            if (throwFromDiagnostics) {
                throw new AssertionError("simulated diagnostics failure");
            }
        }
    }

    private static final class TestPluginMessage extends DefaultByteBufHolder {
        private final String channel;
        private final AtomicInteger releaseAttempts = new AtomicInteger();

        private TestPluginMessage(String channel, ByteBuf content) {
            super(content);
            this.channel = channel;
        }

        @Override
        public TestPluginMessage replace(ByteBuf content) {
            return new TestPluginMessage(channel, content);
        }

        @Override
        public boolean release() {
            releaseAttempts.incrementAndGet();
            return super.release();
        }

        int releaseAttempts() {
            return releaseAttempts.get();
        }
    }
}

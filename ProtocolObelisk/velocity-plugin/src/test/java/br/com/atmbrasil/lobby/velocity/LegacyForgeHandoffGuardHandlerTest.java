package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.LegacyForgeHandoffPolicy.ControlOperation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyForgeHandoffGuardHandlerTest {
    @Test
    void exactLegacyForgeTransitionConsumesRegisterAndUnregisterWithOwnership() {
        AtomicReference<String> target = new AtomicReference<>("forbidden-1");
        RecordingListener listener = new RecordingListener();
        LegacyForgeHandoffGuardHandler handler = handler(5, "lobby", target, listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        TestPluginMessage register = packet("REGISTER");
        TestPluginMessage unregister = packet("UNREGISTER");
        assertFalse(channel.writeOutbound(register));
        assertFalse(channel.writeOutbound(unregister));

        assertEquals(0, register.refCnt());
        assertEquals(0, unregister.refCnt());
        assertEquals(2, handler.suppressedPackets());
        assertEquals(2, listener.suppressed.get());
        assertEquals(ControlOperation.UNREGISTER, listener.lastOperation.get());
        assertEquals("forbidden-1", listener.lastTarget.get());
        assertTrue(handler.active());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void nonControlPacketsAndTransitionsOutsideTheExactFencePassUnchanged() {
        assertPasses(5, "lobby", null, "REGISTER");
        assertPasses(5, "lobby", "another-server", "REGISTER");
        assertPasses(767, "lobby", "forbidden-1", "REGISTER");
        assertPasses(5, "not-the-lobby", "forbidden-1", "REGISTER");
        assertPasses(5, "lobby", "forbidden-1", "FML|HS");
    }

    @Test
    void reflectionFailureQuarantinesPluginMessagesAndReportsOnlyOnce() {
        AtomicInteger failures = new AtomicInteger();
        LegacyForgeHandoffGuardHandler handler = new LegacyForgeHandoffGuardHandler(
                5,
                "lobby",
                "lobby",
                Set.of("forbidden-1"),
                packetAccess(),
                () -> {
                    throw new ReflectiveOperationException("in-flight ABI changed");
                },
                new LegacyForgeHandoffGuardHandler.Listener() {
                    @Override
                    public void suppressed(
                            ControlOperation operation, String inFlightTarget, long sequence) {
                        throw new AssertionError("a failed probe cannot report suppression");
                    }

                    @Override
                    public void failed(Throwable failure) {
                        failures.incrementAndGet();
                    }
                });
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        TestPluginMessage first = packet("REGISTER");
        TestPluginMessage quarantined = packet("some-mod-channel");
        assertFalse(channel.writeOutbound(first));
        assertFalse(channel.writeOutbound(quarantined));
        assertEquals(0, first.refCnt());
        assertEquals(0, quarantined.refCnt());
        assertFalse(handler.active());
        assertEquals(1, failures.get());

        Object vanillaPacket = new Object();
        assertTrue(channel.writeOutbound(vanillaPacket));
        assertSame(vanillaPacket, channel.readOutbound());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void normalLeaseDeactivationStopsFilteringBeforeRemoval() {
        AtomicReference<String> target = new AtomicReference<>("forbidden-1");
        LegacyForgeHandoffGuardHandler handler =
                handler(5, "lobby", target, new RecordingListener());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        handler.deactivate();

        TestPluginMessage packet = packet("REGISTER");
        assertTrue(channel.writeOutbound(packet));
        assertSame(packet, channel.readOutbound());
        assertEquals(1, packet.refCnt());
        packet.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void placementAfterMinecraftEncoderInterceptsObjectBeforeEncoding() {
        AtomicInteger encodedPackets = new AtomicInteger();
        ChannelOutboundHandlerAdapter encoder =
                new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(
                            ChannelHandlerContext context,
                            Object message,
                            ChannelPromise promise) throws Exception {
                        if (message instanceof TestPluginMessage) {
                            encodedPackets.incrementAndGet();
                        }
                        super.write(context, message, promise);
                    }
                };
        EmbeddedChannel channel = new EmbeddedChannel(encoder);
        LegacyForgeHandoffGuardHandler handler = handler(
                5,
                "lobby",
                new AtomicReference<>("forbidden-1"),
                new RecordingListener());
        channel.pipeline().addAfter(
                channel.pipeline().context(encoder).name(), "legacy-forge-guard", handler);

        TestPluginMessage register = packet("REGISTER");
        assertFalse(channel.writeOutbound(register));
        assertEquals(0, register.refCnt());
        assertEquals(0, encodedPackets.get());
        assertEquals(1, handler.suppressedPackets());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void suppressionDiagnosticsAreBoundedWithoutWeakeningTheFence() {
        RecordingListener listener = new RecordingListener();
        LegacyForgeHandoffGuardHandler handler = handler(
                5,
                "lobby",
                new AtomicReference<>("forbidden-1"),
                listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        for (int index = 0; index < 10; index++) {
            assertFalse(channel.writeOutbound(packet("REGISTER")));
        }

        assertEquals(10, handler.suppressedPackets());
        assertEquals(8, listener.suppressed.get());
        assertFalse(channel.finishAndReleaseAll());
    }

    private static void assertPasses(
            int protocol, String guardedBackend, String target, String outerChannel) {
        AtomicReference<String> targetReference = new AtomicReference<>(target);
        RecordingListener listener = new RecordingListener();
        LegacyForgeHandoffGuardHandler handler =
                handler(protocol, guardedBackend, targetReference, listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestPluginMessage packet = packet(outerChannel);

        assertTrue(channel.writeOutbound(packet));
        assertSame(packet, channel.readOutbound());
        assertEquals(1, packet.refCnt());
        assertEquals(0, handler.suppressedPackets());
        assertEquals(0, listener.suppressed.get());
        packet.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    private static LegacyForgeHandoffGuardHandler handler(
            int protocol,
            String guardedBackend,
            AtomicReference<String> target,
            LegacyForgeHandoffGuardHandler.Listener listener) {
        return new LegacyForgeHandoffGuardHandler(
                protocol,
                guardedBackend,
                "lobby",
                Set.of("forbidden-1"),
                packetAccess(),
                target::get,
                listener);
    }

    private static LegacyForgeHandoffGuardHandler.PacketAccess packetAccess() {
        return new LegacyForgeHandoffGuardHandler.PacketAccess() {
            @Override
            public boolean isPluginMessage(Object message) {
                return message instanceof TestPluginMessage;
            }

            @Override
            public String outerChannel(Object message) {
                return ((TestPluginMessage) message).channel;
            }
        };
    }

    private static TestPluginMessage packet(String channel) {
        return new TestPluginMessage(channel, Unpooled.buffer(1).writeByte(1));
    }

    private static final class RecordingListener
            implements LegacyForgeHandoffGuardHandler.Listener {
        private final AtomicInteger suppressed = new AtomicInteger();
        private final AtomicReference<ControlOperation> lastOperation = new AtomicReference<>();
        private final AtomicReference<String> lastTarget = new AtomicReference<>();

        @Override
        public void suppressed(
                ControlOperation operation, String inFlightTarget, long sequence) {
            assertEquals(suppressed.incrementAndGet(), sequence);
            lastOperation.set(operation);
            lastTarget.set(inFlightTarget);
        }

        @Override
        public void failed(Throwable failure) {
            throw new AssertionError("unexpected guard failure", failure);
        }
    }

    private static final class TestPluginMessage extends DefaultByteBufHolder {
        private final String channel;

        private TestPluginMessage(String channel, ByteBuf data) {
            super(data);
            this.channel = channel;
        }

        @Override
        public TestPluginMessage replace(ByteBuf content) {
            return new TestPluginMessage(channel, content);
        }
    }
}

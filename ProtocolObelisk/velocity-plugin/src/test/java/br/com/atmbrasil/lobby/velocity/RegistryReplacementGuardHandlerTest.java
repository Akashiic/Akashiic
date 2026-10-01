package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.CompoundTag;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.Entry;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RegistryReplacementGuardHandlerTest {
    @Test
    void nonTargetRegistryAndNonRegistryObjectsPassThroughUnchanged() throws Exception {
        RecordingListener listener = new RecordingListener();
        RegistryReplacementGuardHandler handler = handler(listener, new AtomicBoolean());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        TestRegistryPacket other = packet(registryBody("minecraft:damage_type", "minecraft:fall"));
        Object ordinary = new Object();
        assertTrue(channel.writeOutbound(other));
        assertSame(other, channel.readOutbound());
        assertEquals(1, other.refCnt());
        assertTrue(channel.writeOutbound(ordinary));
        assertSame(ordinary, channel.readOutbound());
        assertFalse(handler.completion().isDone());
        assertEquals(0, listener.replaced.get());
        assertEquals(0, listener.failures.get());

        other.release();
        handler.deactivate();
        assertTrue(handler.completion().isCompletedExceptionally());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void exactPaperEnchantmentPacketIsReplacedOnceWithIndependentOwnership()
            throws Exception {
        RecordingListener listener = new RecordingListener();
        RegistryReplacementGuardHandler handler = handler(listener, new AtomicBoolean());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestRegistryPacket original = paperEnchantmentPacket();

        assertTrue(channel.writeOutbound(original));
        assertEquals(0, original.refCnt());
        Object rawReplacement = channel.readOutbound();
        assertTrue(rawReplacement instanceof TestRegistryPacket);
        TestRegistryPacket replacement = (TestRegistryPacket) rawReplacement;
        assertNotSame(original, replacement);
        assertArrayEquals(reviewedReplacement().packetBody(), bytes(replacement.content()));
        assertEquals(1, replacement.refCnt());
        assertEquals(1, handler.replacedPackets());
        assertEquals(0, handler.duplicatePackets());
        assertEquals(1, listener.replaced.get());
        assertEquals(
                RegistryShimReceipt.from(reviewedReplacement()),
                handler.completion().join());

        replacement.release();
        assertFalse(channel.finishAndReleaseAll());
    }


    @Test
    void narrow82MergePreservesPaperEntriesAndAppendsExactGiselleExtension()
            throws Exception {
        RegistryShimPacket extension = Atm10Normal82GiselleEnchantmentExtension.packet(
                1_048_576);
        RecordingListener listener = new RecordingListener();
        RegistryReplacementGuardHandler handler = new RegistryReplacementGuardHandler(
                extension,
                RegistryReplacementGuardHandler.TransformMode.MERGE_DISTINCT_EXTENSION,
                packetAccess(new AtomicBoolean()),
                listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestRegistryPacket original = paperEnchantmentPacket();

        assertTrue(channel.writeOutbound(original));
        assertEquals(0, original.refCnt());
        TestRegistryPacket mergedPacket = channel.readOutbound();
        var inspection = MinecraftRegistryPacketCodec.inspect(
                bytes(mergedPacket.content()), 1_048_576);
        assertEquals("minecraft:protection", inspection.entryIds().getFirst());
        assertEquals(
                Atm10Normal82GiselleEnchantmentExtension.ENTRY_IDS,
                inspection.entryIds().subList(1, inspection.entryIds().size()));
        assertEquals(1, handler.replacedPackets());
        assertEquals(1, listener.replaced.get());
        assertEquals(RegistryShimReceipt.from(extension), handler.completion().join());

        mergedPacket.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void narrow82MergeConsumesConflictingDuplicateAfterSuccessfulFirstWrite()
            throws Exception {
        RegistryShimPacket extension = Atm10Normal82GiselleEnchantmentExtension.packet(
                1_048_576);
        RecordingListener listener = new RecordingListener();
        RegistryReplacementGuardHandler handler = new RegistryReplacementGuardHandler(
                extension,
                RegistryReplacementGuardHandler.TransformMode.MERGE_DISTINCT_EXTENSION,
                packetAccess(new AtomicBoolean()),
                listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertTrue(channel.writeOutbound(paperEnchantmentPacket()));
        ((TestRegistryPacket) channel.readOutbound()).release();

        TestRegistryPacket conflictingDuplicate = packet(registryBody(
                Atm10Normal82GiselleEnchantmentExtension.REGISTRY_ID,
                "ad_astra_giselle_addon:space_breathing"));
        assertFalse(channel.writeOutbound(conflictingDuplicate));
        assertEquals(0, conflictingDuplicate.refCnt());
        assertEquals(1, handler.replacedPackets());
        assertEquals(1, handler.duplicatePackets());
        assertEquals(1, listener.replaced.get());
        assertEquals(1, listener.duplicates.get());
        assertEquals(0, listener.failures.get());
        assertTrue(handler.active());

        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void narrow82MergeConsumesMalformedTargetDuplicateAfterSuccessfulFirstWrite()
            throws Exception {
        RegistryShimPacket extension = Atm10Normal82GiselleEnchantmentExtension.packet(
                1_048_576);
        RecordingListener listener = new RecordingListener();
        RegistryReplacementGuardHandler handler = new RegistryReplacementGuardHandler(
                extension,
                RegistryReplacementGuardHandler.TransformMode.MERGE_DISTINCT_EXTENSION,
                packetAccess(new AtomicBoolean()),
                listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertTrue(channel.writeOutbound(paperEnchantmentPacket()));
        ((TestRegistryPacket) channel.readOutbound()).release();

        byte[] validBody = registryBody(
                Atm10Normal82GiselleEnchantmentExtension.REGISTRY_ID,
                "minecraft:protection");
        byte[] malformedAfterRegistryId = java.util.Arrays.copyOf(
                validBody,
                1 + Atm10Normal82GiselleEnchantmentExtension.REGISTRY_ID.length());
        TestRegistryPacket malformedDuplicate = packet(malformedAfterRegistryId);

        assertFalse(channel.writeOutbound(malformedDuplicate));
        assertEquals(0, malformedDuplicate.refCnt());
        assertEquals(1, handler.replacedPackets());
        assertEquals(1, handler.duplicatePackets());
        assertEquals(1, listener.duplicates.get());
        assertEquals(0, listener.failures.get());
        assertTrue(handler.active());

        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void narrow82MergeFailsOpenWhenPaperAlreadyContainsReviewedGiselleEntry()
            throws Exception {
        RegistryShimPacket extension = Atm10Normal82GiselleEnchantmentExtension.packet(
                1_048_576);
        RecordingListener listener = new RecordingListener();
        RegistryReplacementGuardHandler handler = new RegistryReplacementGuardHandler(
                extension,
                RegistryReplacementGuardHandler.TransformMode.MERGE_DISTINCT_EXTENSION,
                packetAccess(new AtomicBoolean()),
                listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestRegistryPacket original = packet(registryBody(
                Atm10Normal82GiselleEnchantmentExtension.REGISTRY_ID,
                "ad_astra_giselle_addon:space_breathing"));

        assertTrue(channel.writeOutbound(original));
        assertSame(original, channel.readOutbound());
        assertEquals(1, original.refCnt());
        assertEquals(0, handler.replacedPackets());
        assertEquals(1, listener.failures.get());
        assertTrue(handler.completion().isCompletedExceptionally());

        original.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void replacementReleasesExactlyThePipelineOwnedRetain() throws Exception {
        RegistryReplacementGuardHandler handler =
                handler(new RecordingListener(), new AtomicBoolean());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestRegistryPacket retainedOriginal = paperEnchantmentPacket();
        retainedOriginal.retain();
        assertEquals(2, retainedOriginal.refCnt());

        assertTrue(channel.writeOutbound(retainedOriginal));
        assertEquals(1, retainedOriginal.refCnt());
        retainedOriginal.release();
        assertEquals(0, retainedOriginal.refCnt());
        ((TestRegistryPacket) channel.readOutbound()).release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void everyPaperRegistryAfterSuccessfulReceiptRemainsConsumedUntilDetach()
            throws Exception {
        RecordingListener listener = new RecordingListener();
        RegistryReplacementGuardHandler handler = handler(listener, new AtomicBoolean());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertTrue(channel.writeOutbound(paperEnchantmentPacket()));
        ((TestRegistryPacket) channel.readOutbound()).release();
        List<TestRegistryPacket> duplicates = List.of(
                paperEnchantmentPacket(),
                paperEnchantmentPacket(),
                paperEnchantmentPacket());
        for (TestRegistryPacket duplicate : duplicates) {
            assertFalse(channel.writeOutbound(duplicate));
            assertEquals(0, duplicate.refCnt());
        }

        assertEquals(1, handler.replacedPackets());
        assertEquals(3, handler.duplicatePackets());
        assertEquals(1, listener.replaced.get());
        assertEquals(1, listener.duplicates.get());
        assertEquals(0, listener.failures.get());
        assertEquals(
                RegistryShimReceipt.from(reviewedReplacement()),
                handler.completion().join(),
                "the first downstream-promise receipt remains the exact completed proof");
        assertTrue(handler.active(), "duplicate diagnostics must not release the fence");
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void malformedRegistryOrReplacementConstructionFailurePassesPaperOriginal()
            throws Exception {
        RecordingListener malformedListener = new RecordingListener();
        RegistryReplacementGuardHandler malformedHandler =
                handler(malformedListener, new AtomicBoolean());
        EmbeddedChannel malformedChannel = new EmbeddedChannel(malformedHandler);
        TestRegistryPacket malformed = packet(new byte[] {0});
        assertTrue(malformedChannel.writeOutbound(malformed));
        assertSame(malformed, malformedChannel.readOutbound());
        assertEquals(1, malformed.refCnt());
        assertEquals(1, malformedListener.failures.get());
        assertTrue(malformedHandler.completion().isCompletedExceptionally());
        malformed.release();
        assertFalse(malformedChannel.finishAndReleaseAll());

        AtomicBoolean failReplacement = new AtomicBoolean(true);
        RecordingListener constructionListener = new RecordingListener();
        RegistryReplacementGuardHandler constructionHandler =
                handler(constructionListener, failReplacement);
        EmbeddedChannel constructionChannel = new EmbeddedChannel(constructionHandler);
        TestRegistryPacket original = paperEnchantmentPacket();
        assertTrue(constructionChannel.writeOutbound(original));
        assertSame(original, constructionChannel.readOutbound());
        assertEquals(1, original.refCnt());
        assertEquals(1, constructionListener.failures.get());
        assertTrue(constructionHandler.completion().isCompletedExceptionally());
        original.release();
        assertFalse(constructionChannel.finishAndReleaseAll());
    }

    @Test
    void voidPromiseFailsOpenBeforeClaimAndForwardsOriginalIntact() throws Exception {
        RecordingListener listener = new RecordingListener();
        RegistryReplacementGuardHandler handler = handler(listener, new AtomicBoolean());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestRegistryPacket original = paperEnchantmentPacket();

        channel.pipeline().writeAndFlush(original, channel.voidPromise());

        assertSame(original, channel.readOutbound());
        assertEquals(1, original.refCnt());
        assertEquals(0, handler.replacedPackets());
        assertEquals(0, handler.duplicatePackets());
        assertEquals(0, listener.replaced.get());
        assertEquals(1, listener.failures.get());
        assertTrue(handler.completion().isCompletedExceptionally());
        assertFalse(handler.active());

        original.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void voidPromiseForNonTargetTailRegistryDoesNotDisarmReplacementFence()
            throws Exception {
        RecordingListener listener = new RecordingListener();
        RegistryReplacementGuardHandler handler = handler(listener, new AtomicBoolean());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestRegistryPacket nonTarget = packet(registryBody(
                "minecraft:damage_type", "minecraft:fall"));

        channel.pipeline().writeAndFlush(nonTarget, channel.voidPromise());

        assertSame(nonTarget, channel.readOutbound());
        assertEquals(1, nonTarget.refCnt());
        assertTrue(handler.active());
        assertFalse(handler.completion().isDone());
        assertEquals(0, listener.failures.get());
        nonTarget.release();

        assertTrue(channel.writeOutbound(paperEnchantmentPacket()));
        TestRegistryPacket replacement = channel.readOutbound();
        assertArrayEquals(reviewedReplacement().packetBody(), bytes(replacement.content()));
        assertEquals(1, handler.replacedPackets());
        assertEquals(1, listener.replaced.get());
        replacement.release();

        TestRegistryPacket voidDuplicate = paperEnchantmentPacket();
        channel.pipeline().writeAndFlush(voidDuplicate, channel.voidPromise());
        assertEquals(0, voidDuplicate.refCnt());
        TestRegistryPacket ordinaryDuplicate = paperEnchantmentPacket();
        assertFalse(channel.writeOutbound(ordinaryDuplicate));
        assertEquals(0, ordinaryDuplicate.refCnt());
        assertEquals(2, handler.duplicatePackets());
        assertEquals(1, listener.duplicates.get());
        assertTrue(handler.active());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void receiptIsPublishedOnlyAfterDownstreamPromiseSuccess() throws Exception {
        AtomicReference<TestRegistryPacket> handedDownstream = new AtomicReference<>();
        RuntimeException writeFailure = new RuntimeException("synthetic write failure");
        ChannelOutboundHandlerAdapter failingEncoder = new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(
                    ChannelHandlerContext context,
                    Object message,
                    ChannelPromise promise) {
                handedDownstream.set((TestRegistryPacket) message);
                NettyReferenceOwnership.release(message);
                promise.setFailure(writeFailure);
            }
        };
        RecordingListener listener = new RecordingListener();
        RegistryReplacementGuardHandler handler = handler(listener, new AtomicBoolean());
        EmbeddedChannel channel = new EmbeddedChannel(failingEncoder);
        channel.pipeline().addAfter(
                channel.pipeline().context(failingEncoder).name(), "replacement", handler);
        TestRegistryPacket original = paperEnchantmentPacket();

        assertThrows(RuntimeException.class, () -> channel.writeOutbound(original));
        assertEquals(0, original.refCnt());
        assertEquals(0, handedDownstream.get().refCnt());
        assertEquals(0, listener.replaced.get());
        assertEquals(1, listener.failures.get());
        assertTrue(handler.completion().isCompletedExceptionally());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void placementAfterMinecraftEncoderInterceptsObjectBeforeEncoding() throws Exception {
        AtomicInteger encoded = new AtomicInteger();
        ChannelOutboundHandlerAdapter encoder = new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(
                    ChannelHandlerContext context,
                    Object message,
                    ChannelPromise promise) throws Exception {
                if (message instanceof TestRegistryPacket) {
                    encoded.incrementAndGet();
                }
                super.write(context, message, promise);
            }
        };
        RegistryReplacementGuardHandler handler =
                handler(new RecordingListener(), new AtomicBoolean());
        EmbeddedChannel channel = new EmbeddedChannel(encoder);
        channel.pipeline().addAfter(
                channel.pipeline().context(encoder).name(), "replacement", handler);

        assertTrue(channel.writeOutbound(paperEnchantmentPacket()));
        TestRegistryPacket encodedReplacement = channel.readOutbound();
        assertArrayEquals(reviewedReplacement().packetBody(), bytes(encodedReplacement.content()));
        assertEquals(1, encoded.get());
        encodedReplacement.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    private static RegistryReplacementGuardHandler handler(
            RegistryReplacementGuardHandler.Listener listener,
            AtomicBoolean failReplacement) throws ProtocolViolationException {
        return new RegistryReplacementGuardHandler(
                reviewedReplacement(), packetAccess(failReplacement), listener);
    }

    private static RegistryReplacementGuardHandler.PacketAccess packetAccess(
            AtomicBoolean failReplacement) {
        return new RegistryReplacementGuardHandler.PacketAccess() {
            @Override
            public boolean isRegistrySyncPacket(Object message) {
                return message instanceof TestRegistryPacket;
            }

            @Override
            public int readableBodyBytes(Object message) {
                return ((TestRegistryPacket) message).content().readableBytes();
            }

            @Override
            public byte[] copyBody(Object message, int expectedBytes) {
                ByteBuf body = ((TestRegistryPacket) message).content();
                if (body.readableBytes() != expectedBytes) {
                    throw new IllegalStateException("test packet body size changed");
                }
                return bytes(body);
            }

            @Override
            public Object replacement(Object original, byte[] replacementBody)
                    throws ReflectiveOperationException {
                if (failReplacement.get()) {
                    throw new ReflectiveOperationException("synthetic constructor failure");
                }
                return packet(Unpooled.buffer(replacementBody.length)
                        .writeBytes(replacementBody));
            }
        };
    }

    private static RegistryShimPacket reviewedReplacement()
            throws ProtocolViolationException {
        byte[] body = MinecraftRegistryPacketCodec.encode(
                Atm10Normal81EnchantmentRegistry.REGISTRY_ID,
                Atm10Normal81EnchantmentRegistry.REQUIRED_SENTINEL_ENTRY_IDS.stream()
                        .map(id -> new Entry(id, new CompoundTag(Map.of())))
                        .toList(),
                65_536);
        return new RegistryShimPacket(
                Atm10Normal81EnchantmentRegistry.SHIM_ID,
                Atm10Normal81EnchantmentRegistry.REQUIRED_NAMESPACE,
                Atm10Normal81EnchantmentRegistry.REGISTRY_ID,
                Atm10Normal81EnchantmentRegistry.REQUIRED_SENTINEL_ENTRY_IDS.size(),
                body,
                sha256(body));
    }

    private static TestRegistryPacket paperEnchantmentPacket()
            throws ProtocolViolationException {
        return packet(registryBody(
                Atm10Normal81EnchantmentRegistry.REGISTRY_ID,
                "minecraft:protection"));
    }

    private static byte[] registryBody(String registryId, String entryId)
            throws ProtocolViolationException {
        return MinecraftRegistryPacketCodec.encode(
                registryId,
                List.of(new Entry(entryId, new CompoundTag(Map.of()))),
                65_536);
    }

    private static TestRegistryPacket packet(byte[] body) {
        return packet(Unpooled.buffer(body.length).writeBytes(body));
    }

    private static TestRegistryPacket packet(ByteBuf body) {
        return new TestRegistryPacket(body);
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] copy = new byte[buffer.readableBytes()];
        buffer.duplicate().readBytes(copy);
        return copy;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static final class RecordingListener
            implements RegistryReplacementGuardHandler.Listener {
        private final AtomicInteger replaced = new AtomicInteger();
        private final AtomicInteger duplicates = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        @Override
        public void replaced(RegistryShimReceipt receipt) {
            replaced.incrementAndGet();
        }

        @Override
        public void duplicate(Throwable failure) {
            duplicates.incrementAndGet();
        }

        @Override
        public void failed(Throwable failure) {
            failures.incrementAndGet();
        }
    }

    private static final class TestRegistryPacket extends DefaultByteBufHolder {
        private TestRegistryPacket(ByteBuf data) {
            super(data);
        }

        @Override
        public TestRegistryPacket replace(ByteBuf content) {
            return new TestRegistryPacket(content);
        }
    }
}

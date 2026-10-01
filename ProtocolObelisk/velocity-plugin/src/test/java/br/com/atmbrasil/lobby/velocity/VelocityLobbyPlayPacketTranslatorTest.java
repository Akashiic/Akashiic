package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ServerConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class VelocityLobbyPlayPacketTranslatorTest {
    private static final String PROFILE_ID =
            "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247";
    private static final String FULL_CONTRACT =
            "a61293a54b0f80c83b73b5e2968e8de08b9595771110fa7c7cbea9e55ad2892f";
    private static final String FROZEN_SEQUENCE =
            "8fda0099a55ad36898adbf43dc77d5b702c6d4c5c26c1bcddb3039669dd00269";
    private static final int STONE_BRICKS_SOURCE = 6_537;
    private static final int STONE_BRICKS_TARGET = 9_029;

    private static BlockStateTranslationProfile profile;

    @BeforeAll
    static void loadProfile() throws IOException {
        profile = BlockStateTranslationProfile.loadAtm10Normal80(
                VelocityLobbyPlayPacketTranslatorTest.class.getClassLoader(),
                SilentGearEmbeddedProfile.ATM10_NORMAL_8_0_RESOURCE_ROOT,
                PROFILE_ID,
                767,
                FULL_CONTRACT,
                FROZEN_SEQUENCE);
    }

    @Test
    void nonTargetPacketPassesThroughWithoutReferenceCountChange() throws Exception {
        Object play = new Object();
        Harness harness = newHarness(play, play, failure -> {
            throw new AssertionError("unexpected translation failure", failure);
        });
        ByteBuf input = Unpooled.buffer().writeByte(0x7F).writeInt(0x1234_5678);
        try {
            assertTrue(harness.channel().writeInbound(input));
            assertEquals(1, input.refCnt());
            ByteBuf forwarded = harness.channel().readInbound();
            assertSame(input, forwarded);
            assertEquals(0x7F, forwarded.readUnsignedByte());
            assertEquals(0x1234_5678, forwarded.readInt());
            forwarded.release();
        } finally {
            harness.channel().finishAndReleaseAll();
        }
    }

    @Test
    void targetPacketIsTranslatedAndOriginalOwnershipIsReleased() throws Exception {
        Object play = new Object();
        Harness harness = newHarness(play, play, failure -> {
            throw new AssertionError("unexpected translation failure", failure);
        });
        ByteBuf input = blockUpdate(STONE_BRICKS_SOURCE);
        try {
            assertTrue(harness.channel().writeInbound(input));
            assertEquals(0, input.refCnt());
            ByteBuf translated = harness.channel().readInbound();
            assertEquals(profile.blockUpdatePacketId(), readVarInt(translated));
            assertEquals(0x0123_4567_89AB_CDEFL, translated.readLong());
            assertEquals(STONE_BRICKS_TARGET, readVarInt(translated));
            assertFalse(translated.isReadable());
            translated.release();
        } finally {
            harness.channel().finishAndReleaseAll();
        }
    }

    @Test
    void inactiveLeaseDropsOnlyReviewedTargetsUntilHandlerRemoval() throws Exception {
        Object play = new Object();
        Harness harness = newHarness(play, play, failure -> {
            throw new AssertionError("unexpected translation failure", failure);
        });
        harness.lease().deactivate();
        ByteBuf target = blockUpdate(STONE_BRICKS_SOURCE);
        ByteBuf unrelated = Unpooled.buffer().writeByte(0x7F).writeByte(1);
        try {
            assertFalse(harness.channel().writeInbound(target));
            assertEquals(0, target.refCnt());
            assertTrue(harness.channel().isActive());

            assertTrue(harness.channel().writeInbound(unrelated));
            assertSame(unrelated, harness.channel().readInbound());
            unrelated.release();
        } finally {
            harness.channel().finishAndReleaseAll();
        }
    }

    @Test
    void malformedTargetIsConsumedAndReportsFailureExactlyOnce() throws Exception {
        Object play = new Object();
        AtomicInteger failures = new AtomicInteger();
        Harness harness = newHarness(play, play, failure -> {
            failures.incrementAndGet();
            throw new AssertionError("listener failure must stay outside the backend pipeline");
        });
        ByteBuf first = Unpooled.buffer();
        writeVarInt(first, profile.blockUpdatePacketId());
        first.writeByte(0);
        ByteBuf second = Unpooled.buffer();
        writeVarInt(second, profile.blockUpdatePacketId());
        try {
            assertFalse(harness.channel().writeInbound(first));
            assertEquals(0, first.refCnt());
            assertEquals(1, failures.get());
            assertFalse(harness.lease().active());
            assertTrue(harness.channel().isActive());

            assertFalse(harness.channel().writeInbound(second));
            assertEquals(0, second.refCnt());
            assertEquals(1, failures.get());
            assertTrue(harness.channel().isActive());
        } finally {
            harness.channel().finishAndReleaseAll();
        }
    }

    @Test
    void staleEvidenceGuardConsumesTargetAndFailsExactlyOnce() throws Exception {
        Object play = new Object();
        AtomicInteger failures = new AtomicInteger();
        Harness harness = newHarness(
                play,
                play,
                () -> false,
                failure -> failures.incrementAndGet());
        ByteBuf target = blockUpdate(STONE_BRICKS_SOURCE);
        ByteBuf unrelated = Unpooled.buffer().writeByte(0x7F).writeByte(1);
        try {
            assertFalse(harness.channel().writeInbound(target));
            assertEquals(0, target.refCnt());
            assertEquals(1, failures.get());
            assertFalse(harness.lease().active());

            assertTrue(harness.channel().writeInbound(unrelated));
            assertSame(unrelated, harness.channel().readInbound());
            unrelated.release();
            assertEquals(1, failures.get());
        } finally {
            harness.channel().finishAndReleaseAll();
        }
    }

    @Test
    void resetAfterSecondEvidenceCheckIsConsumedAtForwardCommit() throws Exception {
        Object play = new Object();
        AtomicInteger evidenceChecks = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicBoolean guardExecuting = new AtomicBoolean();
        AtomicBoolean downstreamObservedGuard = new AtomicBoolean();
        Harness harness = newHarness(
                play,
                play,
                () -> {
                    assertTrue(guardExecuting.compareAndSet(false, true));
                    try {
                        return evidenceChecks.incrementAndGet() <= 2;
                    } finally {
                        guardExecuting.set(false);
                    }
                },
                failure -> {
                    assertFalse(guardExecuting.get());
                    failures.incrementAndGet();
                });
        harness.channel().pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(io.netty.channel.ChannelHandlerContext context, Object message) {
                downstreamObservedGuard.set(guardExecuting.get());
                context.fireChannelRead(message);
            }
        });
        ByteBuf target = blockUpdate(STONE_BRICKS_SOURCE);
        try {
            assertFalse(harness.channel().writeInbound(target));
            assertEquals(0, target.refCnt());
            assertEquals(3, evidenceChecks.get());
            assertEquals(1, failures.get());
            assertFalse(harness.lease().active());
            assertFalse(downstreamObservedGuard.get());
            assertNull(harness.channel().readInbound());
        } finally {
            harness.channel().finishAndReleaseAll();
        }
    }

    @Test
    void successfulForwardRunsDownstreamAfterEvidenceCommitReturns() throws Exception {
        Object play = new Object();
        AtomicInteger evidenceChecks = new AtomicInteger();
        AtomicBoolean guardExecuting = new AtomicBoolean();
        AtomicBoolean downstreamObservedGuard = new AtomicBoolean();
        AtomicInteger downstreamPackets = new AtomicInteger();
        Harness harness = newHarness(
                play,
                play,
                () -> {
                    assertTrue(guardExecuting.compareAndSet(false, true));
                    try {
                        evidenceChecks.incrementAndGet();
                        return true;
                    } finally {
                        guardExecuting.set(false);
                    }
                },
                failure -> {
                    throw new AssertionError("unexpected translation failure", failure);
                });
        harness.channel().pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(io.netty.channel.ChannelHandlerContext context, Object message) {
                downstreamObservedGuard.set(guardExecuting.get());
                downstreamPackets.incrementAndGet();
                context.fireChannelRead(message);
            }
        });
        ByteBuf target = blockUpdate(STONE_BRICKS_SOURCE);
        try {
            assertTrue(harness.channel().writeInbound(target));
            assertEquals(0, target.refCnt());
            assertEquals(3, evidenceChecks.get());
            assertEquals(1, downstreamPackets.get());
            assertFalse(downstreamObservedGuard.get());
            ByteBuf translated = harness.channel().readInbound();
            assertNotNull(translated);
            translated.release();
        } finally {
            harness.channel().finishAndReleaseAll();
        }
    }

    @Test
    void reviewedTargetOutsidePlayPassesThroughUnchanged() throws Exception {
        Object play = new Object();
        Object configuration = new Object();
        Harness harness = newHarness(configuration, play, failure -> {
            throw new AssertionError("unexpected translation failure", failure);
        });
        ByteBuf input = blockUpdate(STONE_BRICKS_SOURCE);
        try {
            assertTrue(harness.channel().writeInbound(input));
            assertEquals(1, input.refCnt());
            ByteBuf forwarded = harness.channel().readInbound();
            assertSame(input, forwarded);
            assertEquals(profile.blockUpdatePacketId(), readVarInt(forwarded));
            assertEquals(0x0123_4567_89AB_CDEFL, forwarded.readLong());
            assertEquals(STONE_BRICKS_SOURCE, readVarInt(forwarded));
            assertFalse(forwarded.isReadable());
            forwarded.release();
        } finally {
            harness.channel().finishAndReleaseAll();
        }
    }

    @Test
    void attachmentWaitsForReconnectBackendConnectionBeforeInstalling() throws Exception {
        AttachmentHarness harness = newAttachmentHarness();
        CompletableFuture<VelocityLobbyPlayPacketTranslator.Lease> attachment =
                harness.translator().attach(
                        harness.endpoint(), profile, failure -> {
                            throw new AssertionError("unexpected translation failure", failure);
                        }, 500L);
        VelocityLobbyPlayPacketTranslator.Lease lease = null;
        try {
            assertFalse(attachment.isDone());
            harness.backend().set(new TestBackendConnection(
                    harness.channel(), harness.playState()));
            lease = attachment.get(2L, TimeUnit.SECONDS);
            assertTrue(lease.active());
            assertNotNull(harness.channel().pipeline().context(
                    "protocolobelisk-block-state-translator"));
        } finally {
            if (lease != null) {
                lease.close();
            }
            attachment.cancel(false);
            harness.channel().finishAndReleaseAll();
        }
    }

    @Test
    void coreOnlyReviewedProfileDoesNotInterceptUnreviewedStructuralSurfaces()
            throws Exception {
        AttachmentHarness harness = newAttachmentHarness();
        harness.backend().set(new TestBackendConnection(
                harness.channel(), harness.playState()));
        AtomicInteger failures = new AtomicInteger();
        VelocityLobbyPlayPacketTranslator.Lease lease = harness.translator().attach(
                harness.endpoint(),
                reviewedCoreProfile(),
                failure -> failures.incrementAndGet(),
                500L).get(2L, TimeUnit.SECONDS);
        ByteBuf particle = Unpooled.buffer();
        writeVarInt(particle, profile.levelParticlesPacketId());
        particle.writeByte(0x55);
        ByteBuf addEntity = Unpooled.buffer();
        writeVarInt(addEntity, profile.addEntityPacketId());
        addEntity.writeByte(0x66);
        ByteBuf blockUpdate = blockUpdate(STONE_BRICKS_SOURCE);
        try {
            assertTrue(lease.active());
            assertTrue(harness.channel().writeInbound(particle));
            assertSame(particle, harness.channel().readInbound());
            particle.release();

            assertTrue(harness.channel().writeInbound(addEntity));
            assertSame(addEntity, harness.channel().readInbound());
            addEntity.release();

            assertTrue(harness.channel().writeInbound(blockUpdate));
            assertEquals(0, blockUpdate.refCnt());
            ByteBuf translated = harness.channel().readInbound();
            assertEquals(profile.blockUpdatePacketId(), readVarInt(translated));
            assertEquals(0x0123_4567_89AB_CDEFL, translated.readLong());
            assertEquals(STONE_BRICKS_SOURCE, readVarInt(translated));
            assertFalse(translated.isReadable());
            translated.release();

            assertEquals(0, failures.get());
            assertTrue(lease.active());
        } finally {
            lease.close();
            if (particle.refCnt() > 0) {
                particle.release();
            }
            if (addEntity.refCnt() > 0) {
                addEntity.release();
            }
            if (blockUpdate.refCnt() > 0) {
                blockUpdate.release();
            }
            harness.channel().finishAndReleaseAll();
        }
    }

    @Test
    void bootAuditUsesUnionOfReviewedProfilesInsteadOfFirstProfile() throws Exception {
        BlockStateTranslationProfile coreOnly = reviewedCoreProfile();

        VelocityLobbyPlayPacketTranslator.ReviewedPacketSurface surface =
                VelocityLobbyPlayPacketTranslator.reviewedPacketSurface(
                        List.of(coreOnly, profile));

        assertEquals(767, surface.minecraftProtocol());
        assertEquals(profile.rewrittenPacketIds(), surface.packetIds());
        assertTrue(surface.packetIds().contains(profile.addEntityPacketId()));
        assertTrue(surface.packetIds().contains(profile.levelParticlesPacketId()));
    }

    @Test
    void attachmentTimesOutFailClosedWhenBackendNeverBecomesReady() throws Exception {
        AttachmentHarness harness = newAttachmentHarness();
        CompletableFuture<VelocityLobbyPlayPacketTranslator.Lease> attachment =
                harness.translator().attach(
                        harness.endpoint(), profile, failure -> {
                            throw new AssertionError("unexpected translation failure", failure);
                        }, 40L);
        try {
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> attachment.get(2L, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains(
                    "was not established within 40 ms"));
            assertNull(harness.channel().pipeline().context(
                    "protocolobelisk-block-state-translator"));
        } finally {
            attachment.cancel(false);
            harness.channel().finishAndReleaseAll();
        }
    }

    @Test
    void cancelledStaleAttachmentCannotInstallOnLaterBackend() throws Exception {
        AttachmentHarness harness = newAttachmentHarness();
        CompletableFuture<VelocityLobbyPlayPacketTranslator.Lease> attachment =
                harness.translator().attach(
                        harness.endpoint(), profile, failure -> {
                            throw new AssertionError("unexpected translation failure", failure);
                        }, 500L);
        try {
            assertTrue(attachment.cancel(false));
            harness.backend().set(new TestBackendConnection(
                    harness.channel(), harness.playState()));
            Thread.sleep(50L);
            assertNull(harness.channel().pipeline().context(
                    "protocolobelisk-block-state-translator"));
        } finally {
            harness.channel().finishAndReleaseAll();
        }
    }

    private static Harness newHarness(
            Object connectionState,
            Object playState,
            VelocityLobbyPlayPacketTranslator.FailureListener failureListener)
            throws ReflectiveOperationException {
        return newHarness(connectionState, playState, () -> true, failureListener);
    }

    private static Harness newHarness(
            Object connectionState,
            Object playState,
            VelocityLobbyPlayPacketTranslator.EvidenceGuard evidenceGuard,
            VelocityLobbyPlayPacketTranslator.FailureListener failureListener)
            throws ReflectiveOperationException {
        EmbeddedChannel channel = new EmbeddedChannel();
        TestConnection connection = new TestConnection(connectionState);
        Method getState = TestConnection.class.getDeclaredMethod("getState");
        getState.setAccessible(true);

        Class<?> leaseClass = Class.forName(
                VelocityLobbyPlayPacketTranslator.class.getName() + "$TranslationLease");
        Constructor<?> leaseConstructor = leaseClass.getDeclaredConstructor(Channel.class);
        leaseConstructor.setAccessible(true);
        Object rawLease = leaseConstructor.newInstance(channel);
        VelocityLobbyPlayPacketTranslator.Lease lease =
                (VelocityLobbyPlayPacketTranslator.Lease) rawLease;

        Class<?> handlerClass = Class.forName(
                VelocityLobbyPlayPacketTranslator.class.getName() + "$TranslationHandler");
        Constructor<?> handlerConstructor = handlerClass.getDeclaredConstructor(
                Object.class,
                Method.class,
                Object.class,
                Set.class,
                Minecraft1211BlockStatePacketTranslator.class,
                leaseClass,
                VelocityLobbyPlayPacketTranslator.EvidenceGuard.class,
                VelocityLobbyPlayPacketTranslator.FailureListener.class);
        handlerConstructor.setAccessible(true);
        Object rawHandler = handlerConstructor.newInstance(
                connection,
                getState,
                playState,
                profile.rewrittenPacketIds(),
                new Minecraft1211BlockStatePacketTranslator(profile),
                rawLease,
                evidenceGuard,
                failureListener);
        ChannelInboundHandlerAdapter handler = (ChannelInboundHandlerAdapter) rawHandler;

        Method bind = leaseClass.getDeclaredMethod("bind", handlerClass);
        bind.setAccessible(true);
        bind.invoke(rawLease, rawHandler);
        channel.pipeline().addLast("translator-under-test", handler);
        return new Harness(channel, lease);
    }

    private static AttachmentHarness newAttachmentHarness() throws ReflectiveOperationException {
        Object playState = new Object();
        Object clientboundDirection = new Object();
        AtomicReference<TestBackendConnection> backend = new AtomicReference<>();
        TestServerConnection endpoint = (TestServerConnection) Proxy.newProxyInstance(
                VelocityLobbyPlayPacketTranslatorTest.class.getClassLoader(),
                new Class<?>[] {TestServerConnection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) {
                        return backend.get();
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(
                "test-clientbound-decoder", new TestDecoder(clientboundDirection));

        Method getConnection = TestServerConnection.class.getMethod("getConnection");
        Method getChannel = TestBackendConnection.class.getDeclaredMethod("getChannel");
        Method getState = TestBackendConnection.class.getDeclaredMethod("getState");
        Method getDirection = TestDecoder.class.getDeclaredMethod("getDirection");
        getConnection.setAccessible(true);
        getChannel.setAccessible(true);
        getState.setAccessible(true);
        getDirection.setAccessible(true);

        Constructor<VelocityLobbyPlayPacketTranslator> constructor =
                VelocityLobbyPlayPacketTranslator.class.getDeclaredConstructor(
                        Class.class,
                        Class.class,
                        Class.class,
                        Method.class,
                        Method.class,
                        Method.class,
                        Method.class,
                        Object.class,
                        Object.class,
                        BlockStateTranslationProfile.class,
                        Set.class);
        constructor.setAccessible(true);
        VelocityLobbyPlayPacketTranslator translator = constructor.newInstance(
                TestServerConnection.class,
                TestBackendConnection.class,
                TestDecoder.class,
                getConnection,
                getChannel,
                getState,
                getDirection,
                playState,
                clientboundDirection,
                profile,
                profile.rewrittenPacketIds());
        return new AttachmentHarness(translator, endpoint, backend, channel, playState);
    }

    private static ByteBuf blockUpdate(int state) {
        ByteBuf packet = Unpooled.buffer();
        writeVarInt(packet, profile.blockUpdatePacketId());
        packet.writeLong(0x0123_4567_89AB_CDEFL);
        writeVarInt(packet, state);
        return packet;
    }

    private static BlockStateTranslationProfile reviewedCoreProfile()
            throws ReflectiveOperationException {
        int[] identityMap = new int[profile.sourceStateCount()];
        for (int sourceId = 0; sourceId < identityMap.length; sourceId++) {
            identityMap[sourceId] = sourceId;
        }
        Constructor<BlockStateTranslationProfile> constructor =
                BlockStateTranslationProfile.class.getDeclaredConstructor(
                        String.class,
                        int.class,
                        int[].class,
                        int.class,
                        int.class,
                        int.class,
                        String.class,
                        String.class,
                        String.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        Set.class,
                        OptionalInt.class,
                        Set.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                "reviewed-core-only-test",
                767,
                identityMap,
                identityMap.length,
                profile.sourceGlobalPaletteBits(),
                profile.sourceGlobalPaletteBits(),
                "0".repeat(64),
                profile.vanillaStateTableSha256(),
                "1".repeat(64),
                -1,
                profile.blockUpdatePacketId(),
                profile.levelChunkWithLightPacketId(),
                profile.levelEventPacketId(),
                -1,
                profile.sectionBlocksUpdatePacketId(),
                -1,
                Set.of(),
                OptionalInt.empty(),
                Set.of(
                        BlockStateTranslationProfile.RewriteCapability.BLOCK_UPDATE,
                        BlockStateTranslationProfile.RewriteCapability.CHUNK_BLOCK_STATES,
                        BlockStateTranslationProfile.RewriteCapability.BLOCK_LEVEL_EVENT,
                        BlockStateTranslationProfile.RewriteCapability.SECTION_BLOCKS_UPDATE));
    }

    private static int readVarInt(ByteBuf input) {
        int value = 0;
        for (int index = 0; index < 5; index++) {
            int current = input.readUnsignedByte();
            value |= (current & 0x7F) << (index * 7);
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("test VarInt exceeds five bytes");
    }

    private static void writeVarInt(ByteBuf output, int value) {
        int remaining = value;
        do {
            int current = remaining & 0x7F;
            remaining >>>= 7;
            output.writeByte(remaining == 0 ? current : current | 0x80);
        } while (remaining != 0);
    }

    private record Harness(
            EmbeddedChannel channel,
            VelocityLobbyPlayPacketTranslator.Lease lease) {
    }

    private record AttachmentHarness(
            VelocityLobbyPlayPacketTranslator translator,
            TestServerConnection endpoint,
            AtomicReference<TestBackendConnection> backend,
            EmbeddedChannel channel,
            Object playState) {
    }

    private interface TestServerConnection extends ServerConnection {
        Object getConnection();
    }

    private static final class TestBackendConnection {
        private final Channel channel;
        private final Object state;

        private TestBackendConnection(Channel channel, Object state) {
            this.channel = channel;
            this.state = state;
        }

        @SuppressWarnings("unused")
        private Channel getChannel() {
            return channel;
        }

        @SuppressWarnings("unused")
        private Object getState() {
            return state;
        }
    }

    private static final class TestDecoder extends ChannelInboundHandlerAdapter {
        private final Object direction;

        private TestDecoder(Object direction) {
            this.direction = direction;
        }

        @SuppressWarnings("unused")
        private Object getDirection() {
            return direction;
        }
    }

    private static final class TestConnection {
        private final Object state;

        private TestConnection(Object state) {
            this.state = state;
        }

        @SuppressWarnings("unused")
        private Object getState() {
            return state;
        }
    }
}

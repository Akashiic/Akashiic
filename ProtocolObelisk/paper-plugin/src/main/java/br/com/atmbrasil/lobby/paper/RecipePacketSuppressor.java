package br.com.atmbrasil.lobby.paper;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;

/** Ordered, independently testable outbound recipe policy used by the Paper channel hook. */
final class RecipePacketSuppressor extends ChannelOutboundHandlerAdapter {
    static final String RECIPE_BOOK_PACKET_CLASS =
            "net.minecraft.network.protocol.game.ClientboundRecipePacket";
    static final String RECIPE_DEFINITIONS_PACKET_CLASS =
            "net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket";

    private static final AttributeKey<Boolean> FIRST_RECIPE_BOOK_DROP_REPORTED =
            AttributeKey.valueOf("atm10_lobby_recipe_guard.first_recipe_book_drop_reported");
    private static final AttributeKey<Boolean> FIRST_RECIPE_DEFINITIONS_REWRITE_REPORTED =
            AttributeKey.valueOf(
                    "atm10_lobby_recipe_guard.first_recipe_definitions_rewrite_reported");
    private static final AttributeKey<Boolean> RECIPE_DEFINITIONS_RELEASE_ARMED =
            AttributeKey.valueOf(
                    "atm10_lobby_recipe_guard.recipe_definitions_release_armed");
    private static final AttributeKey<Boolean> RECIPE_DEFINITIONS_RELEASED =
            AttributeKey.valueOf(
                    "atm10_lobby_recipe_guard.recipe_definitions_released");

    private final boolean suppressRecipeBookPackets;
    private final boolean rewriteRecipeDefinitionPackets;
    private final LongAdder droppedRecipeBookPackets;
    private final LongAdder suppressedRecipeDefinitionPackets;
    private final LongAdder rewrittenRecipeDefinitionPackets;
    private final EmptyRecipeDefinitionsPacketFactory emptyRecipeDefinitionsPacketFactory;
    private final BiConsumer<Channel, RecipePacketType> firstActionReporter;

    RecipePacketSuppressor(
            boolean suppressRecipeBookPackets,
            boolean rewriteRecipeDefinitionPackets,
            LongAdder droppedRecipeBookPackets,
            LongAdder suppressedRecipeDefinitionPackets,
            LongAdder rewrittenRecipeDefinitionPackets,
            EmptyRecipeDefinitionsPacketFactory emptyRecipeDefinitionsPacketFactory,
            BiConsumer<Channel, RecipePacketType> firstActionReporter) {
        this.suppressRecipeBookPackets = suppressRecipeBookPackets;
        this.rewriteRecipeDefinitionPackets = rewriteRecipeDefinitionPackets;
        this.droppedRecipeBookPackets = Objects.requireNonNull(
                droppedRecipeBookPackets, "droppedRecipeBookPackets");
        this.suppressedRecipeDefinitionPackets = Objects.requireNonNull(
                suppressedRecipeDefinitionPackets, "suppressedRecipeDefinitionPackets");
        this.rewrittenRecipeDefinitionPackets = Objects.requireNonNull(
                rewrittenRecipeDefinitionPackets, "rewrittenRecipeDefinitionPackets");
        if (rewriteRecipeDefinitionPackets && emptyRecipeDefinitionsPacketFactory == null) {
            throw new IllegalArgumentException(
                    "recipe-definition rewriting requires an empty packet factory");
        }
        this.emptyRecipeDefinitionsPacketFactory = emptyRecipeDefinitionsPacketFactory;
        this.firstActionReporter = Objects.requireNonNull(
                firstActionReporter, "firstActionReporter");
    }

    static RecipePacketType packetType(Object message) {
        if (message == null) {
            return RecipePacketType.NONE;
        }
        String className = message.getClass().getName();
        if (className.equals(RECIPE_BOOK_PACKET_CLASS)) {
            return RecipePacketType.RECIPE_BOOK;
        }
        if (className.equals(RECIPE_DEFINITIONS_PACKET_CLASS)) {
            return RecipePacketType.RECIPE_DEFINITIONS;
        }
        return RecipePacketType.NONE;
    }

    static void armRecipeDefinitionsRelease(Channel channel) {
        Objects.requireNonNull(channel, "channel")
                .attr(RECIPE_DEFINITIONS_RELEASE_ARMED)
                .set(Boolean.TRUE);
    }

    static boolean recipeDefinitionsReleased(Channel channel) {
        return Boolean.TRUE.equals(Objects.requireNonNull(channel, "channel")
                .attr(RECIPE_DEFINITIONS_RELEASED)
                .get());
    }

    @Override
    public void write(
            ChannelHandlerContext context,
            Object message,
            ChannelPromise promise) throws Exception {
        RecipePacketType packetType = packetType(message);
        switch (packetType) {
            case RECIPE_BOOK -> {
                if (suppressRecipeBookPackets) {
                    dropRecipeBook(context, message, promise);
                } else {
                    context.write(message, promise);
                }
            }
            case RECIPE_DEFINITIONS -> {
                if (rewriteRecipeDefinitionPackets) {
                    handleRecipeDefinitions(context, message, promise);
                } else {
                    context.write(message, promise);
                }
            }
            case NONE -> context.write(message, promise);
        }
    }

    private void dropRecipeBook(
            ChannelHandlerContext context,
            Object message,
            ChannelPromise promise) {
        ReferenceCountUtil.release(message);
        droppedRecipeBookPackets.increment();
        promise.trySuccess();
        reportFirst(context.channel(), FIRST_RECIPE_BOOK_DROP_REPORTED, RecipePacketType.RECIPE_BOOK);
    }

    private void handleRecipeDefinitions(
            ChannelHandlerContext context,
            Object message,
            ChannelPromise promise) {
        Channel channel = context.channel();
        boolean releaseArmed = Boolean.TRUE.equals(
                channel.attr(RECIPE_DEFINITIONS_RELEASE_ARMED).get());
        boolean firstReleasedPacket = releaseArmed
                && channel.attr(RECIPE_DEFINITIONS_RELEASED)
                        .setIfAbsent(Boolean.TRUE) == null;
        if (!firstReleasedPacket) {
            suppressRecipeDefinitions(channel, message, promise);
            return;
        }

        Object replacement;
        try {
            replacement = emptyRecipeDefinitionsPacketFactory.create();
        } catch (RuntimeException exception) {
            ReferenceCountUtil.release(message);
            promise.tryFailure(exception);
            throw exception;
        }
        ReferenceCountUtil.release(message);
        rewrittenRecipeDefinitionPackets.increment();
        context.write(replacement, promise);
    }

    private void suppressRecipeDefinitions(
            Channel channel,
            Object message,
            ChannelPromise promise) {
        ReferenceCountUtil.release(message);
        suppressedRecipeDefinitionPackets.increment();
        promise.trySuccess();
        reportFirst(
                channel,
                FIRST_RECIPE_DEFINITIONS_REWRITE_REPORTED,
                RecipePacketType.RECIPE_DEFINITIONS);
    }

    private void reportFirst(
            Channel channel,
            AttributeKey<Boolean> reportKey,
            RecipePacketType packetType) {
        if (channel.attr(reportKey).setIfAbsent(Boolean.TRUE) == null) {
            firstActionReporter.accept(channel, packetType);
        }
    }

    enum RecipePacketType {
        NONE,
        RECIPE_BOOK,
        RECIPE_DEFINITIONS
    }
}

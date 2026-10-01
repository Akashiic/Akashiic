package br.com.atmbrasil.lobby.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.paper.RecipePacketSuppressor.RecipePacketType;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

final class PaperRecipePacketGuardTest {
    @Test
    void classifiesOnlyTheTwoExactMojangMappedRecipePackets() {
        assertEquals(RecipePacketType.RECIPE_BOOK, RecipePacketSuppressor.packetType(
                new net.minecraft.network.protocol.game.ClientboundRecipePacket()));
        assertEquals(RecipePacketType.RECIPE_DEFINITIONS, RecipePacketSuppressor.packetType(
                new net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket(
                        List.of())));
        assertEquals(RecipePacketType.NONE, RecipePacketSuppressor.packetType(null));
        assertEquals(RecipePacketType.NONE, RecipePacketSuppressor.packetType(new Object()));
    }

    @Test
    void outboundGuardDefersDefinitionsUntilReleaseThenEmitsExactlyOneEmptyPacket()
            throws Exception {
        LongAdder recipeBookDrops = new LongAdder();
        LongAdder recipeDefinitionSuppressions = new LongAdder();
        LongAdder recipeDefinitionRewrites = new LongAdder();
        AtomicInteger recipeBookReports = new AtomicInteger();
        AtomicInteger recipeDefinitionReports = new AtomicInteger();
        EmptyRecipeDefinitionsPacketFactory factory =
                EmptyRecipeDefinitionsPacketFactory.resolve(getClass().getClassLoader());
        EmbeddedChannel channel = new EmbeddedChannel(new RecipePacketSuppressor(
                true,
                true,
                recipeBookDrops,
                recipeDefinitionSuppressions,
                recipeDefinitionRewrites,
                factory,
                (ignored, packetType) -> {
                    if (packetType == RecipePacketType.RECIPE_BOOK) {
                        recipeBookReports.incrementAndGet();
                    } else if (packetType == RecipePacketType.RECIPE_DEFINITIONS) {
                        recipeDefinitionReports.incrementAndGet();
                    }
                }));

        assertFalse(channel.writeOutbound(
                new net.minecraft.network.protocol.game.ClientboundRecipePacket()));
        assertFalse(channel.writeOutbound(
                new net.minecraft.network.protocol.game.ClientboundRecipePacket()));
        var original = new net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket(
                List.of("unsafe-paper-recipe"));
        assertFalse(channel.writeOutbound(original));
        assertFalse(RecipePacketSuppressor.recipeDefinitionsReleased(channel));

        RecipePacketSuppressor.armRecipeDefinitionsRelease(channel);
        var released = new net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket(
                List.of("still-unsafe-paper-recipe"));
        assertTrue(channel.writeOutbound(released));
        Object firstReplacement = channel.readOutbound();
        assertNotSame(released, firstReplacement);
        assertTrue(firstReplacement
                instanceof net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket);
        assertTrue(((net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket)
                firstReplacement).recipes().isEmpty());

        assertFalse(channel.writeOutbound(
                new net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket(
                        List.of("another-paper-recipe"))));
        assertTrue(RecipePacketSuppressor.recipeDefinitionsReleased(channel));
        assertEquals(2L, recipeBookDrops.sum());
        assertEquals(2L, recipeDefinitionSuppressions.sum());
        assertEquals(1L, recipeDefinitionRewrites.sum());
        assertEquals(1, recipeBookReports.get());
        assertEquals(1, recipeDefinitionReports.get());

        Object allowed = new Object();
        assertTrue(channel.writeOutbound(allowed));
        assertSame(allowed, channel.readOutbound());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void eachRecipeStreamCanBeConfiguredIndependently() {
        LongAdder recipeBookDrops = new LongAdder();
        LongAdder recipeDefinitionSuppressions = new LongAdder();
        LongAdder recipeDefinitionRewrites = new LongAdder();
        EmbeddedChannel channel = new EmbeddedChannel(new RecipePacketSuppressor(
                true,
                false,
                recipeBookDrops,
                recipeDefinitionSuppressions,
                recipeDefinitionRewrites,
                null,
                (ignoredChannel, ignoredType) -> { }));

        assertFalse(channel.writeOutbound(
                new net.minecraft.network.protocol.game.ClientboundRecipePacket()));
        Object definitions =
                new net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket(
                        List.of("paper-recipe"));
        assertTrue(channel.writeOutbound(definitions));
        assertSame(definitions, channel.readOutbound());
        assertEquals(1L, recipeBookDrops.sum());
        assertEquals(0L, recipeDefinitionSuppressions.sum());
        assertEquals(0L, recipeDefinitionRewrites.sum());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void rewritePolicyFailsClosedWithoutTheExactPacketFactory() {
        assertThrows(IllegalArgumentException.class, () -> new RecipePacketSuppressor(
                false,
                true,
                new LongAdder(),
                new LongAdder(),
                new LongAdder(),
                null,
                (ignoredChannel, ignoredType) -> { }));
    }

    @Test
    void paperLifecycleReflectionSurfaceResolvesAgainstTheReviewedShape() throws Exception {
        EmptyRecipeDefinitionsPacketFactory factory =
                EmptyRecipeDefinitionsPacketFactory.resolve(getClass().getClassLoader());
        PaperRecipeLifecycleReleaser.resolve(getClass().getClassLoader(), factory);
    }
}

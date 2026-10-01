package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class BlockStateTranslationProfileTest {
    private static final String PROFILE_ID =
            "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247";
    private static final String FULL_CONTRACT =
            "a61293a54b0f80c83b73b5e2968e8de08b9595771110fa7c7cbea9e55ad2892f";
    private static final String FROZEN_SEQUENCE =
            "8fda0099a55ad36898adbf43dc77d5b702c6d4c5c26c1bcddb3039669dd00269";

    private static BlockStateTranslationProfile embedded;

    @BeforeAll
    static void loadEmbeddedProfile() throws IOException {
        embedded = BlockStateTranslationProfile.loadAtm10Normal80(
                BlockStateTranslationProfileTest.class.getClassLoader(),
                SilentGearEmbeddedProfile.ATM10_NORMAL_8_0_RESOURCE_ROOT,
                PROFILE_ID,
                767,
                FULL_CONTRACT,
                FROZEN_SEQUENCE);
    }

    @Test
    void exactEmbeddedProfilePreservesEveryReviewedRewriteSurface() {
        assertEquals(
                Set.of(
                        embedded.addEntityPacketId(),
                        embedded.blockUpdatePacketId(),
                        embedded.levelChunkWithLightPacketId(),
                        embedded.levelEventPacketId(),
                        embedded.levelParticlesPacketId(),
                        embedded.sectionBlocksUpdatePacketId()),
                embedded.rewrittenPacketIds());
        assertTrue(embedded.supportsRewriteCapability(
                BlockStateTranslationProfile.RewriteCapability.BLOCK_PARTICLE));
        assertTrue(embedded.supportsRewriteCapability(
                BlockStateTranslationProfile.RewriteCapability.FALLING_BLOCK_ENTITY));
        assertTrue(embedded.isBlockParticleType(1));
        assertEquals(40, embedded.fallingBlockEntityTypeId());
    }

}

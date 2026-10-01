package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Exact CONFIGURATION additions required by a reviewed executable client profile.
 *
 * <p>The common NeoForge config-file and frozen-registry channels are owned by
 * {@link NeoForgeFrozenRegistryProfile}. This class contains only pack-specific registrations and
 * bootstraps whose server behavior was audited against the corresponding official backend. It is
 * deliberately keyed by the complete client contract, never by a loose mod or pack version.</p>
 */
final class ReviewedConfigurationProfile {
    static final String ATM10_NORMAL_7_3_FULL_CLIENT_CONTRACT_SHA256 =
            "cfce57a5a93240f97d570e952c3b4d71e5fac1f11504289f5ea4265371da559a";
    static final String ATM10_NORMAL_8_0_FULL_CLIENT_CONTRACT_SHA256 =
            "a61293a54b0f80c83b73b5e2968e8de08b9595771110fa7c7cbea9e55ad2892f";
    static final String MEKANISM_BATCH_SECURITY = "mekanism:batch_security";

    private static final List<Channel> ATM10_NORMAL_7_3_ADDITIONAL_CHANNELS = List.of(
            required("trashcans:main", "1", Flow.BIDIRECTIONAL),
            required("owo:handshake_off", "1.0.0", Flow.CLIENTBOUND),
            required("simplemagnets:main", "1", Flow.BIDIRECTIONAL),
            required("owo:handshake", "1.0.0", Flow.BIDIRECTIONAL),
            required(MEKANISM_BATCH_SECURITY, "10.7.19", Flow.CLIENTBOUND),
            required("wirelesschargers:main", "1", Flow.BIDIRECTIONAL),
            required("itemcollectors:main", "1", Flow.BIDIRECTIONAL),
            required("rechiseled:main", "1", Flow.BIDIRECTIONAL),
            required("enderstorage:network", "2.13.0.191", Flow.CLIENTBOUND));

    // PacketBatchSecurityUpdate encodes two empty maps as two zero VarInts. The Paper lobby has
    // no Mekanism security manager, so the reviewed fresh-backend snapshot is the exact neutral
    // state rather than data copied from a gameplay world.
    private static final List<Bootstrap> ATM10_NORMAL_7_3_BOOTSTRAPS = List.of(
            new Bootstrap(MEKANISM_BATCH_SECURITY, new byte[] {0, 0}, false));

    private static final Set<String> OWNED_CHANNEL_IDS = buildOwnedChannelIds();

    private ReviewedConfigurationProfile() {
    }

    static List<Channel> channels(SilentGearEmbeddedProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (!isAtm10Normal(profile)) {
            return NeoForgeFrozenRegistryProfile.reviewedConfigurationChannels();
        }
        java.util.ArrayList<Channel> channels = new java.util.ArrayList<>(
                NeoForgeFrozenRegistryProfile.reviewedConfigurationChannels());
        channels.addAll(ATM10_NORMAL_7_3_ADDITIONAL_CHANNELS);
        return List.copyOf(channels);
    }

    static List<Bootstrap> bootstraps(SilentGearEmbeddedProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return isAtm10Normal(profile) ? ATM10_NORMAL_7_3_BOOTSTRAPS : List.of();
    }

    static Set<String> advertisedChannelIds(SilentGearEmbeddedProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (!isAtm10Normal(profile)) {
            return Set.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ATM10_NORMAL_7_3_ADDITIONAL_CHANNELS.forEach(channel -> ids.add(channel.id()));
        return Set.copyOf(ids);
    }

    static Set<String> ownedChannelIds() {
        return OWNED_CHANNEL_IDS;
    }

    static boolean isAtm10Normal73(SilentGearEmbeddedProfile profile) {
        return isExactAtm10Normal(profile, ATM10_NORMAL_7_3_FULL_CLIENT_CONTRACT_SHA256);
    }

    static boolean isAtm10Normal80(SilentGearEmbeddedProfile profile) {
        return isExactAtm10Normal(profile, ATM10_NORMAL_8_0_FULL_CLIENT_CONTRACT_SHA256);
    }

    static boolean isAtm10Normal(SilentGearEmbeddedProfile profile) {
        return isAtm10Normal73(profile) || isAtm10Normal80(profile);
    }

    private static boolean isExactAtm10Normal(
            SilentGearEmbeddedProfile profile, String fullClientContractSha256) {
        return profile.minecraftProtocol() == 767
                && profile.fullClientContractSha256()
                        .filter(fullClientContractSha256::equals)
                        .isPresent()
                && profile.silentGearChannelContractSha256().equals(
                        SilentGearProtocol.ATM10_NORMAL_4_2.canonicalContractSha256());
    }

    private static Set<String> buildOwnedChannelIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ATM10_NORMAL_7_3_ADDITIONAL_CHANNELS.forEach(channel -> ids.add(channel.id()));
        if (ids.size() != ATM10_NORMAL_7_3_ADDITIONAL_CHANNELS.size()) {
            throw new ExceptionInInitializerError(
                    "duplicate reviewed ATM10 Normal CONFIGURATION channel");
        }
        return Set.copyOf(ids);
    }

    private static Channel required(String id, String version, Flow flow) {
        return new Channel(id, version, flow, false);
    }

    /** Immutable clientbound CONFIGURATION payload with an explicit ACK contract. */
    record Bootstrap(String channelId, byte[] bytes, boolean acknowledgementRequired) {
        Bootstrap {
            if (Objects.requireNonNull(channelId, "channelId").isBlank()) {
                throw new IllegalArgumentException("bootstrap channel id must not be blank");
            }
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}

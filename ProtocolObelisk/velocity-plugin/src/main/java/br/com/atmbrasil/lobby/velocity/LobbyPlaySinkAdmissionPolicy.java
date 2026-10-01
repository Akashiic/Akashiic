package br.com.atmbrasil.lobby.velocity;

import java.util.Objects;
import java.util.Set;

/** Service-first admission with bounded, session-scoped plugin-message ownership. */
final class LobbyPlaySinkAdmissionPolicy {
    private LobbyPlaySinkAdmissionPolicy() {
    }

    /** Unknown client contracts retain the same bounded automatic capability budget. */
    static int automaticBudget(boolean reviewedClientContract, int configuredBudget) {
        if (configuredBudget < 0) {
            throw new IllegalArgumentException("configuredBudget must not be negative");
        }
        return configuredBudget;
    }

    /** Vanilla and protocol-bypass sessions retain native plugin-message pass-through. */
    static boolean interceptsProtocolChannels(
            boolean lifecycleStateEligible, boolean nativePassThroughNegotiation) {
        return lifecycleStateEligible && !nativePassThroughNegotiation;
    }

    /**
     * A globally registered id is consumed only for the player session which negotiated it.
     * Backend-originated messages, proxy transport and externally owned Voice Chat ids always
     * retain their native processing path.
     */
    static boolean consumesPlayerPayload(
            boolean playerSource, Set<String> sessionChannelIds, String channelId) {
        Objects.requireNonNull(sessionChannelIds, "sessionChannelIds");
        Objects.requireNonNull(channelId, "channelId");
        return playerSource
                && sessionChannelIds.contains(channelId)
                && !ProxyPluginMessageOwnership.externallyOwns(channelId)
                && !ReviewedSimpleVoiceChatExtension.externallyOwns(channelId);
    }
}

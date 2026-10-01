package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import java.util.Objects;

/** Narrow structural evidence for the ATM10 8.2 networking lineage. */
final class Atm10Normal82Contract {
    static final String ID = "atm10-normal-8.2-lineage";
    static final int PROTOCOL_VERSION = 767;
    static final String LOGISTICS_MOD_VERSION = "1.16.3";
    static final String LOGISTICS_CHANNEL_ID = "logisticsnetworks:sync_modifier_keys";
    static final String LOGISTICS_NETWORK_VERSION = "9";
    static final int LOGISTICS_PAYLOAD_BYTES = 1;
    static final String REQUIRED_AD_ASTRA_NAMESPACE = "ad_astra";
    static final String REVIEWED_AD_ASTRA_NETWORK_ID = "ad_astra:main";

    private Atm10Normal82Contract() {
    }

    /**
     * Proves only the narrow registry-merge capability, never admission or full pack identity.
     *
     * <p>ATM10 8.1 used LogisticsNetworks 1.13.0/network v1 and therefore cannot satisfy this
     * predicate. The 8.2 client crash and the reviewed LogisticsNetworks 1.16.3 source establish
     * the new serverbound payload at network version 9. Requiring the independently advertised
     * Ad Astra namespace prevents this proof from authorizing the Giselle merge for unrelated
     * LogisticsNetworks installations. The Ad Astra 1.21.1 source independently registers its
     * network at {@code ad_astra:main}; namespace matching intentionally tolerates the payload
     * identifiers derived beneath that reviewed network abstraction.</p>
     */
    static boolean matchesGiselleRegistryMergeEvidence(
            int minecraftProtocol, Registry registry) {
        Objects.requireNonNull(registry, "registry");
        if (minecraftProtocol != PROTOCOL_VERSION) {
            return false;
        }
        boolean adAstraAdvertised = registry.channelIds().stream()
                .anyMatch(id -> namespace(id).equals(REQUIRED_AD_ASTRA_NAMESPACE));
        if (!adAstraAdvertised) {
            return false;
        }
        return registry.channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .anyMatch(Atm10Normal82Contract::isExactLogisticsModifierKeyChannel);
    }

    private static boolean isExactLogisticsModifierKeyChannel(Channel channel) {
        return channel.id().equals(LOGISTICS_CHANNEL_ID)
                && channel.version().equals(LOGISTICS_NETWORK_VERSION)
                && channel.flow() == Flow.SERVERBOUND
                && !channel.optional();
    }

    private static String namespace(String id) {
        int separator = id.indexOf(':');
        return separator < 0 ? id : id.substring(0, separator);
    }
}

package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Chooses the compatibility pack whose captured server would have accepted this client.
 *
 * <p>The predicate is NeoForge 21.1's own {@code NetworkComponentNegotiator}, applied per
 * connection protocol to the pack's captured server registrations and the client's query:</p>
 * <ul>
 *   <li>a required server channel absent from the client fails;</li>
 *   <li>a required client channel absent from the server fails;</li>
 *   <li>a channel present on both sides fails when a declared flow differs or the versions are
 *       not identical.</li>
 * </ul>
 *
 * <p>This is selection of structural data, never admission: a client that matches no pack is
 * still admitted under the cardinal policy and only receives the generic enrichment. There is no
 * fingerprint allowlist. When several packs are compatible the one sharing the most channels with
 * the client wins; an exact tie is reported as ambiguous and no pack is selected.</p>
 */
final class CompatibilityPackSelector {
    static final int MAXIMUM_REPORTED_MISMATCHES = 64;

    private CompatibilityPackSelector() {
    }

    static Selection select(
            int clientProtocol,
            Registry client,
            List<CompatibilityPack> packs,
            String forcedPackId) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(packs, "packs");
        Objects.requireNonNull(forcedPackId, "forcedPackId");
        List<Evaluation> evaluations = new ArrayList<>(packs.size());
        for (CompatibilityPack pack : packs) {
            evaluations.add(evaluate(clientProtocol, client, pack));
        }
        List<Evaluation> compatible = evaluations.stream()
                .filter(Evaluation::compatible)
                .sorted(Comparator.comparingInt(Evaluation::sharedChannels).reversed())
                .toList();
        if (!compatible.isEmpty()) {
            if (compatible.size() > 1
                    && compatible.get(0).sharedChannels() == compatible.get(1).sharedChannels()) {
                return new Selection(Optional.empty(), Status.AMBIGUOUS, evaluations);
            }
            return new Selection(Optional.of(compatible.getFirst().pack()), Status.NEGOTIATED, evaluations);
        }
        if (!forcedPackId.isEmpty()) {
            Optional<CompatibilityPack> forced = packs.stream()
                    .filter(pack -> pack.packId().equals(forcedPackId)
                            && pack.minecraftProtocol() == clientProtocol)
                    .findFirst();
            if (forced.isPresent()) {
                return new Selection(forced, Status.FORCED_BY_OPERATOR, evaluations);
            }
        }
        return new Selection(
                Optional.empty(),
                packs.isEmpty() ? Status.NO_PACKS_INSTALLED : Status.NO_COMPATIBLE_PACK,
                evaluations);
    }

    static Evaluation evaluate(int clientProtocol, Registry client, CompatibilityPack pack) {
        Objects.requireNonNull(pack, "pack");
        List<String> mismatches = new ArrayList<>();
        int mismatchCount = 0;
        int shared = 0;
        if (clientProtocol != pack.minecraftProtocol()) {
            mismatches.add("minecraft protocol " + clientProtocol + " != " + pack.minecraftProtocol());
            return new Evaluation(pack, false, 0, 1, mismatches);
        }
        Registry server = pack.serverChannels();
        for (int protocol : List.of(
                NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL, NeoForgeHandshakeCodec.PLAY_PROTOCOL)) {
            String phase = protocol == NeoForgeHandshakeCodec.PLAY_PROTOCOL ? "play" : "configuration";
            Map<String, Channel> serverChannels = byId(server.channelsFor(protocol));
            Map<String, Channel> clientChannels = byId(client.channelsFor(protocol));
            for (Channel serverChannel : serverChannels.values()) {
                Channel clientChannel = clientChannels.get(serverChannel.id());
                if (clientChannel == null) {
                    if (!serverChannel.optional()) {
                        mismatchCount++;
                        note(mismatches, phase + " server requires " + describe(serverChannel)
                                + " but the client does not have it");
                    }
                    continue;
                }
                String failure = compare(serverChannel, clientChannel);
                if (failure != null) {
                    mismatchCount++;
                    note(mismatches, phase + " " + serverChannel.id() + ": " + failure);
                } else {
                    shared++;
                }
            }
            for (Channel clientChannel : clientChannels.values()) {
                if (!serverChannels.containsKey(clientChannel.id()) && !clientChannel.optional()) {
                    mismatchCount++;
                    note(mismatches, phase + " client requires " + describe(clientChannel)
                            + " but the pack server does not have it");
                }
            }
        }
        return new Evaluation(pack, mismatchCount == 0, shared, mismatchCount, mismatches);
    }

    private static String compare(Channel server, Channel client) {
        boolean serverFlow = server.flow() != Flow.BIDIRECTIONAL;
        boolean clientFlow = client.flow() != Flow.BIDIRECTIONAL;
        if ((serverFlow || clientFlow) && server.flow() != client.flow()) {
            return "flow " + client.flow() + " differs from server " + server.flow();
        }
        if (!server.version().equals(client.version())) {
            return "client version '" + client.version() + "' differs from server '"
                    + server.version() + "'";
        }
        return null;
    }

    private static Map<String, Channel> byId(List<Channel> channels) {
        LinkedHashMap<String, Channel> result = new LinkedHashMap<>();
        for (Channel channel : channels) {
            result.putIfAbsent(channel.id(), channel);
        }
        return result;
    }

    private static String describe(Channel channel) {
        return channel.id() + "@" + channel.version();
    }

    private static void note(List<String> mismatches, String line) {
        if (mismatches.size() < MAXIMUM_REPORTED_MISMATCHES) {
            mismatches.add(line);
        }
    }

    enum Status {
        NEGOTIATED,
        FORCED_BY_OPERATOR,
        AMBIGUOUS,
        NO_COMPATIBLE_PACK,
        NO_PACKS_INSTALLED
    }

    record Evaluation(
            CompatibilityPack pack,
            boolean compatible,
            int sharedChannels,
            int mismatchCount,
            List<String> mismatches) {
        Evaluation {
            Objects.requireNonNull(pack, "pack");
            mismatches = List.copyOf(mismatches);
        }
    }

    record Selection(Optional<CompatibilityPack> pack, Status status, List<Evaluation> evaluations) {
        Selection {
            Objects.requireNonNull(pack, "pack");
            Objects.requireNonNull(status, "status");
            evaluations = List.copyOf(evaluations);
        }

        /** The non-matching evaluation with the fewest mismatches, for operator diagnostics. */
        Optional<Evaluation> closestMismatch() {
            return evaluations.stream()
                    .filter(evaluation -> !evaluation.compatible())
                    .min(Comparator.comparingInt(Evaluation::mismatchCount));
        }
    }
}

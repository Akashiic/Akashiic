package br.com.atmbrasil.lobby.velocity.protocol;

import java.io.ByteArrayOutputStream;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bounded NeoForge 21.1.221 and 21.1.247 lobby-handshake wire helpers.
 *
 * <p>The wire format mirrors ModdedNetworkQueryPayload, ModdedNetworkQueryComponent,
 * NetworkPayloadSetup and NetworkChannel. The runtime first applies an opaque transcript bound,
 * then attempts a bounded decoder using Minecraft's real string envelope. A caller can safely
 * degrade to configured channels if semantic decoding fails. This class deliberately does not
 * depend on Minecraft or NeoForge classes, so it can run inside Velocity.</p>
 */
public final class NeoForgeHandshakeCodec {
    public static final int PLAY_PROTOCOL = 1;
    public static final int CONFIGURATION_PROTOCOL = 4;

    // Utf8String's default Minecraft contract is 32,767 UTF-16 code units. Netty's
    // utf8MaxBytes(int) uses three bytes per unit; the total query bound remains authoritative.
    private static final int MAXIMUM_MINECRAFT_STRING_CHARACTERS = 32_767;
    private static final int MAXIMUM_MINECRAFT_UTF8_BYTES =
            MAXIMUM_MINECRAFT_STRING_CHARACTERS * 3;

    private static final byte[] QUERY_REQUEST = {0};
    private static final byte[] EMPTY_SETUP = {0};
    private static final Pattern RESOURCE_LOCATION = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Comparator<Channel> CHANNEL_ORDER = Comparator
            .comparing(Channel::id)
            .thenComparing(Channel::version)
            .thenComparingInt(channel -> channel.flow().wireOrdinal())
            .thenComparing(Channel::optional);

    private NeoForgeHandshakeCodec() {
    }

    public enum Flow {
        BIDIRECTIONAL(-1),
        SERVERBOUND(0),
        CLIENTBOUND(1);

        private final int wireOrdinal;

        Flow(int wireOrdinal) {
            this.wireOrdinal = wireOrdinal;
        }

        public int wireOrdinal() {
            return wireOrdinal;
        }

        private static Flow fromWireOrdinal(int ordinal) throws ProtocolViolationException {
            return switch (ordinal) {
                case 0 -> SERVERBOUND;
                case 1 -> CLIENTBOUND;
                default -> throw new ProtocolViolationException("invalid packet flow ordinal");
            };
        }
    }

    private enum DecodePolicy {
        FORENSIC,
        LOBBY
    }

    public record Channel(String id, String version, Flow flow, boolean optional) {
        public Channel {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(flow, "flow");
        }
    }

    /** Why a fully consumed, bounded component could not enter the usable registry. */
    public enum IgnoredChannelReason {
        INVALID_RESOURCE_LOCATION,
        INVALID_VERSION,
        INVALID_RESOURCE_LOCATION_AND_VERSION,
        DUPLICATE_CHANNEL_ID
    }

    /**
     * Immutable semantic evidence retained for a lobby component that was safely skipped.
     *
     * <p>This is deliberately richer than an ignored-component count. Runtime exceptions which
     * relay a genuine client advertisement to a backend can therefore bind the exception to the
     * exact unusable component instead of authorizing an arbitrary malformed registration.</p>
     */
    public record IgnoredChannel(
            int protocol,
            String id,
            String version,
            Flow flow,
            boolean optional,
            IgnoredChannelReason reason) {
        public IgnoredChannel {
            if (protocol != PLAY_PROTOCOL && protocol != CONFIGURATION_PROTOCOL) {
                throw new IllegalArgumentException("unsupported ignored-channel protocol");
            }
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(flow, "flow");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Registry(
            Map<Integer, List<Channel>> protocols,
            int declaredChannelCount,
            int ignoredChannelCount,
            List<IgnoredChannel> ignoredChannels) {
        public Registry(Map<Integer, List<Channel>> protocols) {
            this(protocols, countChannels(protocols), 0, List.of());
        }

        public Registry {
            Objects.requireNonNull(protocols, "protocols");
            Objects.requireNonNull(ignoredChannels, "ignoredChannels");
            Map<Integer, List<Channel>> copy = new LinkedHashMap<>();
            protocols.forEach((protocol, channels) -> copy.put(protocol, List.copyOf(channels)));
            protocols = Map.copyOf(copy);
            ignoredChannels = List.copyOf(ignoredChannels);
            int usableChannelCount = countChannels(protocols);
            if (declaredChannelCount < usableChannelCount
                    || ignoredChannelCount != declaredChannelCount - usableChannelCount
                    || ignoredChannelCount != ignoredChannels.size()) {
                throw new IllegalArgumentException("inconsistent registry channel counts");
            }
        }

        public int channelCount() {
            return countChannels(protocols);
        }

        public Set<String> channelIds() {
            Set<String> ids = new HashSet<>();
            protocols.values().forEach(channels -> channels.forEach(channel -> ids.add(channel.id())));
            return Set.copyOf(ids);
        }

        public List<Channel> channelsFor(int protocol) {
            return protocols.getOrDefault(protocol, List.of());
        }

        private static int countChannels(Map<Integer, List<Channel>> protocols) {
            Objects.requireNonNull(protocols, "protocols");
            return protocols.values().stream().mapToInt(List::size).sum();
        }
    }

    public static byte[] queryRequest() {
        return QUERY_REQUEST.clone();
    }

    /** Encoded empty NeoForge network-payload setup retained for diagnostics and regression tests. */
    public static byte[] emptySetup() {
        return EMPTY_SETUP.clone();
    }

    /**
     * Validates the transcript envelope before any optional semantic decoding.
     *
     * <p>This check performs no allocations proportional to claimed field counts. Session ordering
     * is enforced by the caller; the deeper decoder applies independent protocol/channel/string
     * limits.</p>
     */
    public static void validateOpaqueQueryResponse(byte[] payload, ProtocolLimits limits)
            throws ProtocolViolationException {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(limits, "limits");
        if (payload.length == 0) {
            throw new ProtocolViolationException("query response payload is empty");
        }
        if (payload.length > limits.maximumQueryBytes()) {
            throw new ProtocolViolationException(
                    "query response has " + payload.length + " bytes; configured maximum is "
                            + limits.maximumQueryBytes());
        }
    }

    /** Applies the byte boundary to an ignored lobby control payload without decoding it. */
    public static void validateOpaqueControlPayload(byte[] payload, int maximumBytes)
            throws ProtocolViolationException {
        Objects.requireNonNull(payload, "payload");
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        if (payload.length > maximumBytes) {
            throw new ProtocolViolationException(
                    "control payload has " + payload.length + " bytes; configured maximum is "
                            + maximumBytes);
        }
    }

    public static Registry decodeQuery(byte[] payload, ProtocolLimits limits)
            throws ProtocolViolationException {
        return decodeQuery(
                payload,
                limits,
                limits.maximumResourceLocationBytes(),
                limits.maximumVersionBytes(),
                DecodePolicy.FORENSIC);
    }

    /**
     * Decodes a real client registry for bounded lobby planning.
     *
     * <p>Unlike the forensic decoder, this path accepts the full string envelope used by
     * Minecraft's own stream codecs. Allocation is still bounded by the one-megabyte transcript,
     * protocol count and channel count. Components with a semantically unusable id or version are
     * skipped after their complete, bounded wire shape is consumed, so one unusual registration
     * cannot erase every otherwise usable channel in the registry.</p>
     */
    public static Registry decodeLobbyQuery(byte[] payload, ProtocolLimits limits)
            throws ProtocolViolationException {
        int maximumWireStringBytes = Math.min(
                limits.maximumQueryBytes(), MAXIMUM_MINECRAFT_UTF8_BYTES);
        return decodeQuery(
                payload,
                limits,
                maximumWireStringBytes,
                maximumWireStringBytes,
                DecodePolicy.LOBBY);
    }

    private static Registry decodeQuery(
            byte[] payload,
            ProtocolLimits limits,
            int maximumResourceLocationBytes,
            int maximumVersionBytes,
            DecodePolicy policy) throws ProtocolViolationException {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(limits, "limits");
        if (payload.length == 0) {
            throw new ProtocolViolationException("query payload is empty");
        }
        if (payload.length > limits.maximumQueryBytes()) {
            throw new ProtocolViolationException(
                    "query payload has " + payload.length + " bytes; configured maximum is "
                            + limits.maximumQueryBytes());
        }

        Reader reader = new Reader(payload);
        int protocolCount = reader.readBoundedCount(limits.maximumProtocols(), "protocol count");
        if (protocolCount == 0) {
            throw new ProtocolViolationException("client reported no protocol maps");
        }

        Map<Integer, List<Channel>> protocols = new LinkedHashMap<>();
        int totalChannels = 0;
        List<IgnoredChannel> ignoredChannels = new ArrayList<>();
        for (int protocolIndex = 0; protocolIndex < protocolCount; protocolIndex++) {
            int protocol = reader.readNonNegativeVarInt("protocol ordinal");
            if (protocol != PLAY_PROTOCOL && protocol != CONFIGURATION_PROTOCOL) {
                throw new ProtocolViolationException("unsupported connection protocol ordinal");
            }
            if (protocols.containsKey(protocol)) {
                throw new ProtocolViolationException("duplicate connection protocol");
            }

            int channelCount = reader.readBoundedCount(
                    limits.maximumChannelsPerProtocol(), "channel count");
            totalChannels = Math.addExact(totalChannels, channelCount);
            if (totalChannels > limits.maximumChannels()) {
                throw new ProtocolViolationException("total channel count exceeds configured bound");
            }

            List<Channel> channels = new ArrayList<>(channelCount);
            Set<String> ids = new HashSet<>(Math.min(channelCount, 4_096));
            for (int channelIndex = 0; channelIndex < channelCount; channelIndex++) {
                int componentOffset = reader.position();
                try {
                    boolean allowEmptyWireString = policy == DecodePolicy.LOBBY;
                    String id = reader.readUtf8(
                            maximumResourceLocationBytes,
                            "resource location",
                            allowEmptyWireString);
                    String version = reader.readUtf8(
                            maximumVersionBytes,
                            "channel version",
                            allowEmptyWireString);
                    boolean hasFlow = reader.readStrictBoolean("packet flow presence");
                    Flow flow = hasFlow
                            ? Flow.fromWireOrdinal(
                                    reader.readNonNegativeVarInt("packet flow ordinal"))
                            : Flow.BIDIRECTIONAL;
                    boolean optional = reader.readStrictBoolean("optional flag");

                    if (policy == DecodePolicy.FORENSIC) {
                        validateResourceLocation(id, maximumResourceLocationBytes);
                        validateVersion(version, maximumVersionBytes);
                    } else {
                        boolean validId = isMinecraftResourceLocation(
                                id, maximumResourceLocationBytes);
                        boolean validVersion = isCanonicalVersion(
                                version, maximumVersionBytes);
                        if (!validId || !validVersion) {
                            IgnoredChannelReason reason;
                            if (!validId && !validVersion) {
                                reason = IgnoredChannelReason
                                        .INVALID_RESOURCE_LOCATION_AND_VERSION;
                            } else if (!validId) {
                                reason = IgnoredChannelReason.INVALID_RESOURCE_LOCATION;
                            } else {
                                reason = IgnoredChannelReason.INVALID_VERSION;
                            }
                            ignoredChannels.add(new IgnoredChannel(
                                    protocol, id, version, flow, optional, reason));
                            continue;
                        }
                    }
                    if (!ids.add(id)) {
                        if (policy == DecodePolicy.FORENSIC) {
                            throw new ProtocolViolationException(
                                    "duplicate channel id in protocol map");
                        }
                        ignoredChannels.add(new IgnoredChannel(
                                protocol,
                                id,
                                version,
                                flow,
                                optional,
                                IgnoredChannelReason.DUPLICATE_CHANNEL_ID));
                        continue;
                    }
                    channels.add(new Channel(id, version, flow, optional));
                } catch (ProtocolViolationException exception) {
                    throw new ProtocolViolationException(
                            "protocol " + protocol + " channel " + channelIndex
                                    + " at byte " + componentOffset + ": "
                                    + exception.getMessage());
                }
            }
            protocols.put(protocol, List.copyOf(channels));
        }

        reader.requireFullyConsumed();
        if (totalChannels == 0 && policy == DecodePolicy.FORENSIC) {
            throw new ProtocolViolationException("client reported an empty NeoForge registry");
        }
        return new Registry(
                protocols, totalChannels, ignoredChannels.size(), ignoredChannels);
    }

    public static byte[] encodeSetup(Registry registry, ProtocolLimits limits)
            throws ProtocolViolationException {
        return encodeSetup(
                registry,
                limits,
                limits.maximumResourceLocationBytes(),
                limits.maximumVersionBytes());
    }

    /** Encodes a setup selected from a real client registry using Minecraft's wire envelope. */
    public static byte[] encodeLobbySetup(Registry registry, ProtocolLimits limits)
            throws ProtocolViolationException {
        int maximumWireStringBytes = Math.min(
                limits.maximumSetupBytes(), MAXIMUM_MINECRAFT_UTF8_BYTES);
        return encodeSetup(registry, limits, maximumWireStringBytes, maximumWireStringBytes);
    }

    private static byte[] encodeSetup(
            Registry registry,
            ProtocolLimits limits,
            int maximumResourceLocationBytes,
            int maximumVersionBytes) throws ProtocolViolationException {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(limits, "limits");
        Writer writer = new Writer(limits.maximumSetupBytes());
        List<Integer> protocols = registry.protocols().keySet().stream().sorted().toList();
        writer.writeVarInt(protocols.size());

        for (int protocol : protocols) {
            if (protocol != PLAY_PROTOCOL && protocol != CONFIGURATION_PROTOCOL) {
                throw new ProtocolViolationException("cannot encode unsupported protocol ordinal");
            }
            List<Channel> channels = registry.channelsFor(protocol).stream()
                    .sorted(CHANNEL_ORDER)
                    .toList();
            writer.writeVarInt(protocol);
            writer.writeVarInt(channels.size());
            for (Channel channel : channels) {
                validateResourceLocation(channel.id(), maximumResourceLocationBytes);
                validateVersion(channel.version(), maximumVersionBytes);
                // NetworkPayloadSetup is Map<ResourceLocation, NetworkChannel>; the id occurs twice.
                writer.writeUtf8(channel.id());
                writer.writeUtf8(channel.id());
                writer.writeUtf8(channel.version());
            }
        }
        return writer.toByteArray();
    }

    /** Encodes NeoForge's ConfigFilePayload without creating or touching a local file. */
    public static byte[] encodeConfigFilePayload(
            String fileName, byte[] contents, int maximumBytes)
            throws ProtocolViolationException {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contents, "contents");
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        if (!NeoForgeConfigPath.isValid(fileName)) {
            throw new ProtocolViolationException("invalid transient config filename");
        }

        Writer writer = new Writer(maximumBytes);
        writer.writeUtf8(fileName);
        writer.writeVarInt(contents.length);
        writer.writeRaw(contents);
        return writer.toByteArray();
    }

    public static byte[] encodeDinnerboneChannels(Collection<String> channels, int maximumBytes)
            throws ProtocolViolationException {
        Objects.requireNonNull(channels, "channels");
        Writer writer = new Writer(maximumBytes);
        try {
            channels.stream().distinct().sorted().forEach(channel -> {
                try {
                    validateResourceLocation(channel, 256);
                    byte[] encoded = channel.getBytes(StandardCharsets.US_ASCII);
                    writer.writeRaw(encoded);
                    writer.writeRawByte(0);
                } catch (ProtocolViolationException exception) {
                    throw new DeferredProtocolViolation(exception);
                }
            });
            return writer.toByteArray();
        } catch (DeferredProtocolViolation deferred) {
            throw deferred.protocolCause;
        }
    }

    public static Set<String> decodeDinnerboneChannels(
            byte[] payload, int maximumChannels, int maximumBytes)
            throws ProtocolViolationException {
        Objects.requireNonNull(payload, "payload");
        if (maximumChannels <= 0 || maximumBytes <= 0) {
            throw new IllegalArgumentException("Dinnerbone bounds must be positive");
        }
        if (payload.length > maximumBytes) {
            throw new ProtocolViolationException("Dinnerbone channel payload exceeds byte bound");
        }
        Set<String> channels = new HashSet<>();
        int start = 0;
        for (int index = 0; index <= payload.length; index++) {
            if (index != payload.length && payload[index] != 0) {
                int unsigned = payload[index] & 0xFF;
                if (unsigned > 0x7F) {
                    throw new ProtocolViolationException("non-ASCII Dinnerbone channel list");
                }
                continue;
            }
            if (index > start) {
                String channel = new String(payload, start, index - start, StandardCharsets.US_ASCII);
                validateResourceLocation(channel, 256);
                channels.add(channel);
                if (channels.size() > maximumChannels) {
                    throw new ProtocolViolationException("Dinnerbone channel count exceeds bound");
                }
            }
            start = index + 1;
        }
        return Set.copyOf(channels);
    }

    public static Set<String> decodeCommonRegister(byte[] payload, ProtocolLimits limits)
            throws ProtocolViolationException {
        Objects.requireNonNull(payload, "payload");
        Reader reader = new Reader(payload);
        int version = reader.readNonNegativeVarInt("common networking version");
        if (version != 1) {
            throw new ProtocolViolationException("unsupported common networking version");
        }
        String protocol = reader.readUtf8(32, "common register protocol");
        // NeoForge 21.1.221 and 21.1.247 only send/expect c:register for PLAY.
        if (!protocol.equals("play")) {
            throw new ProtocolViolationException("unsupported common register protocol");
        }
        int count = reader.readBoundedCount(limits.maximumChannels(), "common channel count");
        Set<String> channels = new HashSet<>();
        for (int index = 0; index < count; index++) {
            String channel = reader.readResourceLocation(limits.maximumResourceLocationBytes());
            if (!channels.add(channel)) {
                throw new ProtocolViolationException("duplicate common channel id");
            }
        }
        reader.requireFullyConsumed();
        return Set.copyOf(channels);
    }

    /**
     * Rejects late channel advertisements that were not authenticated by the initial query.
     * NeoForge derives both minecraft:register and c:register from PAYLOAD_REGISTRATIONS, the
     * same registry serialized by ModdedNetworkQueryPayload.fromRegistry.
     */
    public static void requireInitiallyDeclaredChannels(
            Collection<String> advertised,
            Registry initialRegistry,
            Set<String> builtins) throws ProtocolViolationException {
        Objects.requireNonNull(advertised, "advertised");
        Objects.requireNonNull(initialRegistry, "initialRegistry");
        Objects.requireNonNull(builtins, "builtins");
        Set<String> allowed = new HashSet<>(initialRegistry.channelIds());
        allowed.addAll(builtins);
        if (!allowed.containsAll(advertised)) {
            throw new ProtocolViolationException(
                    "late channel was not declared in the initial NeoForge registry");
        }
    }

    public static String describe(Registry registry) {
        return String.format(Locale.ROOT, "play=%d, configuration=%d, total=%d",
                registry.channelsFor(PLAY_PROTOCOL).size(),
                registry.channelsFor(CONFIGURATION_PROTOCOL).size(),
                registry.channelCount());
    }

    private static void validateResourceLocation(String value, int maximumBytes)
            throws ProtocolViolationException {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0 || bytes > maximumBytes || !RESOURCE_LOCATION.matcher(value).matches()) {
            throw new ProtocolViolationException("invalid resource location");
        }
    }

    /** Whether an id can be registered through Velocity's Minecraft channel API. */
    public static boolean isBridgeChannelIdentifier(String value) {
        Objects.requireNonNull(value, "value");
        return RESOURCE_LOCATION.matcher(value).matches();
    }

    private static boolean isMinecraftResourceLocation(String value, int maximumBytes) {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0 || bytes > maximumBytes) {
            return false;
        }
        int separator = value.indexOf(':');
        String namespace = separator < 0 ? "minecraft" : value.substring(0, separator);
        String path = separator < 0 ? value : value.substring(separator + 1);
        return !namespace.isEmpty()
                && !path.isEmpty()
                && namespace.chars().allMatch(NeoForgeHandshakeCodec::isNamespaceCharacter)
                && path.chars().allMatch(NeoForgeHandshakeCodec::isPathCharacter);
    }

    private static boolean isNamespaceCharacter(int value) {
        return value == '_'
                || value == '-'
                || value == '.'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9';
    }

    private static boolean isPathCharacter(int value) {
        return isNamespaceCharacter(value) || value == '/';
    }

    private static boolean isCanonicalVersion(String value, int maximumBytes) {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        return bytes > 0
                && bytes <= maximumBytes
                && !value.isBlank()
                && value.equals(value.strip());
    }

    private static void validateVersion(String value, int maximumBytes)
            throws ProtocolViolationException {
        if (!isCanonicalVersion(value, maximumBytes)) {
            throw new ProtocolViolationException("invalid channel version");
        }
    }

    private static final class Reader {
        private final byte[] data;
        private int index;

        private Reader(byte[] data) {
            this.data = data;
        }

        private int readBoundedCount(int maximum, String field) throws ProtocolViolationException {
            int value = readNonNegativeVarInt(field);
            if (value > maximum) {
                throw new ProtocolViolationException(
                        field + " is " + value + "; configured maximum is " + maximum);
            }
            return value;
        }

        private int readNonNegativeVarInt(String field) throws ProtocolViolationException {
            int value = 0;
            int bytes = 0;
            int current;
            do {
                if (bytes == 5 || index >= data.length) {
                    throw new ProtocolViolationException("truncated or oversized " + field);
                }
                current = data[index++] & 0xFF;
                if (bytes == 4 && (current & 0xF0) != 0) {
                    throw new ProtocolViolationException("overflow in " + field);
                }
                value |= (current & 0x7F) << (bytes * 7);
                bytes++;
            } while ((current & 0x80) != 0);

            if (value < 0 || varIntSize(value) != bytes) {
                throw new ProtocolViolationException("negative or non-canonical " + field);
            }
            return value;
        }

        private boolean readStrictBoolean(String field) throws ProtocolViolationException {
            if (index >= data.length) {
                throw new ProtocolViolationException("truncated " + field);
            }
            int value = data[index++] & 0xFF;
            if (value != 0 && value != 1) {
                throw new ProtocolViolationException("non-canonical " + field);
            }
            return value == 1;
        }

        private String readResourceLocation(int maximumBytes)
                throws ProtocolViolationException {
            String value = readUtf8(maximumBytes, "resource location");
            validateResourceLocation(value, maximumBytes);
            return value;
        }

        private int position() {
            return index;
        }

        private String readUtf8(int maximumBytes, String field) throws ProtocolViolationException {
            return readUtf8(maximumBytes, field, false);
        }

        private String readUtf8(
                int maximumBytes, String field, boolean allowEmpty)
                throws ProtocolViolationException {
            int length = readBoundedCount(maximumBytes, field + " byte length");
            if ((!allowEmpty && length == 0) || index > data.length - length) {
                throw new ProtocolViolationException("empty or truncated " + field);
            }
            try {
                CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data, index, length));
                index += length;
                String value = decoded.toString();
                if (value.length() > MAXIMUM_MINECRAFT_STRING_CHARACTERS) {
                    throw new ProtocolViolationException(field + " exceeds Minecraft char bound");
                }
                if (value.indexOf('\0') >= 0) {
                    throw new ProtocolViolationException("NUL in " + field);
                }
                return value;
            } catch (CharacterCodingException exception) {
                throw new ProtocolViolationException("malformed UTF-8 in " + field);
            }
        }

        private void requireFullyConsumed() throws ProtocolViolationException {
            if (index != data.length) {
                throw new ProtocolViolationException("trailing bytes after payload");
            }
        }
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int maximumBytes;

        private Writer(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        private void writeVarInt(int value) throws ProtocolViolationException {
            if (value < 0) {
                throw new ProtocolViolationException("cannot encode a negative VarInt");
            }
            int remaining = value;
            do {
                int next = remaining & 0x7F;
                remaining >>>= 7;
                if (remaining != 0) {
                    next |= 0x80;
                }
                writeRawByte(next);
            } while (remaining != 0);
        }

        private void writeUtf8(String value) throws ProtocolViolationException {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            writeVarInt(encoded.length);
            writeRaw(encoded);
        }

        private void writeRaw(byte[] value) throws ProtocolViolationException {
            if (output.size() > maximumBytes - value.length) {
                throw new ProtocolViolationException("encoded payload exceeds configured bound");
            }
            output.writeBytes(value);
        }

        private void writeRawByte(int value) throws ProtocolViolationException {
            if (output.size() >= maximumBytes) {
                throw new ProtocolViolationException("encoded payload exceeds configured bound");
            }
            output.write(value);
        }

        private byte[] toByteArray() {
            return output.toByteArray();
        }

    }

    private static int varIntSize(int value) {
        if ((value & 0xFFFFFF80) == 0) {
            return 1;
        }
        if ((value & 0xFFFFC000) == 0) {
            return 2;
        }
        if ((value & 0xFFE00000) == 0) {
            return 3;
        }
        if ((value & 0xF0000000) == 0) {
            return 4;
        }
        return 5;
    }

    private static final class DeferredProtocolViolation extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final ProtocolViolationException protocolCause;

        private DeferredProtocolViolation(ProtocolViolationException cause) {
            super(cause);
            this.protocolCause = cause;
        }
    }
}

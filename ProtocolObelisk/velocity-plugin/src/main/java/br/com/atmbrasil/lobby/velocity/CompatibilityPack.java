package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeConfigPath;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolLimits;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One captured, immutable compatibility pack ({@code .obpack}).
 *
 * <p>A pack is produced by the build-only capture mod booted on a modpack's official ServerFiles.
 * It holds exactly what that real NeoForge server would hand a client during CONFIGURATION: its
 * channel registrations, every SERVER config, every synchronized dynamic registry, their tags and
 * the vanilla-to-runtime BlockState projection. Updating a modpack therefore means capturing a new
 * pack, not changing plugin source.</p>
 *
 * <p>Packs never decide admission or routing. They are selected by NeoForge's own negotiation
 * rules (see {@link CompatibilityPackSelector}); a missing, corrupt or non-matching pack only
 * withholds enrichment. Every entry is authenticated by the pack's SHA-256 manifest and every
 * payload is structurally decoded and bounded before the pack becomes usable.</p>
 */
final class CompatibilityPack {
    static final int SUPPORTED_FORMAT = 1;
    static final int SUPPORTED_MINECRAFT_PROTOCOL = 767;
    static final String CATALOG_ID_PREFIX = "pack-";
    static final String SHIM_ID_PREFIX = "pk-";
    static final String EXTENSION_SHIM_ID_PREFIX = "pkx-";
    static final String ENCHANTMENT_REGISTRY_ID = "minecraft:enchantment";

    static final long MAXIMUM_PACK_FILE_BYTES = 128L * 1024 * 1024;
    private static final int MAXIMUM_ENTRIES = 8_192;
    private static final int MAXIMUM_ENTRY_BYTES = 32 * 1024 * 1024;
    private static final long MAXIMUM_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final int MAXIMUM_SERVER_CONFIGS = 1_024;
    private static final int MAXIMUM_REGISTRIES = 1_024;
    private static final int MAXIMUM_REGISTRY_PACKET_BYTES = 1_048_576;
    private static final int MAXIMUM_TAGS_PER_REGISTRY = 65_535;
    private static final int MAXIMUM_TAG_MEMBERS = 65_535;
    private static final Pattern PACK_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern ENTRY_NAME = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9_.-]*(?:/[A-Za-z0-9][A-Za-z0-9_.-]*)*");
    private static final Pattern RESOURCE_LOCATION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String MANIFEST = "manifest.sha256";
    /** Captures never contain more than 1 MiB of query; lobby queries share the same bound. */
    private static final ProtocolLimits NETWORK_LIMITS =
            new ProtocolLimits(1_048_576, 1_048_576, 2, 16_384, 16_384, 1_024, 1_024);

    private final String packId;
    private final String displayName;
    private final String fileName;
    private final String fileSha256;
    private final String neoForgeVersion;
    private final int modCount;
    private final Registry serverChannels;
    private final List<ServerConfig> serverConfigs;
    private final String serverConfigNameSequenceSha256;
    private final String serverConfigPayloadSequenceSha256;
    private final int serverConfigEncodedBytes;
    private final List<RegistryShimPacket> tailRegistries;
    private final List<RegistryShimPacket> paperRegistryExtensions;
    private final Optional<RegistryShimPacket> enchantmentRegistry;
    private final Optional<RegistryShimPacket> enchantmentExtension;
    private final List<String> quarantinedRegistries;
    private final Map<String, Map<String, int[]>> dynamicRegistryTags;
    private final Optional<BlockStateTranslationProfile> blockStates;
    private final List<String> extraVanillaBlockProperties;

    private CompatibilityPack(Builder builder) {
        this.packId = builder.packId;
        this.displayName = builder.displayName;
        this.fileName = builder.fileName;
        this.fileSha256 = builder.fileSha256;
        this.neoForgeVersion = builder.neoForgeVersion;
        this.modCount = builder.modCount;
        this.serverChannels = builder.serverChannels;
        this.serverConfigs = List.copyOf(builder.serverConfigs);
        this.serverConfigNameSequenceSha256 = builder.serverConfigNameSequenceSha256;
        this.serverConfigPayloadSequenceSha256 = builder.serverConfigPayloadSequenceSha256;
        this.serverConfigEncodedBytes = builder.serverConfigEncodedBytes;
        this.tailRegistries = List.copyOf(builder.tailRegistries);
        this.paperRegistryExtensions = List.copyOf(builder.paperRegistryExtensions);
        this.enchantmentRegistry = builder.enchantmentRegistry;
        this.enchantmentExtension = builder.enchantmentExtension;
        this.quarantinedRegistries = List.copyOf(builder.quarantinedRegistries);
        LinkedHashMap<String, Map<String, int[]>> tags = new LinkedHashMap<>();
        builder.dynamicRegistryTags.forEach((registry, byTag) ->
                tags.put(registry, Collections.unmodifiableMap(new LinkedHashMap<>(byTag))));
        this.dynamicRegistryTags = Collections.unmodifiableMap(tags);
        this.blockStates = builder.blockStates;
        this.extraVanillaBlockProperties = List.copyOf(builder.extraVanillaBlockProperties);
    }

    /** True for the shim ids this class assigns to whole pack registry packets. */
    static boolean isPackShimId(String shimId) {
        return shimId != null && shimId.startsWith(SHIM_ID_PREFIX);
    }

    /** True for the shim ids of packets that only extend a registry Paper sends. */
    static boolean isPackExtensionShimId(String shimId) {
        return shimId != null && shimId.startsWith(EXTENSION_SHIM_ID_PREFIX);
    }

    static CompatibilityPack load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        long size = Files.size(file);
        if (size < 1 || size > MAXIMUM_PACK_FILE_BYTES) {
            throw new IllegalArgumentException("pack file size is outside bounds: " + size);
        }
        byte[] bytes = Files.readAllBytes(file);
        return load(file.getFileName().toString(), bytes);
    }

    static CompatibilityPack load(String fileName, byte[] packBytes) throws IOException {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(packBytes, "packBytes");
        if (packBytes.length < 1 || packBytes.length > MAXIMUM_PACK_FILE_BYTES) {
            throw new IllegalArgumentException("pack size is outside bounds");
        }
        Map<String, byte[]> entries = readEntries(packBytes);
        verifyManifest(entries);

        Builder builder = new Builder();
        builder.fileName = fileName;
        builder.fileSha256 = sha256(packBytes);
        Properties meta = properties(entries, "pack.properties");
        requireEquals(meta, "obelisk-pack-format", Integer.toString(SUPPORTED_FORMAT));
        builder.packId = require(meta, "pack-id");
        if (!PACK_ID.matcher(builder.packId).matches()) {
            throw new IllegalArgumentException("pack id is not canonical");
        }
        builder.displayName = meta.getProperty("display-name", builder.packId).strip();
        requireEquals(meta, "minecraft", "1.21.1");
        requireEquals(meta, "minecraft-protocol", Integer.toString(SUPPORTED_MINECRAFT_PROTOCOL));
        requireEquals(meta, "runtime-kind", "pure-neoforge");
        builder.neoForgeVersion = require(meta, "neoforge");
        builder.modCount = nonNegativeInt(meta, "mods.count");

        loadNetwork(entries, meta, builder);
        loadServerConfigs(entries, meta, builder);
        loadRegistries(entries, builder);
        loadTags(entries, builder);
        loadBlockStates(entries, meta, builder);
        return new CompatibilityPack(builder);
    }

    private static Map<String, byte[]> readEntries(byte[] packBytes) throws IOException {
        TreeMap<String, byte[]> entries = new TreeMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(packBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!ENTRY_NAME.matcher(name).matches() || name.contains("..")) {
                    throw new IllegalArgumentException("pack contains an unsafe entry name: " + name);
                }
                if (entries.size() >= MAXIMUM_ENTRIES) {
                    throw new IllegalArgumentException("pack contains too many entries");
                }
                byte[] data = readBounded(zip, MAXIMUM_ENTRY_BYTES);
                total += data.length;
                if (total > MAXIMUM_TOTAL_BYTES) {
                    throw new IllegalArgumentException("pack expands beyond the total byte bound");
                }
                if (entries.put(name, data) != null) {
                    throw new IllegalArgumentException("pack contains a duplicate entry: " + name);
                }
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("pack is empty or not a ZIP archive");
        }
        return entries;
    }

    private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        byte[] data = input.readNBytes(maximumBytes + 1);
        if (data.length > maximumBytes) {
            throw new IllegalArgumentException("pack entry exceeds the byte bound");
        }
        return data;
    }

    private static void verifyManifest(Map<String, byte[]> entries) {
        byte[] manifest = entries.get(MANIFEST);
        if (manifest == null) {
            throw new IllegalArgumentException("pack has no manifest.sha256");
        }
        LinkedHashMap<String, String> listed = new LinkedHashMap<>();
        for (String line : utf8(manifest, MANIFEST).split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf("  ");
            if (separator != 64 || !SHA256.matcher(line.substring(0, 64)).matches()) {
                throw new IllegalArgumentException("pack manifest line is not canonical");
            }
            if (listed.put(line.substring(66), line.substring(0, 64)) != null) {
                throw new IllegalArgumentException("pack manifest lists an entry twice");
            }
        }
        Set<String> actual = new HashSet<>(entries.keySet());
        actual.remove(MANIFEST);
        if (!listed.keySet().equals(actual)) {
            throw new IllegalArgumentException("pack manifest does not list exactly the pack entries");
        }
        for (Map.Entry<String, String> item : listed.entrySet()) {
            if (!sha256(entries.get(item.getKey())).equals(item.getValue())) {
                throw new IllegalArgumentException("pack entry hash mismatch: " + item.getKey());
            }
        }
    }

    private static void loadNetwork(
            Map<String, byte[]> entries, Properties meta, Builder builder) {
        byte[] query = entry(entries, "network/server-query.bin");
        requireEquals(meta, "network.sha256", sha256(query));
        try {
            builder.serverChannels = NeoForgeHandshakeCodec.decodeLobbyQuery(query, NETWORK_LIMITS);
        } catch (ProtocolViolationException exception) {
            throw new IllegalArgumentException(
                    "pack server channel registry is malformed: " + exception.getMessage(), exception);
        }
        // Real servers can register an identifier the lobby decoder cannot use (ATM10 ships
        // "ae2:"). The client advertises the same registration and the decoder skips it on both
        // sides identically, so it never takes part in negotiation.
        if (builder.serverChannels.declaredChannelCount()
                != nonNegativeInt(meta, "network.channel-count")) {
            throw new IllegalArgumentException("pack server channel count differs from its metadata");
        }
    }

    private static void loadServerConfigs(
            Map<String, byte[]> entries, Properties meta, Builder builder) {
        Properties configs = properties(entries, "server-configs.properties");
        int count = nonNegativeInt(configs, "config.count");
        if (count < 1 || count > MAXIMUM_SERVER_CONFIGS) {
            throw new IllegalArgumentException("pack SERVER-config count is outside bounds");
        }
        MessageDigest names = newSha256();
        MessageDigest payloads = newSha256();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        int encodedBytes = 0;
        for (int index = 0; index < count; index++) {
            String key = "config." + index;
            String name = require(configs, key + ".name");
            if (!NeoForgeConfigPath.isValid(name) || !unique.add(name)) {
                throw new IllegalArgumentException("pack SERVER-config name is invalid or duplicated");
            }
            byte[] encoded = entry(entries, require(configs, key + ".file"));
            requireEquals(configs, key + ".encoded-sha256", sha256(encoded));
            DecodedConfig decoded = decodeConfigPayload(encoded);
            if (!decoded.name().equals(name)) {
                throw new IllegalArgumentException("pack SERVER-config payload names a different file");
            }
            requireEquals(configs, key + ".content-sha256", sha256(decoded.contents()));
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            updateInt(names, nameBytes.length);
            names.update(nameBytes);
            payloads.update(encoded);
            encodedBytes = Math.addExact(encodedBytes, encoded.length);
            builder.serverConfigs.add(new ServerConfig(name, encoded));
        }
        builder.serverConfigNameSequenceSha256 = HexFormat.of().formatHex(names.digest());
        builder.serverConfigPayloadSequenceSha256 = HexFormat.of().formatHex(payloads.digest());
        builder.serverConfigEncodedBytes = encodedBytes;
        requireEquals(configs, "config.name-sequence-sha256", builder.serverConfigNameSequenceSha256);
        requireEquals(configs, "config.payload-sequence-sha256",
                builder.serverConfigPayloadSequenceSha256);
        requireEquals(meta, "server-configs.payload-sequence-sha256",
                builder.serverConfigPayloadSequenceSha256);
    }

    /**
     * Splits the captured registries into what a vanilla Paper lobby cannot provide.
     *
     * <ul>
     *   <li>A registry Paper never sends is appended whole, in the server's entry order, as a
     *   tail after Paper's registries.</li>
     *   <li>For a registry Paper does send, only the entries vanilla 1.21.1 lacks are kept, as an
     *   extension the client appends after Paper's own entries. Paper's numeric ids for biomes,
     *   dimension types, damage types and paintings therefore stay exactly what the lobby world
     *   uses, while every modded entry other registries name (Eternal Starlight's biome data
     *   names its biomes, for example) still exists. A vanilla id a modpack overrides keeps
     *   Paper's definition.</li>
     *   <li>{@code minecraft:enchantment} is also kept whole as a replacement candidate, because
     *   modpacks change vanilla enchantments and tag them by the server's own ids.</li>
     * </ul>
     *
     * <p>Delivery must stay closed under references: the client fails its whole registry load
     * when a delivered entry names one it never received. Without quarantine this split is closed
     * by construction, since every non-vanilla entry is delivered. After any quarantine, packets
     * whose NBT still names a withheld entry are withheld too, until nothing changes.</p>
     */
    private static void loadRegistries(Map<String, byte[]> entries, Builder builder) {
        String root = "registries/wire-known-pack";
        Properties manifest = properties(entries, root + ".properties");
        requireEquals(manifest, "known-pack.count", "1");
        requireEquals(manifest, "known-pack.0", "minecraft:core:1.21.1");
        int count = nonNegativeInt(manifest, "packet.count");
        if (count < 1 || count > MAXIMUM_REGISTRIES) {
            throw new IllegalArgumentException("pack registry count is outside bounds");
        }
        List<Delivery> deliveries = new ArrayList<>();
        Set<EntryKey> quarantinedEntries = new HashSet<>();
        boolean quarantinedEntriesUnknown = false;
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < count; index++) {
            String key = "packet." + index;
            String registryId = require(manifest, key + ".registry");
            if (!RESOURCE_LOCATION.matcher(registryId).matches() || !seen.add(registryId)) {
                throw new IllegalArgumentException("pack registry id is invalid or duplicated");
            }
            byte[] body = entry(entries, root + "/" + require(manifest, key + ".file"));
            requireEquals(manifest, key + ".sha256", sha256(body));
            int entryCount = nonNegativeInt(manifest, key + ".entries");
            if (entryCount == 0) {
                continue;
            }
            boolean paperOwned = Minecraft1211VanillaRegistries.isSynchronized(registryId);
            MinecraftRegistryPacketCodec.Inspection inspection = null;
            try {
                if (body.length > MAXIMUM_REGISTRY_PACKET_BYTES) {
                    throw new ProtocolViolationException("registry packet exceeds 1 MiB");
                }
                inspection = MinecraftRegistryPacketCodec.inspect(body, MAXIMUM_REGISTRY_PACKET_BYTES);
                if (!inspection.registryId().equals(registryId)
                        || inspection.entryIds().size() != entryCount) {
                    throw new ProtocolViolationException("registry packet differs from its manifest");
                }
                deliveries.addAll(paperOwned
                        ? paperOwnedDeliveries(registryId, body, inspection)
                        : List.of(tailDelivery(registryId, body, inspection)));
            } catch (ProtocolViolationException | IllegalArgumentException failure) {
                // One unusual registry must not discard the rest of a valid capture.
                builder.quarantinedRegistries.add(registryId + " (" + failure.getMessage() + ")");
                if (inspection == null || !inspection.registryId().equals(registryId)) {
                    quarantinedEntriesUnknown = true;
                } else {
                    for (String entryId : inspection.entryIds()) {
                        if (!Minecraft1211VanillaRegistries.isVanillaEntry(registryId, entryId)) {
                            quarantinedEntries.add(new EntryKey(registryId, entryId));
                        }
                    }
                }
            }
        }

        List<Delivery> delivered = closeUnderReferences(
                deliveries, quarantinedEntries, quarantinedEntriesUnknown,
                builder.quarantinedRegistries);
        for (Delivery delivery : delivered) {
            RegistryShimPacket packet = delivery.packet();
            switch (delivery.kind()) {
                case TAIL -> {
                    builder.tailRegistries.add(packet);
                    builder.registryEntryCounts.put(packet.registryId(), packet.entryCount());
                }
                case PAPER_EXTENSION -> {
                    if (packet.registryId().equals(ENCHANTMENT_REGISTRY_ID)) {
                        builder.enchantmentExtension = Optional.of(packet);
                    } else {
                        builder.paperRegistryExtensions.add(packet);
                    }
                }
                case ENCHANTMENT_REPLACEMENT -> {
                    builder.enchantmentRegistry = Optional.of(packet);
                    builder.registryEntryCounts.put(packet.registryId(), packet.entryCount());
                }
            }
        }
    }

    private static Delivery tailDelivery(
            String registryId,
            byte[] body,
            MinecraftRegistryPacketCodec.Inspection inspection) throws ProtocolViolationException {
        if (inspection.entriesWithData() != inspection.entryIds().size()) {
            throw new ProtocolViolationException("modded registry relies on known-pack placeholders");
        }
        return new Delivery(
                Delivery.Kind.TAIL,
                packet(SHIM_ID_PREFIX, registryId, body, inspection.entryIds().size()),
                EntryKey.all(registryId, inspection.entryIds()));
    }

    private static List<Delivery> paperOwnedDeliveries(
            String registryId,
            byte[] body,
            MinecraftRegistryPacketCodec.Inspection inspection) throws ProtocolViolationException {
        Set<String> added = new LinkedHashSet<>();
        for (String entryId : inspection.entryIds()) {
            if (Minecraft1211VanillaRegistries.isVanillaEntry(registryId, entryId)) {
                continue;
            }
            // The client resolves placeholders only from the vanilla pack it negotiated with Paper.
            if (!inspection.entryIdsWithData().contains(entryId)) {
                throw new ProtocolViolationException(
                        "non-vanilla entry " + entryId + " relies on a known-pack placeholder");
            }
            added.add(entryId);
        }
        List<Delivery> result = new ArrayList<>(2);
        Optional<byte[]> extension = MinecraftRegistryPacketCodec.selectEntries(
                body, added::contains, MAXIMUM_REGISTRY_PACKET_BYTES);
        if (extension.isPresent()) {
            result.add(new Delivery(
                    Delivery.Kind.PAPER_EXTENSION,
                    packet(EXTENSION_SHIM_ID_PREFIX, registryId, extension.orElseThrow(), added.size()),
                    EntryKey.all(registryId, added)));
        }
        if (registryId.equals(ENCHANTMENT_REGISTRY_ID)) {
            result.add(new Delivery(
                    Delivery.Kind.ENCHANTMENT_REPLACEMENT,
                    packet(SHIM_ID_PREFIX, registryId, body, inspection.entryIds().size()),
                    EntryKey.all(registryId, added)));
        }
        return result;
    }

    /**
     * Withholds every delivery that still references an entry the client would not receive.
     *
     * <p>An entry is missing when its registry was quarantined, or when only withheld deliveries
     * carried it. Entries are tracked per registry: the same identifier is often an entry of
     * several registries (Eternal Starlight keys its biome data by biome id), so one registry
     * providing it never satisfies a reference to another. A reference is an NBT identifier string
     * (or compound key) without its registry, so it counts as dangling when any registry is missing
     * that identifier. This can only over-withhold, never miss a reference.</p>
     */
    private static List<Delivery> closeUnderReferences(
            List<Delivery> deliveries,
            Set<EntryKey> quarantinedEntries,
            boolean quarantinedEntriesUnknown,
            List<String> report) {
        if (quarantinedEntries.isEmpty() && !quarantinedEntriesUnknown) {
            return deliveries;
        }
        if (quarantinedEntriesUnknown) {
            for (Delivery delivery : deliveries) {
                report.add(delivery.label()
                        + " (withheld: a quarantined registry could not be enumerated)");
            }
            return List.of();
        }
        Map<Delivery, Set<String>> references = new LinkedHashMap<>();
        for (Delivery delivery : deliveries) {
            try {
                references.put(delivery, references(delivery.packet().packetBody()));
            } catch (ProtocolViolationException exception) {
                // Already inspected successfully; treat an impossible failure as unreferenceable.
                references.put(delivery, Set.of(""));
            }
        }
        List<Delivery> kept = new ArrayList<>(deliveries);
        Set<EntryKey> withheldEntries = new HashSet<>(quarantinedEntries);
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<EntryKey> provided = new HashSet<>();
            kept.forEach(delivery -> provided.addAll(delivery.addedEntries()));
            Set<String> missing = new HashSet<>();
            for (EntryKey entry : withheldEntries) {
                if (!provided.contains(entry)) {
                    missing.add(entry.entryId());
                }
            }
            for (Delivery delivery : List.copyOf(kept)) {
                Set<String> referenced = references.get(delivery);
                String dangling = referenced.contains("")
                        ? "an unreadable reference"
                        : referenced.stream().filter(missing::contains).sorted().findFirst().orElse(null);
                if (dangling != null) {
                    kept.remove(delivery);
                    withheldEntries.addAll(delivery.addedEntries());
                    report.add(delivery.label() + " (withheld: references " + dangling + ")");
                    changed = true;
                }
            }
        }
        return kept;
    }

    private static Set<String> references(byte[] body) throws ProtocolViolationException {
        Set<String> references = new HashSet<>();
        for (String value : MinecraftRegistryPacketCodec.nbtStrings(body, MAXIMUM_REGISTRY_PACKET_BYTES)) {
            if (value.isEmpty() || value.charAt(0) == '#') {
                continue; // tag references bind to an empty set when absent
            }
            String id = value.indexOf(':') < 0 ? "minecraft:" + value : value;
            if (RESOURCE_LOCATION.matcher(id).matches()) {
                references.add(id);
            }
        }
        return references;
    }

    private static RegistryShimPacket packet(
            String shimPrefix, String registryId, byte[] body, int entryCount) {
        return new RegistryShimPacket(
                shimId(shimPrefix, registryId),
                namespace(registryId),
                registryId,
                entryCount,
                body,
                sha256(body));
    }

    /** One entry of one registry. */
    private record EntryKey(String registryId, String entryId) {
        static Set<EntryKey> all(String registryId, java.util.Collection<String> entryIds) {
            Set<EntryKey> keys = new HashSet<>();
            entryIds.forEach(entryId -> keys.add(new EntryKey(registryId, entryId)));
            return Set.copyOf(keys);
        }
    }

    /** One packet this pack may deliver, with the non-vanilla entries it provides. */
    private record Delivery(Kind kind, RegistryShimPacket packet, Set<EntryKey> addedEntries) {
        enum Kind {
            TAIL,
            PAPER_EXTENSION,
            ENCHANTMENT_REPLACEMENT
        }

        String label() {
            return switch (kind) {
                case TAIL -> packet.registryId();
                case PAPER_EXTENSION -> packet.registryId() + " extension";
                case ENCHANTMENT_REPLACEMENT -> packet.registryId() + " replacement";
            };
        }
    }

    /**
     * Retains tags only for registries this pack itself delivers, so every numeric member id
     * refers to the exact entry order of the packet ProtocolObelisk sends.
     */
    private static void loadTags(Map<String, byte[]> entries, Builder builder) {
        byte[] packet = entry(entries, "tags/full-update-tags.bin");
        Cursor cursor = new Cursor(packet);
        int registryCount = cursor.boundedVarInt(0, MAXIMUM_REGISTRIES, "tag registry count");
        for (int registryIndex = 0; registryIndex < registryCount; registryIndex++) {
            String registryId = cursor.resourceLocation();
            int tagCount = cursor.boundedVarInt(0, MAXIMUM_TAGS_PER_REGISTRY, "tag count");
            Integer entryCount = builder.registryEntryCounts.get(registryId);
            LinkedHashMap<String, int[]> tags = new LinkedHashMap<>();
            for (int tagIndex = 0; tagIndex < tagCount; tagIndex++) {
                String tagId = cursor.resourceLocation();
                int members = cursor.boundedVarInt(0, MAXIMUM_TAG_MEMBERS, "tag member count");
                int[] ids = new int[members];
                for (int member = 0; member < members; member++) {
                    ids[member] = cursor.boundedVarInt(0, Integer.MAX_VALUE - 1, "tag member id");
                    if (entryCount != null && ids[member] >= entryCount) {
                        throw new IllegalArgumentException(
                                "pack tag " + tagId + " references an entry outside " + registryId);
                    }
                }
                if (tags.put(tagId, ids) != null) {
                    throw new IllegalArgumentException("pack tags repeat " + tagId + " in " + registryId);
                }
            }
            if (entryCount != null) {
                builder.dynamicRegistryTags.put(registryId, tags);
            }
        }
        if (cursor.remaining() != 0) {
            throw new IllegalArgumentException("pack tags packet has trailing bytes");
        }
    }

    private static void loadBlockStates(
            Map<String, byte[]> entries, Properties meta, Builder builder) throws IOException {
        byte[] manifest = entry(entries, "block-states/block-state-map.properties");
        BlockStateTranslationProfile profile = BlockStateTranslationProfile.loadPack(
                builder.packId,
                manifest,
                mapFile -> entry(entries, "block-states/" + mapFile));
        requireEquals(meta, "block-states.map-sha256", profile.mapSha256());
        builder.blockStates = Optional.of(profile);
        byte[] extras = entries.get("block-states/extra-vanilla-properties.tsv");
        if (extras != null) {
            for (String line : utf8(extras, "extra-vanilla-properties.tsv").split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    String[] fields = line.split("\t");
                    if (fields.length >= 2) {
                        builder.extraVanillaBlockProperties.add(fields[0] + "#" + fields[1]);
                    }
                }
            }
        }
    }

    private static String shimId(String prefix, String registryId) {
        String safe = registryId.replace(':', '.').replace('/', '.');
        String digest = sha256((prefix + registryId).getBytes(StandardCharsets.UTF_8)).substring(0, 8);
        int room = 64 - prefix.length() - 1 - digest.length();
        return prefix + (safe.length() > room ? safe.substring(0, room) : safe) + "-" + digest;
    }

    private static String namespace(String registryId) {
        return registryId.substring(0, registryId.indexOf(':'));
    }

    private static DecodedConfig decodeConfigPayload(byte[] encoded) {
        Cursor cursor = new Cursor(encoded);
        int nameBytes = cursor.boundedVarInt(1, 32_767 * 3, "config name length");
        String name = utf8(cursor.take(nameBytes), "config name");
        int contentBytes = cursor.boundedVarInt(0, MAXIMUM_ENTRY_BYTES, "config content length");
        byte[] contents = cursor.take(contentBytes);
        if (cursor.remaining() != 0) {
            throw new IllegalArgumentException("pack SERVER-config payload has trailing bytes");
        }
        return new DecodedConfig(name, contents);
    }

    private static byte[] entry(Map<String, byte[]> entries, String name) {
        byte[] data = entries.get(name);
        if (data == null) {
            throw new IllegalArgumentException("pack is missing " + name);
        }
        return data;
    }

    private static Properties properties(Map<String, byte[]> entries, String name) {
        Properties properties = new Properties();
        try {
            properties.load(new java.io.StringReader(utf8(entry(entries, name), name)));
        } catch (IOException impossible) {
            throw new IllegalArgumentException("pack properties are unreadable: " + name, impossible);
        }
        return properties;
    }

    private static String utf8(byte[] bytes, String label) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(label + " is not valid UTF-8", exception);
        }
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("pack metadata is missing " + key);
        }
        return value.strip();
    }

    private static void requireEquals(Properties properties, String key, String expected) {
        if (!require(properties, key).equals(expected)) {
            throw new IllegalArgumentException("pack metadata " + key + " does not match its content");
        }
    }

    private static int nonNegativeInt(Properties properties, String key) {
        try {
            int value = Integer.parseInt(require(properties, key));
            if (value < 0) {
                throw new NumberFormatException("negative");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("pack metadata " + key + " is not a count", exception);
        }
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    String packId() {
        return packId;
    }

    String displayName() {
        return displayName;
    }

    String fileName() {
        return fileName;
    }

    String fileSha256() {
        return fileSha256;
    }

    String neoForgeVersion() {
        return neoForgeVersion;
    }

    int modCount() {
        return modCount;
    }

    int minecraftProtocol() {
        return SUPPORTED_MINECRAFT_PROTOCOL;
    }

    Registry serverChannels() {
        return serverChannels;
    }

    List<ServerConfig> serverConfigs() {
        return serverConfigs;
    }

    List<String> serverConfigNames() {
        return serverConfigs.stream().map(ServerConfig::name).toList();
    }

    String serverConfigNameSequenceSha256() {
        return serverConfigNameSequenceSha256;
    }

    String serverConfigPayloadSequenceSha256() {
        return serverConfigPayloadSequenceSha256;
    }

    int serverConfigEncodedBytes() {
        return serverConfigEncodedBytes;
    }

    String catalogId() {
        return CATALOG_ID_PREFIX + packId;
    }

    /** Modded registries in the server's own order: appended after Paper's vanilla registries. */
    List<RegistryShimPacket> tailRegistries() {
        return tailRegistries;
    }

    /**
     * Non-vanilla entries of the registries Paper sends (except enchantments), each appended by
     * the client after Paper's own entries for that registry.
     */
    List<RegistryShimPacket> paperRegistryExtensions() {
        return paperRegistryExtensions;
    }

    /** Complete captured enchantment registry, used to replace Paper's packet in place. */
    Optional<RegistryShimPacket> enchantmentRegistry() {
        return enchantmentRegistry;
    }

    /** Non-vanilla enchantments, appended instead when the replacement is not in effect. */
    Optional<RegistryShimPacket> enchantmentExtension() {
        return enchantmentExtension;
    }

    List<String> quarantinedRegistries() {
        return quarantinedRegistries;
    }

    /**
     * Tags for the registries this pack delivers, keyed by registry id. The map is the exact
     * shape Velocity's CONFIG tags packet consumes.
     */
    Map<String, Map<String, int[]>> tagsFor(Set<String> deliveredRegistryIds) {
        LinkedHashMap<String, Map<String, int[]>> selected = new LinkedHashMap<>();
        dynamicRegistryTags.forEach((registry, tags) -> {
            if (deliveredRegistryIds.contains(registry) && !tags.isEmpty()) {
                selected.put(registry, tags);
            }
        });
        return Collections.unmodifiableMap(selected);
    }

    Optional<BlockStateTranslationProfile> blockStates() {
        return blockStates;
    }

    List<String> extraVanillaBlockProperties() {
        return extraVanillaBlockProperties;
    }

    record ServerConfig(String name, byte[] encodedPayload) {
        ServerConfig {
            Objects.requireNonNull(name, "name");
            encodedPayload = Objects.requireNonNull(encodedPayload, "encodedPayload").clone();
        }

        @Override
        public byte[] encodedPayload() {
            return encodedPayload.clone();
        }
    }

    private record DecodedConfig(String name, byte[] contents) {
    }

    private static final class Builder {
        private String packId;
        private String displayName;
        private String fileName;
        private String fileSha256;
        private String neoForgeVersion;
        private int modCount;
        private Registry serverChannels;
        private final List<ServerConfig> serverConfigs = new ArrayList<>();
        private String serverConfigNameSequenceSha256;
        private String serverConfigPayloadSequenceSha256;
        private int serverConfigEncodedBytes;
        private final List<RegistryShimPacket> tailRegistries = new ArrayList<>();
        private final List<RegistryShimPacket> paperRegistryExtensions = new ArrayList<>();
        private Optional<RegistryShimPacket> enchantmentRegistry = Optional.empty();
        private Optional<RegistryShimPacket> enchantmentExtension = Optional.empty();
        private final List<String> quarantinedRegistries = new ArrayList<>();
        private final Map<String, Integer> registryEntryCounts = new LinkedHashMap<>();
        private final Map<String, Map<String, int[]>> dynamicRegistryTags = new LinkedHashMap<>();
        private Optional<BlockStateTranslationProfile> blockStates = Optional.empty();
        private final List<String> extraVanillaBlockProperties = new ArrayList<>();
    }

    /** Bounded reader for the few Minecraft wire structures a pack stores. */
    private static final class Cursor {
        private final byte[] data;
        private int position;

        private Cursor(byte[] data) {
            this.data = data;
        }

        private int remaining() {
            return data.length - position;
        }

        private byte[] take(int count) {
            if (count < 0 || count > remaining()) {
                throw new IllegalArgumentException("pack payload is truncated");
            }
            byte[] result = java.util.Arrays.copyOfRange(data, position, position + count);
            position += count;
            return result;
        }

        private int boundedVarInt(int minimum, int maximum, String label) {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                if (position >= data.length) {
                    throw new IllegalArgumentException("pack payload is truncated at " + label);
                }
                byte current = data[position++];
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    if (value < minimum || value > maximum) {
                        throw new IllegalArgumentException(label + " is outside bounds");
                    }
                    return value;
                }
            }
            throw new IllegalArgumentException(label + " VarInt is too long");
        }

        private String resourceLocation() {
            String value = utf8(take(boundedVarInt(1, 32_767, "resource location length")),
                    "resource location");
            if (!RESOURCE_LOCATION.matcher(value).matches()) {
                throw new IllegalArgumentException("pack contains an invalid resource location");
            }
            return value;
        }
    }
}

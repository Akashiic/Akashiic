package br.com.atmbrasil.lobby.velocity.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Bounded encoder for the body of Minecraft 1.21.1's clientbound registry-data packet.
 *
 * <p>The Velocity packet encoder owns the packet id and framing. This class writes only the
 * protocol body: registry key, entries and their network NBT values. Keeping the codec independent
 * from Minecraft classes makes the wire format unit-testable and avoids packaging server classes
 * inside the plugin.</p>
 */
public final class MinecraftRegistryPacketCodec {
    private static final Pattern RESOURCE_LOCATION = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");
    // Real modded registries exceed 256 entries (moonlight:soft_fluid has 462 in ATM10); every
    // packet stays bounded by its 1 MiB body and by the NBT node budget below.
    private static final int MAXIMUM_ENTRIES = 65_535;
    private static final int MAXIMUM_COMPOUND_FIELDS = 256;
    private static final int MAXIMUM_LIST_ELEMENTS = 256;
    private static final int MAXIMUM_NBT_DEPTH = 32;
    private static final int MAXIMUM_INSPECTED_NBT_COLLECTION_ELEMENTS = 262_144;
    private static final int MAXIMUM_INSPECTED_NBT_NODES = 262_144;

    private MinecraftRegistryPacketCodec() {
    }

    /** Encodes one complete {@code ClientboundRegistryDataPacket} body. */
    public static byte[] encode(
            String registryId,
            List<Entry> entries,
            int maximumBytes) throws ProtocolViolationException {
        requireResourceLocation(registryId, "registry id");
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty() || entries.size() > MAXIMUM_ENTRIES) {
            throw new ProtocolViolationException("registry entry count is outside bounds");
        }
        if (maximumBytes < 1 || maximumBytes > 1_048_576) {
            throw new IllegalArgumentException("maximumBytes is outside bounds");
        }

        LinkedHashMap<String, Entry> uniqueEntries = new LinkedHashMap<>();
        for (Entry entry : entries) {
            Objects.requireNonNull(entry, "registry entry");
            requireResourceLocation(entry.id(), "registry entry id");
            if (uniqueEntries.putIfAbsent(entry.id(), entry) != null) {
                throw new ProtocolViolationException(
                        "duplicate registry entry id: " + entry.id());
            }
        }

        BoundedOutputStream bounded = new BoundedOutputStream(maximumBytes);
        try (DataOutputStream output = new DataOutputStream(bounded)) {
            writeMinecraftString(output, registryId);
            writeVarInt(output, uniqueEntries.size());
            for (Entry entry : uniqueEntries.values()) {
                writeMinecraftString(output, entry.id());
                output.writeBoolean(true);
                output.writeByte(TagType.COMPOUND.wireId);
                writeCompoundPayload(output, entry.data(), 0);
            }
            output.flush();
            return bounded.toByteArray();
        } catch (BoundExceededException exception) {
            throw new ProtocolViolationException(
                    "registry packet body exceeds configured byte bound");
        } catch (IOException exception) {
            throw new ProtocolViolationException(
                    "could not encode registry packet body: " + exception.getMessage());
        }
    }

    /**
     * Reads only the leading registry identifier from a bounded packet body.
     *
     * <p>This deliberately does not validate the entry table or NBT. It exists for an already
     * claimed registry fence: once a reviewed {@code minecraft:enchantment} transaction has been
     * written, a later packet for the same key must be recognized and consumed even when its
     * trailing body is malformed. Call {@link #inspect(byte[], int)} before trusting any other
     * structural property.</p>
     */
    public static String inspectRegistryId(byte[] packetBody, int maximumBytes)
            throws ProtocolViolationException {
        Objects.requireNonNull(packetBody, "packetBody");
        if (maximumBytes < 1 || maximumBytes > 1_048_576) {
            throw new IllegalArgumentException("maximumBytes is outside bounds");
        }
        if (packetBody.length < 1 || packetBody.length > maximumBytes) {
            throw new ProtocolViolationException(
                    "registry packet body is outside configured byte bound");
        }
        return new PacketReader(packetBody).readResourceLocation("registry id");
    }

    /**
     * Structurally inspects one complete registry-data packet body without trusting its header.
     *
     * <p>The inspector deliberately does not materialize NBT values. It validates every bounded
     * network-NBT payload, rejects duplicate registry entry IDs and requires zero trailing bytes.
     * This is suitable for reviewing an exported native packet before it is admitted to the
     * immutable shim catalog; it is not a general-purpose NBT decoder.</p>
     */
    public static Inspection inspect(byte[] packetBody, int maximumBytes)
            throws ProtocolViolationException {
        Objects.requireNonNull(packetBody, "packetBody");
        if (maximumBytes < 1 || maximumBytes > 1_048_576) {
            throw new IllegalArgumentException("maximumBytes is outside bounds");
        }
        if (packetBody.length < 1 || packetBody.length > maximumBytes) {
            throw new ProtocolViolationException(
                    "registry packet body is outside configured byte bound");
        }

        PacketReader reader = new PacketReader(packetBody);
        String registryId = reader.readResourceLocation("registry id");
        int entryCount = reader.readBoundedVarInt(1, MAXIMUM_ENTRIES, "registry entry count");
        ArrayList<String> entryIds = new ArrayList<>(entryCount);
        LinkedHashSet<String> uniqueEntryIds = new LinkedHashSet<>(entryCount);
        LinkedHashSet<String> entryIdsWithData = new LinkedHashSet<>(entryCount);
        int entriesWithData = 0;
        for (int index = 0; index < entryCount; index++) {
            String entryId = reader.readResourceLocation("registry entry id");
            if (!uniqueEntryIds.add(entryId)) {
                throw new ProtocolViolationException(
                        "duplicate registry entry id: " + entryId);
            }
            entryIds.add(entryId);

            int dataMarker = reader.readUnsignedByte();
            if (dataMarker == 0) {
                continue;
            }
            if (dataMarker != 1) {
                throw new ProtocolViolationException(
                        "registry entry data marker is not canonical");
            }
            int rootType = reader.readUnsignedByte();
            if (rootType != 10) {
                throw new ProtocolViolationException(
                        "registry entry network-NBT root is not a compound");
            }
            reader.skipNbtPayload(rootType, 0);
            entriesWithData++;
            entryIdsWithData.add(entryId);
        }
        if (reader.remaining() != 0) {
            throw new ProtocolViolationException(
                    "registry packet body has trailing bytes");
        }
        return new Inspection(
                registryId,
                entryIds,
                entriesWithData,
                entryIdsWithData,
                packetBody.length);
    }

    /**
     * Merges two complete registry-data bodies for the same registry without re-encoding NBT.
     *
     * <p>Both inputs are fully validated first. Entry identifiers must be disjoint; if the
     * extension attempts to replace an existing key, the merge is rejected instead of silently
     * changing Paper data. The output keeps every original entry byte-for-byte, rewrites only the
     * canonical entry-count VarInt, and appends the extension's already-reviewed entry bytes.</p>
     */
    public static byte[] mergeDistinctEntries(
            byte[] originalBody,
            byte[] extensionBody,
            int maximumBytes) throws ProtocolViolationException {
        Objects.requireNonNull(originalBody, "originalBody");
        Objects.requireNonNull(extensionBody, "extensionBody");
        if (maximumBytes < 1 || maximumBytes > 1_048_576) {
            throw new IllegalArgumentException("maximumBytes is outside bounds");
        }

        PacketLayout original = parseLayout(originalBody, maximumBytes);
        PacketLayout extension = parseLayout(extensionBody, maximumBytes);
        if (!original.registryId().equals(extension.registryId())) {
            throw new ProtocolViolationException(
                    "registry merge inputs target different registry ids");
        }

        LinkedHashSet<String> mergedIds = new LinkedHashSet<>(original.entryIds());
        for (String entryId : extension.entryIds()) {
            if (!mergedIds.add(entryId)) {
                throw new ProtocolViolationException(
                        "registry merge extension duplicates entry id: " + entryId);
            }
        }
        int mergedEntryCount;
        try {
            mergedEntryCount = Math.addExact(
                    original.entryIds().size(), extension.entryIds().size());
        } catch (ArithmeticException exception) {
            throw new ProtocolViolationException("registry merge entry count overflows");
        }
        if (mergedEntryCount > MAXIMUM_ENTRIES) {
            throw new ProtocolViolationException(
                    "registry merge entry count exceeds bound");
        }

        BoundedOutputStream bounded = new BoundedOutputStream(maximumBytes);
        try (DataOutputStream output = new DataOutputStream(bounded)) {
            output.write(originalBody, 0, original.entryCountOffset());
            writeVarInt(output, mergedEntryCount);
            output.write(
                    originalBody,
                    original.entriesOffset(),
                    originalBody.length - original.entriesOffset());
            output.write(
                    extensionBody,
                    extension.entriesOffset(),
                    extensionBody.length - extension.entriesOffset());
            output.flush();
        } catch (BoundExceededException exception) {
            throw new ProtocolViolationException(
                    "merged registry packet body exceeds configured byte bound");
        } catch (IOException exception) {
            throw new ProtocolViolationException(
                    "could not merge registry packet body: " + exception.getMessage());
        }

        byte[] merged = bounded.toByteArray();
        Inspection inspection = inspect(merged, maximumBytes);
        if (!inspection.registryId().equals(original.registryId())
                || inspection.entryIds().size() != mergedEntryCount
                || !inspection.entryIds().equals(List.copyOf(mergedIds))) {
            throw new ProtocolViolationException(
                    "merged registry packet failed post-encode validation");
        }
        return merged;
    }

    /**
     * Copies the entries accepted by {@code keep} into a new body for the same registry.
     *
     * <p>The input is fully validated first. Kept entries retain their original bytes and
     * order; only the entry-count VarInt is rewritten. Returns empty when nothing is kept, since
     * a registry-data packet cannot be empty.</p>
     */
    public static Optional<byte[]> selectEntries(
            byte[] packetBody,
            Predicate<String> keep,
            int maximumBytes) throws ProtocolViolationException {
        Objects.requireNonNull(packetBody, "packetBody");
        Objects.requireNonNull(keep, "keep");
        if (maximumBytes < 1 || maximumBytes > 1_048_576) {
            throw new IllegalArgumentException("maximumBytes is outside bounds");
        }
        PacketLayout layout = parseLayout(packetBody, maximumBytes);
        ArrayList<Integer> kept = new ArrayList<>();
        for (int index = 0; index < layout.entryIds().size(); index++) {
            if (keep.test(layout.entryIds().get(index))) {
                kept.add(index);
            }
        }
        if (kept.isEmpty()) {
            return Optional.empty();
        }
        BoundedOutputStream bounded = new BoundedOutputStream(maximumBytes);
        try (DataOutputStream output = new DataOutputStream(bounded)) {
            output.write(packetBody, 0, layout.entryCountOffset());
            writeVarInt(output, kept.size());
            for (int index : kept) {
                int start = layout.entryOffsets()[index];
                output.write(packetBody, start, layout.entryOffsets()[index + 1] - start);
            }
            output.flush();
        } catch (BoundExceededException exception) {
            throw new ProtocolViolationException(
                    "selected registry packet body exceeds configured byte bound");
        } catch (IOException exception) {
            throw new ProtocolViolationException(
                    "could not select registry entries: " + exception.getMessage());
        }
        byte[] selected = bounded.toByteArray();
        Inspection inspection = inspect(selected, maximumBytes);
        List<String> expectedIds = kept.stream().map(layout.entryIds()::get).toList();
        if (!inspection.registryId().equals(layout.registryId())
                || !inspection.entryIds().equals(expectedIds)) {
            throw new ProtocolViolationException(
                    "selected registry packet failed post-encode validation");
        }
        return Optional.of(selected);
    }

    /**
     * Returns every NBT string of a validated body: string values and compound field names.
     *
     * <p>Holder references in registry network NBT are encoded as identifier strings (or as
     * compound keys for keyed maps). The set is therefore a conservative superset of the entries
     * one registry packet may reference, which lets callers withhold a packet whose references
     * cannot be satisfied instead of letting the client fail its registry load.</p>
     */
    public static Set<String> nbtStrings(byte[] packetBody, int maximumBytes)
            throws ProtocolViolationException {
        Inspection inspection = inspect(packetBody, maximumBytes);
        PacketReader reader = new PacketReader(packetBody);
        reader.collectedStrings = new HashSet<>();
        reader.readResourceLocation("registry id");
        int entryCount = reader.readBoundedVarInt(1, MAXIMUM_ENTRIES, "registry entry count");
        for (int index = 0; index < entryCount; index++) {
            reader.readResourceLocation("registry entry id");
            if (reader.readUnsignedByte() == 1) {
                reader.skipNbtPayload(reader.readUnsignedByte(), 0);
            }
        }
        if (reader.remaining() != 0 || entryCount != inspection.entryIds().size()) {
            throw new ProtocolViolationException(
                    "registry packet changed between inspection and string collection");
        }
        return Set.copyOf(reader.collectedStrings);
    }

    private static PacketLayout parseLayout(byte[] packetBody, int maximumBytes)
            throws ProtocolViolationException {
        Inspection inspection = inspect(packetBody, maximumBytes);
        PacketReader reader = new PacketReader(packetBody);
        String registryId = reader.readResourceLocation("registry id");
        int entryCountOffset = reader.position();
        int entryCount = reader.readBoundedVarInt(
                1, MAXIMUM_ENTRIES, "registry entry count");
        int entriesOffset = reader.position();
        int[] entryOffsets = new int[entryCount + 1];
        for (int index = 0; index < entryCount; index++) {
            entryOffsets[index] = reader.position();
            reader.readResourceLocation("registry entry id");
            int dataMarker = reader.readUnsignedByte();
            if (dataMarker == 0) {
                continue;
            }
            if (dataMarker != 1) {
                throw new ProtocolViolationException(
                        "registry entry data marker is not canonical");
            }
            int rootType = reader.readUnsignedByte();
            if (rootType != 10) {
                throw new ProtocolViolationException(
                        "registry entry network-NBT root is not a compound");
            }
            reader.skipNbtPayload(rootType, 0);
        }
        entryOffsets[entryCount] = reader.position();
        if (reader.remaining() != 0) {
            throw new ProtocolViolationException(
                    "registry packet body has trailing bytes");
        }
        if (!registryId.equals(inspection.registryId())
                || entryCount != inspection.entryIds().size()) {
            throw new ProtocolViolationException(
                    "registry packet layout disagrees with structural inspection");
        }
        return new PacketLayout(
                registryId,
                inspection.entryIds(),
                entryCountOffset,
                entriesOffset,
                entryOffsets);
    }

    private record PacketLayout(
            String registryId,
            List<String> entryIds,
            int entryCountOffset,
            int entriesOffset,
            int[] entryOffsets) {
        private PacketLayout {
            Objects.requireNonNull(registryId, "registryId");
            entryIds = List.copyOf(Objects.requireNonNull(entryIds, "entryIds"));
            Objects.requireNonNull(entryOffsets, "entryOffsets");
            if (entryIds.isEmpty()
                    || entryCountOffset < 1
                    || entriesOffset <= entryCountOffset
                    || entryOffsets.length != entryIds.size() + 1
                    || entryOffsets[0] != entriesOffset) {
                throw new IllegalArgumentException("registry packet layout is outside bounds");
            }
        }
    }

    /** Immutable structural result returned by {@link #inspect(byte[], int)}. */
    public record Inspection(
            String registryId,
            List<String> entryIds,
            int entriesWithData,
            Set<String> entryIdsWithData,
            int packetBytes) {
        public Inspection {
            Objects.requireNonNull(registryId, "registryId");
            entryIds = List.copyOf(Objects.requireNonNull(entryIds, "entryIds"));
            entryIdsWithData = Set.copyOf(
                    Objects.requireNonNull(entryIdsWithData, "entryIdsWithData"));
            if (entryIds.isEmpty()
                    || entriesWithData < 0
                    || entriesWithData > entryIds.size()
                    || entryIdsWithData.size() != entriesWithData
                    || !Set.copyOf(entryIds).containsAll(entryIdsWithData)
                    || packetBytes < 1) {
                throw new IllegalArgumentException("registry inspection is outside bounds");
            }
        }

        public Set<String> entryIdSet() {
            return Set.copyOf(entryIds);
        }
    }

    public record Entry(String id, CompoundTag data) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(data, "data");
        }
    }

    public sealed interface NbtValue
            permits StringTag, IntTag, FloatTag, ListTag, CompoundTag {
        TagType type();
    }

    public record StringTag(String value) implements NbtValue {
        public StringTag {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public TagType type() {
            return TagType.STRING;
        }
    }

    public record IntTag(int value) implements NbtValue {
        @Override
        public TagType type() {
            return TagType.INT;
        }
    }

    public record FloatTag(float value) implements NbtValue {
        public FloatTag {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("NBT float must be finite");
            }
        }

        @Override
        public TagType type() {
            return TagType.FLOAT;
        }
    }

    public record ListTag(List<NbtValue> values) implements NbtValue {
        public ListTag {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
            if (values.isEmpty() || values.size() > MAXIMUM_LIST_ELEMENTS) {
                throw new IllegalArgumentException("NBT list size is outside bounds");
            }
            TagType expected = values.getFirst().type();
            if (values.stream().anyMatch(value -> value.type() != expected)) {
                throw new IllegalArgumentException("NBT list elements must have one type");
            }
        }

        @Override
        public TagType type() {
            return TagType.LIST;
        }
    }

    public record CompoundTag(Map<String, NbtValue> values) implements NbtValue {
        public CompoundTag {
            Objects.requireNonNull(values, "values");
            if (values.size() > MAXIMUM_COMPOUND_FIELDS) {
                throw new IllegalArgumentException("NBT compound field count exceeds bound");
            }
            LinkedHashMap<String, NbtValue> copy = new LinkedHashMap<>();
            values.forEach((name, value) -> {
                if (name == null || name.isEmpty()) {
                    throw new IllegalArgumentException("NBT compound field name is empty");
                }
                copy.put(name, Objects.requireNonNull(value, "NBT compound value"));
            });
            values = Collections.unmodifiableMap(copy);
        }

        @Override
        public TagType type() {
            return TagType.COMPOUND;
        }
    }

    public static StringTag stringTag(String value) {
        return new StringTag(value);
    }

    public static IntTag intTag(int value) {
        return new IntTag(value);
    }

    public static FloatTag floatTag(float value) {
        return new FloatTag(value);
    }

    public static ListTag stringList(String... values) {
        Objects.requireNonNull(values, "values");
        return new ListTag(List.of(values).stream()
                .map(MinecraftRegistryPacketCodec::stringTag)
                .map(NbtValue.class::cast)
                .toList());
    }

    private static void writeCompoundPayload(
            DataOutputStream output,
            CompoundTag compound,
            int depth) throws IOException {
        requireDepth(depth);
        for (Map.Entry<String, NbtValue> field : compound.values().entrySet()) {
            NbtValue value = field.getValue();
            output.writeByte(value.type().wireId);
            output.writeUTF(field.getKey());
            writeValuePayload(output, value, depth + 1);
        }
        output.writeByte(TagType.END.wireId);
    }

    private static void writeValuePayload(
            DataOutputStream output,
            NbtValue value,
            int depth) throws IOException {
        requireDepth(depth);
        switch (value) {
            case StringTag string -> output.writeUTF(string.value());
            case IntTag integer -> output.writeInt(integer.value());
            case FloatTag floating -> output.writeFloat(floating.value());
            case ListTag list -> {
                TagType elementType = list.values().getFirst().type();
                output.writeByte(elementType.wireId);
                output.writeInt(list.values().size());
                for (NbtValue element : list.values()) {
                    writeValuePayload(output, element, depth + 1);
                }
            }
            case CompoundTag compound -> writeCompoundPayload(output, compound, depth + 1);
        }
    }

    private static void requireDepth(int depth) throws IOException {
        if (depth > MAXIMUM_NBT_DEPTH) {
            throw new IOException("NBT nesting exceeds bound");
        }
    }

    private static void writeMinecraftString(DataOutputStream output, String value)
            throws IOException, ProtocolViolationException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > 32_767) {
            throw new ProtocolViolationException("Minecraft string exceeds byte bound");
        }
        writeVarInt(output, utf8.length);
        output.write(utf8);
    }

    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException("VarInt value must not be negative");
        }
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            output.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.writeByte(remaining);
    }

    private static void requireResourceLocation(String value, String label)
            throws ProtocolViolationException {
        Objects.requireNonNull(value, label);
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0 || bytes > 256 || !RESOURCE_LOCATION.matcher(value).matches()) {
            throw new ProtocolViolationException("invalid " + label + ": " + value);
        }
    }

    public enum TagType {
        END(0),
        INT(3),
        FLOAT(5),
        STRING(8),
        LIST(9),
        COMPOUND(10);

        private final int wireId;

        TagType(int wireId) {
            this.wireId = wireId;
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final int maximumBytes;
        private final ByteArrayOutputStream delegate;

        private BoundedOutputStream(int maximumBytes) {
            this.maximumBytes = maximumBytes;
            delegate = new ByteArrayOutputStream(Math.min(maximumBytes, 8_192));
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, values.length);
            requireCapacity(length);
            delegate.write(values, offset, length);
        }

        private void requireCapacity(int bytes) throws BoundExceededException {
            if (bytes < 0 || delegate.size() > maximumBytes - bytes) {
                throw new BoundExceededException();
            }
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }

    private static final class BoundExceededException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class PacketReader {
        private final byte[] bytes;
        private int index;
        private int inspectedNbtNodes;
        private Set<String> collectedStrings;

        private PacketReader(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        private String readResourceLocation(String label) throws ProtocolViolationException {
            int byteCount = readBoundedVarInt(1, 256, label + " byte count");
            byte[] encoded = readBytes(byteCount);
            final String value;
            try {
                value = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(encoded))
                        .toString();
            } catch (CharacterCodingException exception) {
                throw new ProtocolViolationException(label + " is not valid UTF-8");
            }
            if (!RESOURCE_LOCATION.matcher(value).matches()) {
                throw new ProtocolViolationException("invalid " + label + ": " + value);
            }
            return value;
        }

        private int readBoundedVarInt(int minimum, int maximum, String label)
                throws ProtocolViolationException {
            int value = readVarInt();
            if (value < minimum || value > maximum) {
                throw new ProtocolViolationException(label + " is outside bounds");
            }
            return value;
        }

        private int readVarInt() throws ProtocolViolationException {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int current = readUnsignedByte();
                if (shift == 28 && (current & 0xF0) != 0) {
                    throw new ProtocolViolationException("registry packet has oversized VarInt");
                }
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    if (shift > 0 && (current & 0x7F) == 0) {
                        throw new ProtocolViolationException(
                                "registry packet has non-canonical VarInt");
                    }
                    return value;
                }
            }
            throw new ProtocolViolationException("registry packet has oversized VarInt");
        }

        private void skipNbtPayload(int tagType, int depth)
                throws ProtocolViolationException {
            if (depth > MAXIMUM_NBT_DEPTH) {
                throw new ProtocolViolationException("registry entry NBT depth exceeds bound");
            }
            inspectedNbtNodes = Math.addExact(inspectedNbtNodes, 1);
            if (inspectedNbtNodes > MAXIMUM_INSPECTED_NBT_NODES) {
                throw new ProtocolViolationException("registry entry NBT node count exceeds bound");
            }
            switch (tagType) {
                case 0 -> {
                    return;
                }
                case 1 -> skip(1);
                case 2 -> skip(2);
                case 3, 5 -> skip(4);
                case 4, 6 -> skip(8);
                case 7 -> skip(readBoundedCollectionBytes(1));
                case 8 -> skipNbtString();
                case 9 -> {
                    int elementType = readUnsignedByte();
                    if (elementType > 12) {
                        throw new ProtocolViolationException(
                                "registry entry NBT list type is invalid");
                    }
                    int elementCount = readBoundedCollectionLength();
                    if (elementType == 0 && elementCount != 0) {
                        throw new ProtocolViolationException(
                                "registry entry NBT end-tag list is non-empty");
                    }
                    for (int index = 0; index < elementCount; index++) {
                        skipNbtPayload(elementType, depth + 1);
                    }
                }
                case 10 -> {
                    while (true) {
                        int childType = readUnsignedByte();
                        if (childType == 0) {
                            return;
                        }
                        if (childType > 12) {
                            throw new ProtocolViolationException(
                                    "registry entry NBT compound type is invalid");
                        }
                        skipNbtString();
                        skipNbtPayload(childType, depth + 1);
                    }
                }
                case 11 -> skip(readBoundedCollectionBytes(4));
                case 12 -> skip(readBoundedCollectionBytes(8));
                default -> throw new ProtocolViolationException(
                        "registry entry NBT tag type is invalid");
            }
        }

        private int readBoundedCollectionLength() throws ProtocolViolationException {
            int length = readInt();
            if (length < 0 || length > MAXIMUM_INSPECTED_NBT_COLLECTION_ELEMENTS) {
                throw new ProtocolViolationException(
                        "registry entry NBT collection length is outside bounds");
            }
            return length;
        }

        private int readBoundedCollectionBytes(int elementBytes)
                throws ProtocolViolationException {
            int length = readBoundedCollectionLength();
            try {
                return Math.multiplyExact(length, elementBytes);
            } catch (ArithmeticException exception) {
                throw new ProtocolViolationException(
                        "registry entry NBT collection byte length overflows");
            }
        }

        private void skipNbtString() throws ProtocolViolationException {
            int length = readUnsignedShort();
            if (collectedStrings == null) {
                skip(length);
            } else {
                // Identifiers are ASCII, where modified UTF-8 and UTF-8 agree.
                collectedStrings.add(new String(readBytes(length), StandardCharsets.UTF_8));
            }
        }

        private int readInt() throws ProtocolViolationException {
            return (readUnsignedByte() << 24)
                    | (readUnsignedByte() << 16)
                    | (readUnsignedByte() << 8)
                    | readUnsignedByte();
        }

        private int readUnsignedShort() throws ProtocolViolationException {
            return (readUnsignedByte() << 8) | readUnsignedByte();
        }

        private int readUnsignedByte() throws ProtocolViolationException {
            if (index >= bytes.length) {
                throw new ProtocolViolationException("registry packet body is truncated");
            }
            return bytes[index++] & 0xFF;
        }

        private byte[] readBytes(int length) throws ProtocolViolationException {
            if (length < 0 || index > bytes.length - length) {
                throw new ProtocolViolationException("registry packet body is truncated");
            }
            byte[] copy = java.util.Arrays.copyOfRange(bytes, index, index + length);
            index += length;
            return copy;
        }

        private void skip(int length) throws ProtocolViolationException {
            if (length < 0 || index > bytes.length - length) {
                throw new ProtocolViolationException("registry packet body is truncated");
            }
            index += length;
        }

        private int remaining() {
            return bytes.length - index;
        }

        private int position() {
            return index;
        }
    }
}

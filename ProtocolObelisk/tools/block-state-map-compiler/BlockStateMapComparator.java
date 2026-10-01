import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Build-only, fail-closed comparator for the reviewed Minecraft 1.21.1 to ATM10 8.0 BlockState
 * map.
 *
 * <p>This class is intentionally outside every Gradle source set. It consumes independently
 * generated vanilla and ATM tables, proves the exact neutral-property projection, and atomically
 * emits the human-reviewable mapping consumed by {@link BlockStateMapCompiler}.</p>
 */
public final class BlockStateMapComparator {
    private static final int EXPECTED_VANILLA_STATE_COUNT = 26_684;
    private static final int REVIEWED_ATM_GLOBAL_STATE_COUNT = 1_980_659;
    private static final int REVIEWED_ATM_MINECRAFT_STATE_COUNT = 45_481;
    private static final int MAXIMUM_VANILLA_BYTES = 4 * 1024 * 1024;
    private static final int MAXIMUM_ATM_BYTES = 8 * 1024 * 1024;
    private static final int MAXIMUM_LINE_BYTES = 16_384;
    private static final Pattern RESOURCE_LOCATION =
            Pattern.compile("minecraft:[a-z0-9_./-]+");
    private static final Pattern PROPERTY_NAME = Pattern.compile("[a-z0-9_]+");
    private static final Pattern PROPERTY_VALUE = Pattern.compile("[a-z0-9_.:/-]+");
    private static final Map<String, Integer> REVIEWED_EXTRA_PROPERTY_BLOCK_COUNTS = Map.of(
            "essentia_logged", 337,
            "boiling", 1,
            "waterlogged", 14);

    private BlockStateMapComparator() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 5) {
            throw new IllegalArgumentException(
                    "usage: BlockStateMapComparator <vanilla.tsv> <atm.tsv> <output.tsv> "
                            + "<atm-global-state-count> <atm-minecraft-state-count>");
        }
        Path vanillaPath = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path atmPath = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path outputPath = Path.of(arguments[2]).toAbsolutePath().normalize();
        if (vanillaPath.equals(atmPath)
                || vanillaPath.equals(outputPath)
                || atmPath.equals(outputPath)) {
            throw new IllegalArgumentException("all input and output paths must differ");
        }

        int expectedGlobalCount = parseCanonicalNonNegativeInt(
                arguments[3], "ATM global state count");
        int expectedMinecraftCount = parseCanonicalNonNegativeInt(
                arguments[4], "ATM minecraft state count");
        if (expectedGlobalCount != REVIEWED_ATM_GLOBAL_STATE_COUNT
                || expectedMinecraftCount != REVIEWED_ATM_MINECRAFT_STATE_COUNT) {
            throw new IllegalArgumentException(
                    "ATM state counts do not identify the reviewed ATM10 8.0 export");
        }

        List<VanillaEntry> vanilla = readVanilla(vanillaPath);
        AtmTable atm = readAtm(atmPath, expectedGlobalCount, expectedMinecraftCount);
        byte[] first = compare(vanilla, atm.entries(), expectedGlobalCount);
        byte[] second = compare(vanilla, atm.entries(), expectedGlobalCount);
        if (!Arrays.equals(first, second)) {
            throw new IllegalStateException("comparison output is not deterministic");
        }
        publishAtomically(outputPath, first);
        System.out.printf(
                "compared %d vanilla states against %d ATM minecraft states, %d bytes, "
                        + "sha256=%s%n",
                vanilla.size(), atm.entries().size(), first.length, sha256(first));
    }

    private static List<VanillaEntry> readVanilla(Path path) throws IOException {
        List<String> lines = readStrictLines(path, MAXIMUM_VANILLA_BYTES, "vanilla table");
        if (lines.size() != EXPECTED_VANILLA_STATE_COUNT) {
            throw new IllegalArgumentException(
                    "vanilla state count mismatch: " + lines.size());
        }
        List<VanillaEntry> entries = new ArrayList<>(lines.size());
        Set<StateKey> uniqueStates = new HashSet<>(lines.size());
        for (int expectedId = 0; expectedId < lines.size(); expectedId++) {
            String[] fields = splitExact(lines.get(expectedId), 2, "vanilla line");
            int id = parseCanonicalNonNegativeInt(fields[0], "vanilla id");
            if (id != expectedId) {
                throw new IllegalArgumentException(
                        "vanilla ids are not dense at " + expectedId);
            }
            ParsedState state = parseState(fields[1], "vanilla state " + id);
            StateKey key = state.key();
            if (!uniqueStates.add(key)) {
                throw new IllegalArgumentException("duplicate vanilla state at id " + id);
            }
            entries.add(new VanillaEntry(id, fields[1], state));
        }
        validateConsistentSchemas(entries.stream()
                .map(entry -> new SchemaEntry(entry.id(), entry.state()))
                .toList(), "vanilla");
        return List.copyOf(entries);
    }

    private static AtmTable readAtm(
            Path path, int expectedGlobalCount, int expectedMinecraftCount) throws IOException {
        List<String> lines = readStrictLines(path, MAXIMUM_ATM_BYTES, "ATM table");
        if (lines.size() < 3 || !lines.get(0).equals("format-version=1")
                || !lines.get(1).equals("scope=minecraft-namespace")) {
            throw new IllegalArgumentException(
                    "ATM table must start with format-version=1 and scope=minecraft-namespace");
        }

        int cursor = 2;
        Integer headerGlobalCount = null;
        Integer headerMinecraftCount = null;
        while (cursor < lines.size() && lines.get(cursor).indexOf('\t') < 0) {
            String line = lines.get(cursor);
            if (line.startsWith("global-state-count=")) {
                if (headerGlobalCount != null) {
                    throw new IllegalArgumentException("duplicate global-state-count header");
                }
                headerGlobalCount = parseCanonicalNonNegativeInt(
                        line.substring("global-state-count=".length()),
                        "global-state-count header");
            } else if (line.startsWith("namespace-state-count=")) {
                if (headerMinecraftCount != null) {
                    throw new IllegalArgumentException("duplicate namespace-state-count header");
                }
                headerMinecraftCount = parseCanonicalNonNegativeInt(
                        line.substring("namespace-state-count=".length()),
                        "namespace-state-count header");
            } else {
                throw new IllegalArgumentException("unknown ATM header: " + line);
            }
            cursor++;
        }
        if ((headerGlobalCount == null) != (headerMinecraftCount == null)) {
            throw new IllegalArgumentException(
                    "ATM count headers must either both be present or both be absent");
        }
        if (headerGlobalCount != null
                && (headerGlobalCount.intValue() != expectedGlobalCount
                        || headerMinecraftCount.intValue() != expectedMinecraftCount)) {
            throw new IllegalArgumentException("ATM count headers disagree with reviewed evidence");
        }
        if (lines.size() - cursor != expectedMinecraftCount) {
            throw new IllegalArgumentException(
                    "ATM minecraft state count mismatch: " + (lines.size() - cursor));
        }

        List<AtmEntry> entries = new ArrayList<>(expectedMinecraftCount);
        Set<Integer> uniqueIds = new HashSet<>(expectedMinecraftCount);
        Set<StateKey> uniqueStates = new HashSet<>(expectedMinecraftCount);
        int previousId = -1;
        for (int index = cursor; index < lines.size(); index++) {
            String[] fields = splitExact(lines.get(index), 2, "ATM line");
            int id = parseCanonicalNonNegativeInt(fields[0], "ATM global id");
            if (id <= previousId || id >= expectedGlobalCount || !uniqueIds.add(id)) {
                throw new IllegalArgumentException(
                        "ATM ids are not unique, increasing, and globally bounded at " + id);
            }
            ParsedState state = parseState(fields[1], "ATM state " + id);
            if (!uniqueStates.add(state.key())) {
                throw new IllegalArgumentException("duplicate ATM state at global id " + id);
            }
            entries.add(new AtmEntry(id, state));
            previousId = id;
        }
        validateConsistentSchemas(entries.stream()
                .map(entry -> new SchemaEntry(entry.globalId(), entry.state()))
                .toList(), "ATM");
        return new AtmTable(List.copyOf(entries));
    }

    private static byte[] compare(
            List<VanillaEntry> vanilla, List<AtmEntry> atm, int expectedGlobalCount) {
        Map<String, Set<String>> vanillaSchemas = schemas(vanilla.stream()
                .map(entry -> new SchemaEntry(entry.id(), entry.state()))
                .toList());
        Map<String, Set<String>> atmSchemas = schemas(atm.stream()
                .map(entry -> new SchemaEntry(entry.globalId(), entry.state()))
                .toList());
        if (!vanillaSchemas.keySet().equals(atmSchemas.keySet())) {
            throw new IllegalArgumentException("vanilla and ATM minecraft block sets differ");
        }

        Map<String, Set<String>> extrasByBlock = new HashMap<>();
        Map<String, Set<String>> blocksByExtra = new HashMap<>();
        for (Map.Entry<String, Set<String>> vanillaSchema : vanillaSchemas.entrySet()) {
            String block = vanillaSchema.getKey();
            Set<String> atmProperties = atmSchemas.get(block);
            if (!atmProperties.containsAll(vanillaSchema.getValue())) {
                throw new IllegalArgumentException(
                        "ATM state schema removed vanilla properties from " + block);
            }
            Set<String> extras = new HashSet<>(atmProperties);
            extras.removeAll(vanillaSchema.getValue());
            if (!REVIEWED_EXTRA_PROPERTY_BLOCK_COUNTS.keySet().containsAll(extras)) {
                throw new IllegalArgumentException("unreviewed extra property on " + block);
            }
            Set<String> immutableExtras = Set.copyOf(extras);
            extrasByBlock.put(block, immutableExtras);
            for (String extra : immutableExtras) {
                blocksByExtra.computeIfAbsent(extra, ignored -> new HashSet<>()).add(block);
            }
        }
        for (Map.Entry<String, Integer> expected
                : REVIEWED_EXTRA_PROPERTY_BLOCK_COUNTS.entrySet()) {
            int observed = blocksByExtra.getOrDefault(expected.getKey(), Set.of()).size();
            if (observed != expected.getValue().intValue()) {
                throw new IllegalArgumentException(
                        "reviewed block count mismatch for " + expected.getKey()
                                + ": " + observed);
            }
        }

        Map<StateKey, Integer> projectedTargets = new HashMap<>(vanilla.size());
        Set<ExtraObservation> falseExtras = new HashSet<>();
        Set<ExtraObservation> trueExtras = new HashSet<>();
        Set<StateKey> vanillaKeys = new HashSet<>(vanilla.size());
        for (VanillaEntry entry : vanilla) {
            vanillaKeys.add(entry.state().key());
        }
        for (AtmEntry entry : atm) {
            ParsedState state = entry.state();
            Set<String> extras = extrasByBlock.get(state.block());
            boolean neutral = true;
            for (String extra : extras) {
                String value = state.properties().get(extra);
                ExtraObservation observation = new ExtraObservation(state.block(), extra);
                if ("false".equals(value)) {
                    falseExtras.add(observation);
                } else if ("true".equals(value)) {
                    trueExtras.add(observation);
                    neutral = false;
                } else {
                    throw new IllegalArgumentException(
                            "extra property is not boolean on " + state.block());
                }
            }
            if (!neutral) {
                continue;
            }
            Map<String, String> projectedProperties = new LinkedHashMap<>(state.properties());
            for (String extra : extras) {
                projectedProperties.remove(extra);
            }
            StateKey projected = new StateKey(state.block(), Map.copyOf(projectedProperties));
            if (!vanillaKeys.contains(projected)) {
                // Modded enum registries can add values to an existing vanilla property (for
                // example note-block instruments). Such ATM-only states have no source id and are
                // deliberately outside the projection; every vanilla source still has to match.
                continue;
            }
            Integer duplicate = projectedTargets.putIfAbsent(projected, entry.globalId());
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "ambiguous neutral ATM projection at global ids "
                                + duplicate + " and " + entry.globalId());
            }
        }
        Set<ExtraObservation> expectedObservations = new HashSet<>();
        for (Map.Entry<String, Set<String>> block : extrasByBlock.entrySet()) {
            for (String extra : block.getValue()) {
                expectedObservations.add(new ExtraObservation(block.getKey(), extra));
            }
        }
        if (!falseExtras.equals(expectedObservations) || !trueExtras.equals(expectedObservations)) {
            throw new IllegalArgumentException(
                    "every reviewed extra property must expose both false and true states");
        }
        if (projectedTargets.size() != EXPECTED_VANILLA_STATE_COUNT) {
            throw new IllegalArgumentException(
                    "neutral projected state count mismatch: " + projectedTargets.size());
        }

        StringBuilder output = new StringBuilder(3 * 1024 * 1024);
        int previousTarget = -1;
        for (VanillaEntry entry : vanilla) {
            Integer target = projectedTargets.get(entry.state().key());
            if (target == null) {
                throw new IllegalArgumentException(
                        "vanilla state has no neutral ATM projection at id " + entry.id());
            }
            if (target.intValue() <= previousTarget || target.intValue() >= expectedGlobalCount) {
                throw new IllegalArgumentException(
                        "mapped targets are not strictly increasing and bounded at vanilla id "
                                + entry.id());
            }
            output.append(entry.id())
                    .append('\t')
                    .append(target.intValue())
                    .append('\t')
                    .append(entry.canonical())
                    .append('\n');
            previousTarget = target.intValue();
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void validateConsistentSchemas(List<SchemaEntry> entries, String label) {
        Map<String, Set<String>> expectedByBlock = new HashMap<>();
        for (SchemaEntry entry : entries) {
            Set<String> schema = entry.state().properties().keySet();
            Set<String> previous = expectedByBlock.putIfAbsent(
                    entry.state().block(), Set.copyOf(schema));
            if (previous != null && !previous.equals(schema)) {
                throw new IllegalArgumentException(
                        label + " property schema varies within " + entry.state().block()
                                + " at id " + entry.id());
            }
        }
    }

    private static Map<String, Set<String>> schemas(List<SchemaEntry> entries) {
        Map<String, Set<String>> result = new HashMap<>();
        for (SchemaEntry entry : entries) {
            result.putIfAbsent(
                    entry.state().block(), Set.copyOf(entry.state().properties().keySet()));
        }
        return result;
    }

    private static ParsedState parseState(String value, String field) {
        int opening = value.indexOf('[');
        String block = opening < 0 ? value : value.substring(0, opening);
        if (!RESOURCE_LOCATION.matcher(block).matches()) {
            throw new IllegalArgumentException(field + " has an invalid resource location");
        }
        if (opening < 0) {
            return new ParsedState(block, Map.of());
        }
        if (!value.endsWith("]") || opening == value.length() - 2
                || value.indexOf('[', opening + 1) >= 0
                || value.indexOf(']', opening) != value.length() - 1) {
            throw new IllegalArgumentException(field + " has malformed properties");
        }
        String body = value.substring(opening + 1, value.length() - 1);
        Map<String, String> properties = new LinkedHashMap<>();
        String previousName = null;
        for (String assignment : body.split(",", -1)) {
            int separator = assignment.indexOf('=');
            if (separator <= 0 || separator == assignment.length() - 1
                    || assignment.indexOf('=', separator + 1) >= 0) {
                throw new IllegalArgumentException(field + " has a malformed assignment");
            }
            String name = assignment.substring(0, separator);
            String propertyValue = assignment.substring(separator + 1);
            if (!PROPERTY_NAME.matcher(name).matches()
                    || !PROPERTY_VALUE.matcher(propertyValue).matches()) {
                throw new IllegalArgumentException(field + " has an invalid property");
            }
            if (previousName != null && previousName.compareTo(name) >= 0) {
                throw new IllegalArgumentException(
                        field + " properties are not unique and canonically ordered");
            }
            properties.put(name, propertyValue);
            previousName = name;
        }
        return new ParsedState(block, Map.copyOf(properties));
    }

    private static String[] splitExact(String line, int expectedFields, String field) {
        if (line.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_LINE_BYTES) {
            throw new IllegalArgumentException(field + " exceeds its byte bound");
        }
        String[] fields = line.split("\\t", -1);
        if (fields.length != expectedFields) {
            throw new IllegalArgumentException(
                    field + " must have exactly " + expectedFields + " TSV fields");
        }
        return fields;
    }

    private static List<String> readStrictLines(Path path, int maximumBytes, String field)
            throws IOException {
        long size = Files.size(path);
        if (size <= 0 || size > maximumBytes) {
            throw new IllegalArgumentException(field + " violates its file-size bound");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes[bytes.length - 1] != '\n') {
            throw new IllegalArgumentException(field + " must end at a complete LF line");
        }
        for (byte value : bytes) {
            if (value == '\r' || value == 0) {
                throw new IllegalArgumentException(field + " contains forbidden control bytes");
            }
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(field + " is not strict UTF-8", exception);
        }
        String[] split = decoded.substring(0, decoded.length() - 1).split("\\n", -1);
        for (String line : split) {
            if (line.isEmpty()) {
                throw new IllegalArgumentException(field + " contains an empty line");
            }
        }
        return List.of(split);
    }

    private static int parseCanonicalNonNegativeInt(String value, String field) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || !Integer.toString(parsed).equals(value)) {
                throw new IllegalArgumentException(field + " is not canonical");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " is invalid", exception);
        }
    }

    private static void publishAtomically(Path output, byte[] bytes) throws IOException {
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("output must have a parent directory");
        }
        Files.createDirectories(parent);
        Path staging = Files.createTempFile(parent, output.getFileName().toString(), ".tmp");
        boolean published = false;
        try {
            Files.write(staging, bytes);
            try {
                Files.move(
                        staging,
                        output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("atomic publication is unavailable for " + output, exception);
            }
            published = true;
        } finally {
            if (!published) {
                Files.deleteIfExists(staging);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private record ParsedState(String block, Map<String, String> properties) {
        private StateKey key() {
            return new StateKey(block, properties);
        }
    }

    private record StateKey(String block, Map<String, String> properties) {
    }

    private record VanillaEntry(int id, String canonical, ParsedState state) {
    }

    private record AtmEntry(int globalId, ParsedState state) {
    }

    private record SchemaEntry(int id, ParsedState state) {
    }

    private record ExtraObservation(String block, String property) {
    }

    private record AtmTable(List<AtmEntry> entries) {
    }
}

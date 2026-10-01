package br.com.atmbrasil.protocolobelisk.fixture;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Properties;

/** Applies the reviewed deterministic schema to two existing exporter outputs. */
public final class CanonicalBlockStateIntegrationAudit {
    private static final String PROFILE_RELATIVE =
            "integration/blockstate-profiles/atm10-normal-8.1-neoforge-21.1.249";

    private CanonicalBlockStateIntegrationAudit() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                    "usage: CanonicalBlockStateIntegrationAudit <out-a> <out-b> <audit-output>");
        }
        Path outA = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path outB = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path output = Path.of(arguments[2]).toAbsolutePath().normalize();
        if (Files.exists(output)) {
            throw new IllegalArgumentException("audit output must not exist: " + output);
        }
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("audit output requires a parent");
        }
        Files.createDirectories(parent);

        AuditInput a = inspect(outA, parent);
        AuditInput b = inspect(outB, parent);
        if (!a.descriptors().equals(b.descriptors())) {
            throw new IllegalStateException("canonical descriptor-set evidence differs across boots");
        }
        if (!a.map().equals(b.map()) || !Arrays.equals(a.mapBytes(), b.mapBytes())) {
            throw new IllegalStateException("POBS bytes/evidence differ across boots");
        }
        byte[] manifestA = CanonicalBlockStateDescriptorSet.reviewedManifest(
                a.descriptors(), a.map());
        byte[] manifestB = CanonicalBlockStateDescriptorSet.reviewedManifest(
                b.descriptors(), b.map());
        if (!Arrays.equals(manifestA, manifestB)) {
            throw new AssertionError("canonical integration manifests are not byte-identical");
        }

        Path staging = Files.createTempDirectory(parent, ".canonical-schema-audit-");
        boolean published = false;
        try {
            Files.write(staging.resolve("block-state-map-a.properties"), manifestA);
            Files.write(staging.resolve("block-state-map-b.properties"), manifestB);
            String rawA = CanonicalBlockStateDescriptorSet.sha256(a.globalTable());
            String rawB = CanonicalBlockStateDescriptorSet.sha256(b.globalTable());
            String manifestHash = CanonicalBlockStateDescriptorSet.sha256(manifestA);
            String audit = new StringBuilder()
                    .append("format-version=1\n")
                    .append("input-a=").append(outA).append('\n')
                    .append("input-b=").append(outB).append('\n')
                    .append("raw-runtime-order.a.sha256=").append(rawA).append('\n')
                    .append("raw-runtime-order.b.sha256=").append(rawB).append('\n')
                    .append("raw-runtime-order.byte-identical=")
                    .append(rawA.equals(rawB)).append('\n')
                    .append("canonicalization=")
                    .append(a.descriptors().canonicalization()).append('\n')
                    .append("canonical-descriptor-set.count=")
                    .append(a.descriptors().count()).append('\n')
                    .append("canonical-descriptor-set.sha256=")
                    .append(a.descriptors().sha256()).append('\n')
                    .append("pobs.bytes=").append(a.map().bytes()).append('\n')
                    .append("pobs.sha256=").append(a.map().sha256()).append('\n')
                    .append("pobs.byte-identical=true\n")
                    .append("integration-manifest.bytes=").append(manifestA.length).append('\n')
                    .append("integration-manifest.sha256=").append(manifestHash).append('\n')
                    .append("integration-manifest.byte-identical=true\n")
                    .toString();
            Files.writeString(
                    staging.resolve("canonical-dual-boot-audit.properties"),
                    audit,
                    StandardCharsets.UTF_8);
            try {
                Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException("atomic audit publication is unavailable", exception);
            }
            published = true;
            System.out.println("canonical-descriptor-set.sha256=" + a.descriptors().sha256());
            System.out.println("integration-manifest.sha256=" + manifestHash);
            System.out.println("audit-output=" + output);
        } finally {
            if (!published) {
                deleteTree(staging);
            }
        }
    }

    private static AuditInput inspect(Path output, Path temporaryParent) throws IOException {
        Path global = output.resolve("block-states/global-block-states.tsv");
        Path mapPath = output.resolve("block-states/block-state-map.bin");
        Path oldManifestPath = output.resolve(PROFILE_RELATIVE).resolve(
                "block-state-map.properties");
        byte[] mapBytes = Files.readAllBytes(mapPath);
        CanonicalBlockStateDescriptorSet.MapEvidence map =
                CanonicalBlockStateDescriptorSet.inspectPobs(mapBytes);
        CanonicalBlockStateDescriptorSet.Evidence descriptors =
                CanonicalBlockStateDescriptorSet.analyze(global, temporaryParent);
        validateExistingManifest(oldManifestPath, global, map);
        return new AuditInput(global, descriptors, mapBytes, map);
    }

    private static void validateExistingManifest(
            Path manifestPath,
            Path global,
            CanonicalBlockStateDescriptorSet.MapEvidence map
    ) throws IOException {
        byte[] bytes = Files.readAllBytes(manifestPath);
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(bytes));
        require(properties, "format-version", "2");
        require(properties, "profile-id", CanonicalBlockStateDescriptorSet.PROFILE_ID);
        require(properties, "client-global-state.count", Integer.toString(map.targetGlobalCount()));
        require(properties, "map.sha256", map.sha256());
        String ambiguousRawHash = properties.getProperty("client-state-table.sha256");
        if (ambiguousRawHash != null
                && !ambiguousRawHash.equals(CanonicalBlockStateDescriptorSet.sha256(global))) {
            throw new IllegalArgumentException(
                    "existing ambiguous runtime-order hash does not match its global table");
        }
    }

    private static void require(Properties properties, String key, String expected) {
        String actual = properties.getProperty(key);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("existing manifest " + key + " mismatch");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record AuditInput(
            Path globalTable,
            CanonicalBlockStateDescriptorSet.Evidence descriptors,
            byte[] mapBytes,
            CanonicalBlockStateDescriptorSet.MapEvidence map
    ) {
        private AuditInput {
            mapBytes = mapBytes.clone();
        }

        @Override
        public byte[] mapBytes() {
            return mapBytes.clone();
        }
    }
}

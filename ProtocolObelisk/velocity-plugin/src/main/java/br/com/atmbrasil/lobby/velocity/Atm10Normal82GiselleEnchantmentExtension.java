package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.Inspection;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Four-entry Giselle enchantment extension used only by the ATM10 8.2 lineage merge path.
 *
 * <p>The bytes are extracted verbatim from the reviewed ATM10 8.1 runtime registry capture. The
 * upstream 1.21.1 AddonEnchantments definition did not change between the reviewed 8.1 source
 * commit and the 8.3 lineage commit preceding the 8.4 localization-only update. This class does
 * not claim a full ATM10 8.2 registry capture; it authorizes only these four unchanged entries.</p>
 */
final class Atm10Normal82GiselleEnchantmentExtension {
    static final String SHIM_ID = "ad-astra-giselle-atm10-8.2-lineage";
    static final String REQUIRED_NAMESPACE = "ad_astra";
    static final String REGISTRY_ID = "minecraft:enchantment";
    static final int ENTRY_COUNT = 4;
    static final String SOURCE_RUNTIME_FULL_PACKET_SHA256 =
            "e50135f0a0d111014c81d170be0ce7b2590de1415650012540d5dd49e4c30d77";
    static final String SOURCE_ADDON_COMMIT =
            "068f631ab769c5d22c007c6f266d8d436286bb5d";
    static final String VALIDATED_ADDON_LINEAGE_COMMIT =
            "178e1013f896e7d85b79e5d21dc50c67a225377c";
    static final String PACKET_SHA256 =
            "d03b3e1424a53d13f6e47170fec4be267e9e273b81be8e75abb83e8501767ee9";
    static final int PACKET_BYTES = 1_401;
    static final String PACKET_RESOURCE =
            "reviewed-registries/atm10-normal-8.2-lineage/"
                    + "ad-astra-giselle-enchantment-extension.bin";
    static final List<String> ENTRY_IDS = List.of(
            "ad_astra_giselle_addon:acid_rain_proof",
            "ad_astra_giselle_addon:gravity_normalizing",
            "ad_astra_giselle_addon:space_breathing",
            "ad_astra_giselle_addon:space_fire_proof");

    private Atm10Normal82GiselleEnchantmentExtension() {
    }

    static RegistryShimPacket packet(int configuredMaximumBytes) {
        return load(Atm10Normal82GiselleEnchantmentExtension.class.getClassLoader(),
                configuredMaximumBytes);
    }

    static RuntimeResolution runtimeResolution(int configuredMaximumBytes) {
        try {
            return new RuntimeResolution(
                    Optional.of(packet(configuredMaximumBytes)), Optional.empty());
        } catch (RuntimeException | LinkageError failure) {
            return new RuntimeResolution(Optional.empty(), Optional.of(failure));
        }
    }

    static RegistryShimPacket loadForTest(ClassLoader loader, int configuredMaximumBytes) {
        return load(loader, configuredMaximumBytes);
    }

    private static RegistryShimPacket load(ClassLoader loader, int configuredMaximumBytes) {
        Objects.requireNonNull(loader, "loader");
        if (configuredMaximumBytes < 1 || configuredMaximumBytes > 1_048_576) {
            throw new IllegalArgumentException(
                    "maximum-registry-shim-bytes is outside bounds");
        }
        if (PACKET_BYTES > configuredMaximumBytes) {
            throw new IllegalArgumentException(
                    SHIM_ID + " requires " + PACKET_BYTES
                            + " bytes but maximum-registry-shim-bytes is "
                            + configuredMaximumBytes);
        }

        try (InputStream stream = loader.getResourceAsStream(PACKET_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed extension resource is missing");
            }
            byte[] body = stream.readNBytes(Math.addExact(configuredMaximumBytes, 1));
            if (body.length != PACKET_BYTES) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed extension length mismatch: " + body.length);
            }
            String actualSha256 = sha256(body);
            if (!actualSha256.equals(PACKET_SHA256)) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed extension SHA-256 mismatch: " + actualSha256);
            }

            Inspection inspection;
            try {
                inspection = MinecraftRegistryPacketCodec.inspect(
                        body, configuredMaximumBytes);
            } catch (ProtocolViolationException exception) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed extension is structurally invalid", exception);
            }
            if (!inspection.registryId().equals(REGISTRY_ID)
                    || inspection.entriesWithData() != ENTRY_COUNT
                    || !inspection.entryIds().equals(ENTRY_IDS)) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed extension identity differs from evidence");
            }

            return new RegistryShimPacket(
                    SHIM_ID,
                    REQUIRED_NAMESPACE,
                    REGISTRY_ID,
                    ENTRY_COUNT,
                    body,
                    actualSha256);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed extension could not be read", exception);
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

    record RuntimeResolution(
            Optional<RegistryShimPacket> packet,
            Optional<Throwable> quarantineFailure) {
        RuntimeResolution {
            packet = Objects.requireNonNull(packet, "packet");
            quarantineFailure = Objects.requireNonNull(
                    quarantineFailure, "quarantineFailure");
            if (packet.isPresent() == quarantineFailure.isPresent()) {
                throw new IllegalArgumentException(
                        "runtime resolution must contain exactly one outcome");
            }
        }
    }
}

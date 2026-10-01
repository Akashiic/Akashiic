package br.com.atmbrasil.lobby.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Set;

/** Reviewed, in-memory AE2 CLIENT config used only for the exact ATM10 TTS lobby profile. */
final class Ae2JeiSessionOptimization {
    static final String CONFIG_FILE_NAME = "ae2-client.toml";
    static final String REVIEWED_AE2_VERSION = "19.2.17";
    static final String REVIEWED_AE2_JEI_VERSION = "1.2.1";

    private static final String REVIEWED_PROFILE_ID =
            "atm10-tts-2.0.2_silentgear-4.1.3.1_neoforge-21.1.221";
    private static final String REQUIRED_NAMESPACE = "ae2";
    private static final String RESOURCE =
            "reviewed-configs/atm10-tts-2.0.2/ae2-client.toml";
    private static final int MAXIMUM_RESOURCE_BYTES = 16_384;
    private static final int EXPECTED_CONTENT_BYTES = 756;
    private static final String EXPECTED_CONTENT_SHA256 =
            "6ee20273f31bf40a34fb7ed3136c1b03b744855a31fa719e3ef46c7f2b77de87";

    private final byte[] contents;

    private Ae2JeiSessionOptimization(byte[] contents) {
        this.contents = contents.clone();
    }

    static Ae2JeiSessionOptimization loadReviewed(ClassLoader loader) throws IOException {
        Objects.requireNonNull(loader, "loader");
        final byte[] bytes;
        try (InputStream stream = loader.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IOException("missing embedded AE2-JEI session optimization");
            }
            bytes = stream.readNBytes(MAXIMUM_RESOURCE_BYTES + 1);
        }
        if (bytes.length > MAXIMUM_RESOURCE_BYTES) {
            throw new IllegalArgumentException(
                    "embedded AE2-JEI session optimization exceeds byte limit");
        }
        if (bytes.length != EXPECTED_CONTENT_BYTES) {
            throw new IllegalArgumentException(
                    "embedded AE2-JEI session optimization length mismatch");
        }
        String actualSha256 = SilentGearProtocol.sha256(bytes);
        if (!actualSha256.equals(EXPECTED_CONTENT_SHA256)) {
            throw new IllegalArgumentException(
                    "embedded AE2-JEI session optimization hash mismatch");
        }
        return new Ae2JeiSessionOptimization(bytes);
    }

    boolean matches(SilentGearEmbeddedProfile profile, Set<String> advertisedNamespaces) {
        Objects.requireNonNull(advertisedNamespaces, "advertisedNamespaces");
        return profile != null
                && profile.profileId().equals(REVIEWED_PROFILE_ID)
                && profile.packRelease().equals("2.0.2")
                && profile.neoForgeVersion().equals("21.1.221")
                && profile.minecraftProtocol() == 767
                && advertisedNamespaces.contains(REQUIRED_NAMESPACE);
    }

    byte[] contents() {
        return contents.clone();
    }

    int contentBytes() {
        return contents.length;
    }

    String contentSha256() {
        return EXPECTED_CONTENT_SHA256;
    }
}

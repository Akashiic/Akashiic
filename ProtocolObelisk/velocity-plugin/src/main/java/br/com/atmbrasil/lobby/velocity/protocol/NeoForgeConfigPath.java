package br.com.atmbrasil.lobby.velocity.protocol;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Validates the wire identifier used by NeoForge's {@code ConfigFilePayload}.
 *
 * <p>NeoForge identifies synchronized configs by their exact relative file name. Some mods,
 * including Ars Nouveau, register SERVER configs below a namespace directory. This validator
 * accepts those slash-separated identifiers, including the case-sensitive uppercase directory
 * registered by Mekanism, while deliberately rejecting filesystem traversal, absolute paths,
 * platform separators and non-canonical names. No path is ever resolved or touched on the proxy
 * filesystem.</p>
 */
public final class NeoForgeConfigPath {
    public static final int MAXIMUM_UTF8_BYTES = 128;

    private static final Pattern SAFE_RELATIVE_TOML_PATH = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9_.-]*(?:/[A-Za-z0-9][A-Za-z0-9_.-]*)*\\.toml");

    private NeoForgeConfigPath() {
    }

    public static boolean isValid(String fileName) {
        if (fileName == null
                || fileName.isEmpty()
                || fileName.length() > MAXIMUM_UTF8_BYTES
                || fileName.contains("..")
                || fileName.indexOf('\\') >= 0
                || fileName.startsWith("/")
                || fileName.endsWith("/")
                || fileName.contains("//")) {
            return false;
        }
        return fileName.getBytes(StandardCharsets.UTF_8).length <= MAXIMUM_UTF8_BYTES
                && SAFE_RELATIVE_TOML_PATH.matcher(fileName).matches();
    }
}

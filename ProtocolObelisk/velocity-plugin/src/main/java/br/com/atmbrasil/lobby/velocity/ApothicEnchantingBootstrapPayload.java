package br.com.atmbrasil.lobby.velocity;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Minimal valid PLAY bootstrap for the reviewed Apothic Enchanting 1.5.2, 1.6.0 and 1.6.1 codecs.
 *
 * <p>The reviewed client throws while its enchantment-information map is empty. Once one
 * registry-backed entry exists, its own {@code getEnchInfo} method uses the built-in fallback for
 * every other enchantment. The sentinel uses enchantment holder id zero, which is valid for every
 * Minecraft 1.21.1 registry sent by a vanilla Paper lobby. The ATM10 Normal 7.3 and 8.0 official
 * maps each contain 135 registry-dependent entries, but their bytes are not identical. The
 * reviewed sentinel intentionally avoids copying either boot-specific numeric holder map into the
 * vanilla lobby session.</p>
 */
final class ApothicEnchantingBootstrapPayload {
    static final String CHANNEL_ID = "apothic_enchanting:enchantment_info";
    static final String CHANNEL_VERSION = "1";
    static final String REQUIRED_NAMESPACE = "apothic_enchanting";
    static final String REVIEWED_MOD_VERSION = "1.21.1-1.5.2";
    static final String ATM10_NORMAL_REVIEWED_MOD_VERSION = "1.21.1-1.6.0";
    static final String ATM10_8_1_REVIEWED_MOD_VERSION = "1.21.1-1.6.1";
    static final int ATM10_NORMAL_OFFICIAL_ENTRY_COUNT = 135;
    static final int ATM10_NORMAL_OFFICIAL_PAYLOAD_BYTES = 1_643;
    static final String ATM10_NORMAL_OFFICIAL_PAYLOAD_SHA256 =
            "5b3d6b28ada63c5791add176954a13752d26b8a1dbd058ce2c51427738011224";
    static final String ATM10_NORMAL_8_0_OFFICIAL_PAYLOAD_SHA256 =
            "5f8aa1e956bb05b4ea3eb1ca34753c68b5718aac77f9cf3bd8d9a3cc4916234a";
    static final String LEGACY_SOURCE_COMMIT =
            "2822fb1209715a0896b90992725138e8d6ac64ea";
    static final String ATM10_8_1_SOURCE_COMMIT =
            "4e885c8b99f1acd09184c3fb43052a941a7cff08";

    private static final int SENTINEL_ENCHANTMENT_HOLDER_ID = 0;
    private static final byte[] PAYLOAD = createPayload();
    private static final String PAYLOAD_SHA256 = sha256(PAYLOAD);

    private ApothicEnchantingBootstrapPayload() {
    }

    static byte[] payload() {
        return PAYLOAD.clone();
    }

    static int payloadBytes() {
        return PAYLOAD.length;
    }

    static String payloadSha256() {
        return PAYLOAD_SHA256;
    }

    private static byte[] createPayload() {
        ByteArrayOutputStream output = new ByteArrayOutputStream(16);

        // EnchantmentInfoPayload.info: map<Holder<Enchantment>, EnchantmentInfo>.
        writeVarInt(output, 1);
        writeVarInt(output, SENTINEL_ENCHANTMENT_HOLDER_ID);

        // EnchantmentInfo.ench, maxLevel, maxLootLevel and disabled levelCap.
        writeVarInt(output, SENTINEL_ENCHANTMENT_HOLDER_ID);
        writeVarInt(output, 1);
        writeVarInt(output, 1);
        writeVarInt(output, -1);

        // PowerFunction.DEFAULT_MAX, followed by DEFAULT_MIN and its enchantment holder.
        output.write(1);
        output.write(0);
        writeVarInt(output, SENTINEL_ENCHANTMENT_HOLDER_ID);
        return output.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        int remaining = value;
        do {
            int next = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                next |= 0x80;
            }
            output.write(next);
        } while (remaining != 0);
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}

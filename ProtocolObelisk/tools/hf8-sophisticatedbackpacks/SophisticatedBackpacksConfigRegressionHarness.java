package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

/** Focused executable regression checks against actual HF7/HF8 plugin classes. */
public final class SophisticatedBackpacksConfigRegressionHarness {
    private static final String TARGET = "sophisticatedbackpacks-server.toml";
    private static final String LOADER = "neoforge-server.toml";
    private static final String CREATE = "create-server.toml";
    private static final String HYPERTUBE = "create_hypertube-server.toml";
    private static int checks;

    private SophisticatedBackpacksConfigRegressionHarness() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("hf7") || args[0].equals("hf8"))) {
            throw new IllegalArgumentException("usage: hf7|hf8 evidence-directory");
        }
        boolean patched = args[0].equals("hf8");
        Path evidence = Path.of(args[1]);
        Files.createDirectories(evidence);
        Path dir = Files.createTempDirectory("protocolobelisk-sbp-config-qa-");
        BridgeConfig defaults = BridgeConfig.load(dir);
        byte[] template = Files.readAllBytes(dir.resolve("bridge.properties"));
        List<String> names = defaults.transientServerConfigs();

        check(names.contains(TARGET) == patched, "baseline Sophisticated Backpacks presence");
        check(names.size() == (patched ? 20 : 19), "fresh baseline exact count");
        check(names.stream().filter(TARGET::equals).count() == (patched ? 1 : 0),
                "baseline target uniqueness");
        check(names.stream().filter(LOADER::equals).count() == 1, "NeoForge config retained once");
        check(names.stream().filter(CREATE::equals).count() == 1, "Create config retained once");
        check(names.stream().filter(HYPERTUBE::equals).count() == 1, "Hypertube config retained once");
        check(names.subList(0, 3).equals(List.of(LOADER, CREATE, HYPERTUBE)),
                "existing NeoForge/Create/Hypertube order retained");
        if (patched) {
            check(names.get(3).equals(TARGET), "Sophisticated Backpacks follows existing dependency trio");
        }
        check(!defaults.enabled(), "fresh config remains disabled");
        check(defaults.expectedMinecraftProtocol() == 767, "protocol unchanged");
        check(defaults.legacyForgeHandoffTargets().equals(List.of("forbidden-1")),
                "legacy routing unchanged");

        String templateText = new String(template, StandardCharsets.UTF_8);
        check(templateText.contains("enable-built-in-atm-server-configs=true"),
                "template built-in switch retained");
        check(templateText.contains(TARGET) == patched, "template advertises target only when patched");

        BridgeConfig noBuiltins = variant(template, p -> {
            p.setProperty("enable-built-in-atm-server-configs", "false");
            p.setProperty("enable-built-in-mekanism-compatibility", "false");
            p.setProperty("transient-server-configs", "");
        });
        check(noBuiltins.transientServerConfigs().isEmpty(), "operator full opt-out preserved");

        BridgeConfig manual = variant(template, p -> {
            p.setProperty("enable-built-in-atm-server-configs", "false");
            p.setProperty("enable-built-in-mekanism-compatibility", "false");
            p.setProperty("transient-server-configs", TARGET);
        });
        check(manual.transientServerConfigs().equals(List.of(TARGET)), "manual opt-in remains exact");

        BridgeConfig noMekanism = variant(template, p -> {
            p.setProperty("enable-built-in-mekanism-compatibility", "false");
            p.setProperty("transient-server-configs", "");
        });
        check(noMekanism.transientServerConfigs().size() == (patched ? 10 : 9),
                "Mekanism opt-out independent of target");

        TransientServerConfigPlanner.Plan fallback = TransientServerConfigPlanner.plan(
                names, List.of("sophisticatedbackpacks", "sophisticatedbackpacks", "unknown_mod"), false, 512);
        check(fallback.configs().equals(names), "profile-free fallback equals baseline");
        check(fallback.derivedConfigCount() == 0, "no arbitrary derivation when disabled");
        check(!fallback.reviewedCatalogApplied(), "fallback never impersonates exact catalog");
        check(fallback.configs().contains(TARGET) == patched, "planner reproduces HF7 omission/HF8 fix");
        check(!fallback.configs().contains("unknown_mod-server.toml"), "unknown config not fabricated");

        TransientServerConfigPlanner.Plan derived = TransientServerConfigPlanner.plan(
                names, List.of("sophisticatedbackpacks", "sophisticatedbackpacks"), true, 1);
        check(derived.configs().stream().filter(TARGET::equals).count() == 1,
                "namespace derivation cannot duplicate target");
        check(derived.derivedConfigCount() == (patched ? 0 : 1),
                "HF8 built-in target does not consume derivation slot");

        Atm10Normal81ServerConfigCatalog.Catalog catalog = Atm10Normal81ServerConfigCatalog.catalog();
        TransientServerConfigPlanner.Plan exact = TransientServerConfigPlanner.withReviewedContractCatalog(
                fallback, 767, Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                Optional.of(catalog));
        check(exact.reviewedCatalogApplied(), "exact 8.1 catalog selection preserved");
        check(exact.configs().equals(catalog.fileNames()), "exact 8.1 wire order preserved");
        check(exact.configs().size() == 288, "exact 8.1 remains 288 configs");
        check(exact.configs().stream().filter(TARGET::equals).count() == 1,
                "exact 8.1 already contains Sophisticated Backpacks once");
        check(exact.reviewedCatalogEncodedBytes() == 651792, "exact 8.1 encoded bytes unchanged");
        check(exact.reviewedCatalogPayloadSequenceSha256().equals(
                "305e26b71ea175cb8e527d498dd51b61ca6e62951f533bc57f5cdbe6ed410521"),
                "exact 8.1 payload hash unchanged");
        check(TransientServerConfigPlanner.withReviewedContractCatalog(
                fallback, 767, "0".repeat(64), Optional.of(catalog)) == fallback,
                "unknown contract cannot borrow 8.1 catalog");
        check(TransientServerConfigPlanner.withReviewedContractCatalog(
                fallback, 5, Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                Optional.of(catalog)) == fallback,
                "legacy protocol cannot borrow 8.1 catalog");

        byte[] targetPacket = NeoForgeHandshakeCodec.encodeConfigFilePayload(TARGET, new byte[0], 36);
        check(targetPacket.length == 36, "target empty config payload is 36 bytes");
        check((targetPacket[0] & 255) == 34, "target filename VarInt length exact");
        check(new String(targetPacket, 1, 34, StandardCharsets.UTF_8).equals(TARGET),
                "target filename exact on wire");
        check(targetPacket[35] == 0, "target TOML payload stays empty");
        expectFailure(() -> NeoForgeHandshakeCodec.encodeConfigFilePayload(TARGET, new byte[0], 35),
                "target payload bound enforced");
        expectFailure(() -> NeoForgeHandshakeCodec.encodeConfigFilePayload("../" + TARGET, new byte[0], 1024),
                "path traversal rejected");
        Files.write(evidence.resolve(args[0] + "-target-config-payload.bin"), targetPacket);

        List<String> observed = List.of(
                "neoforge-server.toml", "create-server.toml", "create_hypertube-server.toml",
                "securitycraft-server.toml", "productivefarming-server.toml", "bhc-server.toml",
                "utilitarian-server.toml", "ars_nouveau/rewind.toml", "matc-server.toml",
                "Mekanism/general.toml", "Mekanism/gear.toml", "Mekanism/machine-storage.toml",
                "Mekanism/tiers.toml", "Mekanism/machine-usage.toml", "Mekanism/world.toml",
                "Mekanism/generators.toml", "Mekanism/generators-gear.toml",
                "Mekanism/generator-storage.toml", "Mekanism/tools.toml",
                "xycraft/core-server.toml", "xycraft/machines-server.toml");
        BridgeConfig incident = variant(template,
                p -> p.setProperty("transient-server-configs", String.join(",", observed)));
        List<String> incidentNames = incident.transientServerConfigs();
        check(incidentNames.size() == (patched ? 22 : 21), "observed HF7 21 to HF8 22 migration");
        check(incidentNames.contains(TARGET) == patched, "observed session gains only target");
        List<String> withoutTarget = incidentNames.stream().filter(n -> !n.equals(TARGET)).toList();
        check(withoutTarget.equals(observed), "all 21 observed names and relative order retained");
        if (patched) {
            check(incidentNames.subList(0, 4).equals(List.of(LOADER, CREATE, HYPERTUBE, TARGET)),
                    "actual incident dependency prefix exact");
        }
        TransientServerConfigPlanner.Plan incidentPlan = TransientServerConfigPlanner.plan(
                incidentNames, List.of("sophisticatedbackpacks", "sophisticatedcore"), false, 512);
        check(incidentPlan.configs().equals(incidentNames), "incident no-profile plan retains corrected baseline");
        check(incidentPlan.derivedConfigCount() == 0, "incident plan does not broaden derivation");
        check(!incidentPlan.reviewedCatalogApplied(), "incident plan does not impersonate 8.1");
        check(TransientServerConfigPlanner.withReviewedContractCatalog(
                incidentPlan, 767,
                "65ddebdbecf3a09d57c2fcc3ce39099df539fdf9260181c1e31b90afdc3f9577",
                Optional.of(catalog)) == incidentPlan,
                "actual 8.2 contract still cannot borrow exact 8.1 config catalog");

        BridgeConfig explicitOptOut = variant(template, p -> {
            p.setProperty("transient-server-configs", String.join(",", observed));
            p.setProperty("enable-built-in-atm-server-configs", "false");
        });
        check(explicitOptOut.transientServerConfigs().size() == observed.size()
                && explicitOptOut.transientServerConfigs().containsAll(observed),
                "existing operator opt-out retains the same observed config set");
        check(!explicitOptOut.transientServerConfigs().contains(TARGET),
                "operator opt-out does not force target");

        BridgeConfig manualTarget = variant(template, p -> {
            p.setProperty("transient-server-configs", String.join(",", observed) + "," + TARGET);
            p.setProperty("enable-built-in-atm-server-configs", "false");
        });
        check(manualTarget.transientServerConfigs().size() == 22, "manual target opt-in supported");
        check(manualTarget.transientServerConfigs().stream().filter(TARGET::equals).count() == 1,
                "manual target never duplicated");

        expectFailure(() -> variant(template,
                p -> p.setProperty("transient-server-configs", TARGET + "," + TARGET)),
                "duplicate operator target rejected");
        expectFailure(() -> variant(template,
                p -> p.setProperty("transient-server-configs", "../" + TARGET)),
                "invalid operator target rejected");

        int available = 128 - names.size();
        List<String> custom = new ArrayList<>();
        for (int i = 0; i < available; i++) { custom.add("custom" + i + "-server.toml"); }
        BridgeConfig atLimit = variant(template,
                p -> p.setProperty("transient-server-configs", String.join(",", custom)));
        check(atLimit.transientServerConfigs().size() == 128, "merged 128 config limit remains valid");
        custom.add("over-limit-server.toml");
        expectFailure(() -> variant(template,
                p -> p.setProperty("transient-server-configs", String.join(",", custom))),
                "merged 129 config limit rejected");

        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        int encodedBytes = 0;
        for (String fileName : incidentPlan.configs()) {
            byte[] payload = NeoForgeHandshakeCodec.encodeConfigFilePayload(fileName, new byte[0], 1_048_576);
            all.writeBytes(payload);
            encodedBytes += payload.length;
            check(payload[payload.length - 1] == 0, "empty config content for " + fileName);
        }
        byte[] fullPayload = all.toByteArray();
        check(decodeNames(fullPayload).equals(incidentNames), "independent decoder sees exact config sequence");
        Files.write(evidence.resolve(args[0] + "-incident-config-payloads.bin"), fullPayload);
        Files.write(evidence.resolve(args[0] + "-incident-config-names.txt"), incidentNames, StandardCharsets.UTF_8);
        Files.writeString(evidence.resolve(args[0] + "-incident-config-bytes.txt"), encodedBytes + "\n");

        check(!Files.exists(dir.resolve(TARGET)), "proxy never persists target TOML");
        check(!new String(targetPacket, StandardCharsets.UTF_8).contains("leatherWeight"),
                "patch does not inject Sophisticated Backpacks gameplay values");
        check(TransientServerConfigPlanner.exact(List.of("backend/custom.toml"))
                .configs().equals(List.of("backend/custom.toml")),
                "exact backend planner never inherits lobby baseline");

        Files.writeString(evidence.resolve(args[0] + "-default-config-count.txt"), names.size() + "\n");
        System.out.println("MODE=" + args[0] + " CHECKS=" + checks + " RESULT=PASS");
    }

    private static List<String> decodeNames(byte[] bytes) {
        java.nio.ByteBuffer input = java.nio.ByteBuffer.wrap(bytes);
        List<String> names = new ArrayList<>();
        while (input.hasRemaining()) {
            int n = varInt(input);
            if (n < 1 || n > 128 || n > input.remaining()) {
                throw new AssertionError("invalid config filename length");
            }
            byte[] name = new byte[n];
            input.get(name);
            if (varInt(input) != 0) { throw new AssertionError("unexpected injected config content"); }
            names.add(new String(name, StandardCharsets.UTF_8));
        }
        return List.copyOf(names);
    }

    private static int varInt(java.nio.ByteBuffer input) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (!input.hasRemaining()) { throw new AssertionError("truncated VarInt"); }
            int b = Byte.toUnsignedInt(input.get());
            value |= (b & 127) << (i * 7);
            if ((b & 128) == 0) { return value; }
        }
        throw new AssertionError("oversized VarInt");
    }

    private static BridgeConfig variant(byte[] template, Consumer<Properties> change) throws Exception {
        Path dir = Files.createTempDirectory("protocolobelisk-sbp-config-variant-");
        Properties properties = new Properties();
        properties.load(new java.io.ByteArrayInputStream(template));
        change.accept(properties);
        Path path = dir.resolve("bridge.properties");
        try (OutputStream out = Files.newOutputStream(path)) { properties.store(out, "QA fixture"); }
        byte[] before = Files.readAllBytes(path);
        BridgeConfig config = BridgeConfig.load(dir);
        if (!Arrays.equals(before, Files.readAllBytes(path))) {
            throw new AssertionError("operator configuration was rewritten");
        }
        return config;
    }

    private static void check(boolean condition, String label) {
        if (!condition) { throw new AssertionError(label); }
        checks++;
        System.out.println("PASS " + label);
    }

    private static void expectFailure(ThrowingAction action, String label) throws Exception {
        try { action.run(); }
        catch (IllegalArgumentException | ProtocolViolationException expected) {
            check(true, label);
            return;
        }
        throw new AssertionError(label + ": did not reject invalid input");
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }
}

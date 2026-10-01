package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.io.OutputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

/** Executable focused tests against actual HF5/HF6 classes; no API or JUnit stubs. */
public final class CreateConfigRegressionHarness {
    private static final String TARGET = "create-server.toml";
    private static final String ADDON = "create_hypertube-server.toml";
    private static final String LOADER = "neoforge-server.toml";
    private static int checks;

    private CreateConfigRegressionHarness() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("hf5") || args[0].equals("hf6"))) {
            throw new IllegalArgumentException("usage: hf5|hf6 evidence-directory");
        }
        boolean patched = args[0].equals("hf6");
        Path evidence = Path.of(args[1]);
        Files.createDirectories(evidence);
        Path dir = Files.createTempDirectory("protocolobelisk-coreconfig-qa-");
        BridgeConfig defaults = BridgeConfig.load(dir);
        byte[] template = Files.readAllBytes(dir.resolve("bridge.properties"));
        List<String> names = defaults.transientServerConfigs();
        check(names.contains(TARGET) == patched, "baseline Create presence");
        check(names.size() == (patched ? 19 : 18), "baseline exact config count");
        check(names.stream().filter(TARGET::equals).count() == (patched ? 1 : 0),
                "baseline no Create duplicates");
        check(!defaults.enabled(), "fresh configuration remains disabled");
        check(defaults.expectedMinecraftProtocol() == 767, "base protocol unchanged");
        check(defaults.legacyForgeHandoffTargets().equals(List.of("forbidden-1")),
                "legacy handoff configuration unchanged");
        check(defaults.allowVanillaLobby(), "vanilla setting unchanged");
        check(names.getFirst().equals(LOADER), "NeoForge loader remains first in default fallback");

        List<String> snapshot = new ArrayList<>();
        for (RecordComponent component : BridgeConfig.class.getRecordComponents()) {
            snapshot.add(component.getName() + "=" + component.getAccessor().invoke(defaults));
        }
        Files.write(evidence.resolve(args[0] + "-config-snapshot.txt"), snapshot,
                StandardCharsets.UTF_8);
        Files.write(evidence.resolve(args[0] + "-bridge.properties"), template);

        BridgeConfig again = BridgeConfig.load(dir);
        check(again.equals(defaults), "reload same immutable configuration");
        check(Arrays.equals(template, Files.readAllBytes(dir.resolve("bridge.properties"))),
                "existing file never rewritten on load");

        BridgeConfig blank = variant(template, p -> p.setProperty("transient-server-configs", ""));
        check(blank.transientServerConfigs().equals(names), "old v4 blank receives built-ins");
        BridgeConfig absent = variant(template, p -> p.remove("transient-server-configs"));
        check(absent.transientServerConfigs().equals(names), "old v4 absent key receives built-ins");
        BridgeConfig listed = variant(template, p -> p.setProperty("transient-server-configs", TARGET));
        check(listed.transientServerConfigs().stream().filter(TARGET::equals).count() == 1,
                "operator Create entry deduplicates with built-in");
        check(listed.transientServerConfigs().containsAll(names), "operator entry retains other built-ins");
        BridgeConfig disabled = variant(template, p -> {
            p.setProperty("transient-server-configs", "");
            p.setProperty("enable-built-in-atm-server-configs", "false");
            p.setProperty("enable-built-in-mekanism-compatibility", "false");
        });
        check(disabled.transientServerConfigs().isEmpty(), "explicit operator opt-out respected");
        BridgeConfig manual = variant(template, p -> {
            p.setProperty("transient-server-configs", TARGET);
            p.setProperty("enable-built-in-atm-server-configs", "false");
            p.setProperty("enable-built-in-mekanism-compatibility", "false");
        });
        check(manual.transientServerConfigs().equals(List.of(TARGET)), "manual opt-in is exactly one file");
        BridgeConfig noMekanism = variant(template, p -> {
            p.setProperty("transient-server-configs", "");
            p.setProperty("enable-built-in-mekanism-compatibility", "false");
        });
        check(noMekanism.transientServerConfigs().size() == (patched ? 9 : 8),
                "Mekanism opt-out still independent");

        TransientServerConfigPlanner.Plan fallback = TransientServerConfigPlanner.plan(
                names, List.of("neoforge", "ars_nouveau", "unknown_mod"), false, 512);
        check(fallback.configs().equals(names), "profile-free fallback equals baseline");
        check(fallback.derivedConfigCount() == 0, "no arbitrary namespace guesses enabled");
        check(!fallback.reviewedCatalogApplied(), "fallback does not impersonate exact catalog");
        check(!fallback.configs().contains("unknown_mod-server.toml"), "unknown mod not fabricated");
        check(fallback.configs().contains(TARGET) == patched, "HF5 omission/HF6 correction at planner");
        check(fallback.equals(TransientServerConfigPlanner.plan(names, List.of(), false, 512)),
                "profile-free plan deterministic across repeated calls");
        TransientServerConfigPlanner.Plan derived = TransientServerConfigPlanner.plan(
                names, List.of("create", "create"), true, 1);
        check(derived.configs().stream().filter(TARGET::equals).count() == 1,
                "derivation cannot duplicate Create");
        check(derived.derivedConfigCount() == (patched ? 0 : 1), "Create no longer consumes derivation slot");

        Atm10Normal81ServerConfigCatalog.Catalog catalog = Atm10Normal81ServerConfigCatalog.catalog();
        TransientServerConfigPlanner.Plan exact = TransientServerConfigPlanner.withReviewedContractCatalog(
                fallback, 767, Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                Optional.of(catalog));
        check(exact.reviewedCatalogApplied(), "exact 8.1 catalog selection preserved");
        check(exact.configs().equals(catalog.fileNames()), "exact 8.1 wire order preserved");
        check(exact.configs().size() == 288, "exact 8.1 remains 288 files");
        check(exact.configs().stream().filter(TARGET::equals).count() == 1,
                "exact 8.1 contains Create exactly once");
        check(exact.reviewedCatalogEncodedBytes() == 651792, "exact 8.1 payload total unchanged");
        check(exact.reviewedCatalogPayloadSequenceSha256().equals(
                "305e26b71ea175cb8e527d498dd51b61ca6e62951f533bc57f5cdbe6ed410521"),
                "exact 8.1 payload digest unchanged");
        check(TransientServerConfigPlanner.withReviewedContractCatalog(
                fallback, 767, "0".repeat(64), Optional.of(catalog)) == fallback,
                "unknown contract cannot borrow 8.1 catalog");
        check(TransientServerConfigPlanner.withReviewedContractCatalog(
                fallback, 5, Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                Optional.of(catalog)) == fallback, "legacy protocol cannot borrow 8.1 catalog");
        check(TransientServerConfigPlanner.withReviewedContractCatalog(
                fallback, 767, Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                Optional.empty()) == fallback, "missing catalog leaves admission plan intact");
        check(TransientServerConfigPlanner.withReviewedContractCatalog(
                exact, 767, Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                Optional.of(catalog)) == exact, "catalog application idempotent");

        byte[] corePacket = NeoForgeHandshakeCodec.encodeConfigFilePayload(TARGET, new byte[0], 20);
        check(corePacket.length == 20, "Create wire payload bounded at 20 bytes");
        check((corePacket[0] & 255) == 18, "Create filename length is canonical VarInt");
        check(new String(corePacket, 1, 18, StandardCharsets.UTF_8).equals(TARGET),
                "Create filename exact case on wire");
        check(corePacket[19] == 0, "empty TOML uses client defaults, no injected config values");
        expectFailure(() -> NeoForgeHandshakeCodec.encodeConfigFilePayload(TARGET, new byte[0], 19),
                "wire budget enforced");
        expectFailure(() -> NeoForgeHandshakeCodec.encodeConfigFilePayload("../" + TARGET,
                new byte[0], 1024), "wire traversal rejected");
        Files.write(evidence.resolve(args[0] + "-core-config-payload.bin"), corePacket);

        Registry lineage = new Registry(Map.of(1, List.of(
                new Channel("ad_astra:sync", "1", Flow.SERVERBOUND, false),
                new Channel("logisticsnetworks:sync_modifier_keys", "9", Flow.SERVERBOUND, false))));
        check(Atm10Normal82Contract.matchesGiselleRegistryMergeEvidence(767, lineage),
                "HF3 Giselle lineage evidence still accepted");
        check(!Atm10Normal82Contract.matchesGiselleRegistryMergeEvidence(5, lineage),
                "HF3 Giselle does not leak to legacy");
        Registry oldLineage = new Registry(Map.of(1, List.of(
                new Channel("ad_astra:sync", "1", Flow.SERVERBOUND, false),
                new Channel("logisticsnetworks:sync_modifier_keys", "1", Flow.SERVERBOUND, false))));
        check(!Atm10Normal82Contract.matchesGiselleRegistryMergeEvidence(767, oldLineage),
                "HF3 v1/v9 distinction preserved");

        expectFailure(() -> variant(template, p -> p.setProperty("transient-server-configs", TARGET + "," + TARGET)),
                "duplicate operator filenames still rejected");
        expectFailure(() -> variant(template, p -> p.setProperty("transient-server-configs", "../" + TARGET)),
                "operator traversal still rejected");
        expectFailure(() -> variant(template, p -> p.setProperty("transient-server-configs", TARGET + ",")),
                "empty operator entry still rejected");
        int available = 128 - names.size();
        List<String> custom = new ArrayList<>();
        for (int i = 0; i < available; i++) { custom.add("custom" + i + "-server.toml"); }
        BridgeConfig atLimit = variant(template, p -> p.setProperty("transient-server-configs", String.join(",", custom)));
        check(atLimit.transientServerConfigs().size() == 128, "merged maximum 128 remains valid");
        custom.add("over-limit-server.toml");
        expectFailure(() -> variant(template, p -> p.setProperty("transient-server-configs", String.join(",", custom))),
                "merged maximum 129 still rejected");
        check(!Files.exists(dir.resolve(TARGET)), "no server TOML created beside operator config");
        check(names.contains(LOADER), "HF4 loader fix retained");
        List<String> observed = List.of("neoforge-server.toml", "create_hypertube-server.toml", "securitycraft-server.toml",
                "productivefarming-server.toml", "bhc-server.toml", "utilitarian-server.toml",
                "ars_nouveau/rewind.toml", "matc-server.toml", "Mekanism/general.toml",
                "Mekanism/gear.toml", "Mekanism/machine-storage.toml", "Mekanism/tiers.toml",
                "Mekanism/machine-usage.toml", "Mekanism/world.toml", "Mekanism/generators.toml",
                "Mekanism/generators-gear.toml", "Mekanism/generator-storage.toml", "Mekanism/tools.toml",
                "xycraft/core-server.toml", "xycraft/machines-server.toml");
        BridgeConfig incident = variant(template,
                p -> p.setProperty("transient-server-configs", String.join(",", observed)));
        List<String> incidentNames = incident.transientServerConfigs();
        check(incidentNames.size() == (patched ? 21 : 20), "observed operator 20 to 21 migration");
        check(incidentNames.stream().filter(name -> !name.equals(TARGET)).toList().equals(observed), "all observed filenames and order retained");
        check(incidentNames.contains(TARGET) == patched, "Create supplied with existing v4 config");
        TransientServerConfigPlanner.Plan incidentPlan = TransientServerConfigPlanner.plan(
                incidentNames, List.of("create", "create_hypertube", "neoforge"), false, 512);
        check(incidentPlan.configs().equals(incidentNames), "observed no-profile plan retains corrected baseline");
        check(incidentPlan.derivedConfigCount() == 0, "observed session does not broaden namespace derivation");
        check(!incidentPlan.reviewedCatalogApplied(), "observed session does not impersonate 8.1");
        check(TransientServerConfigPlanner.withReviewedContractCatalog(incidentPlan, 767,
                "65ddebdbecf3a09d57c2fcc3ce39099df539fdf9260181c1e31b90afdc3f9577",
                Optional.of(catalog)) == incidentPlan, "actual 8.2 contract cannot borrow 8.1 catalog");
        check(exact.reviewedCatalogNameSequenceSha256().equals(
                "333cacc99f7dba60cd852804301cc3f48e2b1d78a697cf316bda3eaed6a07127"),
                "exact 8.1 filename sequence hash unchanged");
        check(incidentPlan.configs().stream().filter(LOADER::equals).count() == 1,
                "observed session loader config appears once");
        BridgeConfig explicitOptOut = variant(template, p -> {
            p.setProperty("transient-server-configs", String.join(",", observed));
            p.setProperty("enable-built-in-atm-server-configs", "false");
        });
        check(explicitOptOut.transientServerConfigs().size() == observed.size()
                && explicitOptOut.transientServerConfigs().containsAll(observed)
                && !explicitOptOut.transientServerConfigs().contains(TARGET), "existing explicit operator opt-out retained");
        BridgeConfig manualTarget = variant(template, p -> {
            p.setProperty("transient-server-configs", String.join(",", observed) + "," + TARGET);
            p.setProperty("enable-built-in-atm-server-configs", "false");
        });
        check(manualTarget.transientServerConfigs().size() == 21, "operator can opt in without built-ins");
        check(manualTarget.transientServerConfigs().stream().filter(TARGET::equals).count() == 1,
                "manual config not duplicated");
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        int encodedBytes = 0;
        for (String fileName : incidentPlan.configs()) {
            byte[] payload = NeoForgeHandshakeCodec.encodeConfigFilePayload(fileName, new byte[0], 1_048_576);
            all.writeBytes(payload);
            encodedBytes += payload.length;
            check(payload[payload.length - 1] == 0, "empty config content for " + fileName);
        }
        Files.write(evidence.resolve(args[0] + "-observed-config-payloads.bin"), all.toByteArray());
        Files.write(evidence.resolve(args[0] + "-observed-config-names.txt"), incidentPlan.configs());
        Files.writeString(evidence.resolve(args[0] + "-observed-config-bytes.txt"), encodedBytes + "\n");
        check(java.util.Arrays.equals(corePacket,
                NeoForgeHandshakeCodec.encodeConfigFilePayload(TARGET, new byte[0], 1_048_576)),
                "actual wire encoding stable independent of maximum allowed size");
        check(!new String(corePacket, StandardCharsets.UTF_8).contains("syncPlayerPickupHitboxWithContraptionHitbox"),
                "patch does not override Create hitbox behavior");
        check(!Files.exists(dir.resolve(TARGET)), "Create TOML not persisted beside proxy config");
        check(!Files.exists(dir.resolve(LOADER)), "NeoForge TOML not persisted beside proxy config");
        check(names.stream().filter(ADDON::equals).count() == 1,
                "HF5 Hypertube fix retained exactly once");
        check(incidentNames.stream().filter(ADDON::equals).count() == 1,
                "actual operator Hypertube fix retained exactly once");
        if (patched) {
            check(names.subList(0, 3).equals(List.of(LOADER, TARGET, ADDON)),
                    "reviewed dependency coverage: NeoForge then Create then Hypertube");
            BridgeConfig reversed = variant(template, p -> p.setProperty(
                    "transient-server-configs", ADDON + "," + TARGET + "," + LOADER));
            check(reversed.transientServerConfigs().equals(names),
                    "built-in dependency order survives reversed operator declaration");
        }
        check(exact.configs().stream().filter(LOADER::equals).count() == 1
                && exact.configs().stream().filter(ADDON::equals).count() == 1,
                "exact catalog retains NeoForge and addon once");
        check(TransientServerConfigPlanner.exact(List.of("backend/custom.toml"))
                .configs().equals(List.of("backend/custom.toml")),
                "exact backend config planner never inherits lobby baseline");
        check(!fallback.configs().contains("create-client.toml")
                && !fallback.configs().contains("create-common.toml"),
                "no client or common Create config injected");
        check(NeoForgeHandshakeCodec.encodeConfigFilePayload(LOADER,
                new byte[0], 1024).length == 22, "loader payload remains 22 bytes");
        check(NeoForgeHandshakeCodec.encodeConfigFilePayload(ADDON,
                new byte[0], 1024).length == 30, "Hypertube payload remains 30 bytes");
        check(NeoForgeHandshakeCodec.encodeConfigFilePayload(TARGET,
                new byte[0], 1024).length == 20, "base Create payload is 20 bytes");
        byte[] fullPayload = all.toByteArray();
        check(decodeNames(fullPayload).equals(incidentNames),
                "independent decoder validates every config envelope and zero content");
        check(!Files.exists(dir.resolve(ADDON)), "addon config not persisted by proxy");
        byte[] nonempty = "[kinetics]\nvalue=7\n".getBytes(StandardCharsets.UTF_8);
        byte[] nonemptyCopy = nonempty.clone();
        byte[] encoded = NeoForgeHandshakeCodec.encodeConfigFilePayload(TARGET, nonempty, 1024);
        check(Arrays.equals(nonempty, nonemptyCopy), "config encoder does not mutate content input");
        check(encoded.length == 20 + nonempty.length, "nonempty config encoding remains supported");
        check(TransientServerConfigPlanner.withReviewedContractCatalog(fallback, 768,
                Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                Optional.of(catalog)) == fallback, "wrong modern protocol cannot borrow exact catalog");
        check(defaults.registryShims().equals(again.registryShims()),
                "registry choices remain stable across config reload");
        check(defaults.pinnedPlaySinkChannels().stream().anyMatch(p ->
                p.id().equals("logisticsnetworks:sync_modifier_keys") && p.version().equals("9")),
                "HF3 LogisticsNetworks v9 pin preserved");
        System.out.println("MODE=" + args[0] + " CHECKS=" + checks + " RESULT=PASS");
        System.out.println("SCOPE=REAL_BRIDGE_CONFIG_AND_PURE_JAVA_PLANNING_WIRE_ONLY");
        System.out.println("NOT_RUN=REAL_CLIENT,VELOCITY_PAPER_RUNTIME,NETTY_JUNIT_FULL_SUITE");
    }

    /** Independent decoder of the concatenated ConfigFile payloads, without the production decoder. */
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
            if (varInt(input) != 0) { throw new AssertionError("unexpected injected config value"); }
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
        Path dir = Files.createTempDirectory("protocolobelisk-coreconfig-variant-");
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

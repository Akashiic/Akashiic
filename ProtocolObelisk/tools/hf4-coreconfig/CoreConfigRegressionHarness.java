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

/** Executable focused tests against actual HF3/HF4 classes; no API or JUnit stubs. */
public final class CoreConfigRegressionHarness {
    private static final String CORE = "neoforge-server.toml";
    private static int checks;

    private CoreConfigRegressionHarness() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("hf3") || args[0].equals("hf4"))) {
            throw new IllegalArgumentException("usage: hf3|hf4 evidence-directory");
        }
        boolean patched = args[0].equals("hf4");
        Path evidence = Path.of(args[1]);
        Files.createDirectories(evidence);
        Path dir = Files.createTempDirectory("protocolobelisk-coreconfig-qa-");
        BridgeConfig defaults = BridgeConfig.load(dir);
        byte[] template = Files.readAllBytes(dir.resolve("bridge.properties"));
        List<String> names = defaults.transientServerConfigs();
        check(names.contains(CORE) == patched, "baseline core presence");
        check(names.size() == (patched ? 17 : 16), "baseline exact config count");
        check(names.stream().filter(CORE::equals).count() == (patched ? 1 : 0),
                "baseline no core duplicates");
        check(!defaults.enabled(), "fresh configuration remains disabled");
        check(defaults.expectedMinecraftProtocol() == 767, "base protocol unchanged");
        check(defaults.legacyForgeHandoffTargets().equals(List.of("forbidden-1")),
                "legacy handoff configuration unchanged");
        check(defaults.allowVanillaLobby(), "vanilla setting unchanged");
        check(!patched || names.getFirst().equals(CORE), "core is first in fallback");

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
        BridgeConfig listed = variant(template, p -> p.setProperty("transient-server-configs", CORE));
        check(listed.transientServerConfigs().stream().filter(CORE::equals).count() == 1,
                "operator core entry deduplicates with built-in");
        check(listed.transientServerConfigs().containsAll(names), "operator entry retains other built-ins");
        BridgeConfig disabled = variant(template, p -> {
            p.setProperty("transient-server-configs", "");
            p.setProperty("enable-built-in-atm-server-configs", "false");
            p.setProperty("enable-built-in-mekanism-compatibility", "false");
        });
        check(disabled.transientServerConfigs().isEmpty(), "explicit operator opt-out respected");
        BridgeConfig manual = variant(template, p -> {
            p.setProperty("transient-server-configs", CORE);
            p.setProperty("enable-built-in-atm-server-configs", "false");
            p.setProperty("enable-built-in-mekanism-compatibility", "false");
        });
        check(manual.transientServerConfigs().equals(List.of(CORE)), "manual opt-in is exactly one file");
        BridgeConfig noMekanism = variant(template, p -> {
            p.setProperty("transient-server-configs", "");
            p.setProperty("enable-built-in-mekanism-compatibility", "false");
        });
        check(noMekanism.transientServerConfigs().size() == (patched ? 7 : 6),
                "Mekanism opt-out still independent");

        TransientServerConfigPlanner.Plan fallback = TransientServerConfigPlanner.plan(
                names, List.of("neoforge", "ars_nouveau", "unknown_mod"), false, 512);
        check(fallback.configs().equals(names), "profile-free fallback equals baseline");
        check(fallback.derivedConfigCount() == 0, "no arbitrary namespace guesses enabled");
        check(!fallback.reviewedCatalogApplied(), "fallback does not impersonate exact catalog");
        check(!fallback.configs().contains("unknown_mod-server.toml"), "unknown mod not fabricated");
        check(fallback.configs().contains(CORE) == patched, "HF3 omission/HF4 correction at planner");
        check(fallback.equals(TransientServerConfigPlanner.plan(names, List.of(), false, 512)),
                "profile-free plan deterministic across repeated calls");
        TransientServerConfigPlanner.Plan derived = TransientServerConfigPlanner.plan(
                names, List.of("neoforge", "neoforge"), true, 1);
        check(derived.configs().stream().filter(CORE::equals).count() == 1,
                "derivation cannot duplicate core");
        check(derived.derivedConfigCount() == (patched ? 0 : 1), "core no longer consumes derivation slot");

        Atm10Normal81ServerConfigCatalog.Catalog catalog = Atm10Normal81ServerConfigCatalog.catalog();
        TransientServerConfigPlanner.Plan exact = TransientServerConfigPlanner.withReviewedContractCatalog(
                fallback, 767, Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256,
                Optional.of(catalog));
        check(exact.reviewedCatalogApplied(), "exact 8.1 catalog selection preserved");
        check(exact.configs().equals(catalog.fileNames()), "exact 8.1 wire order preserved");
        check(exact.configs().size() == 288, "exact 8.1 remains 288 files");
        check(exact.configs().stream().filter(CORE::equals).count() == 1,
                "exact 8.1 contains core exactly once");
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

        byte[] corePacket = NeoForgeHandshakeCodec.encodeConfigFilePayload(CORE, new byte[0], 22);
        check(corePacket.length == 22, "core wire payload bounded at 22 bytes");
        check((corePacket[0] & 255) == 20, "core filename length is canonical VarInt");
        check(new String(corePacket, 1, 20, StandardCharsets.UTF_8).equals(CORE),
                "core filename exact case on wire");
        check(corePacket[21] == 0, "empty TOML uses client defaults, no injected config values");
        expectFailure(() -> NeoForgeHandshakeCodec.encodeConfigFilePayload(CORE, new byte[0], 21),
                "wire budget enforced");
        expectFailure(() -> NeoForgeHandshakeCodec.encodeConfigFilePayload("../" + CORE,
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

        expectFailure(() -> variant(template, p -> p.setProperty("transient-server-configs", CORE + "," + CORE)),
                "duplicate operator filenames still rejected");
        expectFailure(() -> variant(template, p -> p.setProperty("transient-server-configs", "../" + CORE)),
                "operator traversal still rejected");
        expectFailure(() -> variant(template, p -> p.setProperty("transient-server-configs", CORE + ",")),
                "empty operator entry still rejected");
        int available = 128 - names.size();
        List<String> custom = new ArrayList<>();
        for (int i = 0; i < available; i++) { custom.add("custom" + i + "-server.toml"); }
        BridgeConfig atLimit = variant(template, p -> p.setProperty("transient-server-configs", String.join(",", custom)));
        check(atLimit.transientServerConfigs().size() == 128, "merged maximum 128 remains valid");
        custom.add("over-limit-server.toml");
        expectFailure(() -> variant(template, p -> p.setProperty("transient-server-configs", String.join(",", custom))),
                "merged maximum 129 still rejected");
        check(!Files.exists(dir.resolve(CORE)), "no server TOML created beside operator config");
        System.out.println("MODE=" + args[0] + " CHECKS=" + checks + " RESULT=PASS");
        System.out.println("SCOPE=REAL_BRIDGE_CONFIG_AND_PURE_JAVA_PLANNING_WIRE_ONLY");
        System.out.println("NOT_RUN=REAL_CLIENT,VELOCITY_PAPER_RUNTIME,NETTY_JUNIT_FULL_SUITE");
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

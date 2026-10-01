package br.com.atmbrasil.lobby.velocity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Focused executable regression checks against the actual HF6/HF7 plugin classes. */
public final class ArcanusRegistryRegressionHarness {
    private static final String CONTRACT =
            "65ddebdbecf3a09d57c2fcc3ce39099df539fdf9260181c1e31b90afdc3f9577";
    private static final String HASH =
            "bd73a8eb99de591e7bbcf2a16ffef24e69d17f30a6bdcbd691b4dc02c2d26db3";
    private static final String REGISTRY = "forbidden_arcanus:item_modifier";
    private static final String SHIM = "forbidden-arcanus-2.6.1";
    private static final Set<String> NAMESPACES = Set.of(
            "forbidden_arcanus", "minecraft", "ars_nouveau", "ad_astra", "irons_spellbooks", "neovitae");
    private static int checks;

    private ArcanusRegistryRegressionHarness() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("hf6") || args[0].equals("hf7"))) {
            throw new IllegalArgumentException("usage: hf6|hf7 evidence-directory");
        }
        boolean patched = args[0].equals("hf7");
        Path evidence = Path.of(args[1]);
        Files.createDirectories(evidence);
        List<RegistryShimPacket> candidates = RegistryShimCatalog.resolve(
                RegistryShimCatalog.builtInAtmShimIds(), 1_048_576);
        RegistryShimPacket fa = candidates.stream().filter(p -> p.shimId().equals(SHIM)).findFirst().orElseThrow();
        check(candidates.size() == 6, "six configured candidates preserved");
        check(fa.entryCount() == 6 && fa.packetBytes() == 2747, "existing six-entry packet shape preserved");
        check(fa.sha256().equals(HASH) && digest(fa.packetBody()).equals(HASH), "existing packet actual SHA pin");
        Files.write(evidence.resolve("forbidden-item-modifier.bin"), fa.packetBody());

        List<RegistryShimPacket> selected = select(767, candidates, NAMESPACES, CONTRACT);
        check(selected.size() == (patched ? 1 : 0), "HF6 omission / HF7 targeted selection reproduced");
        if (patched) {
            check(selected.getFirst() == fa, "reuse exact immutable candidate, no data fabrication");
            check(selected.getFirst().registryId().equals(REGISTRY), "only item-modifier registry selected");
            check(selected.getFirst().entryCount() == 6, "all six reviewed definitions selected atomically");
        }
        check(RegistryShimCatalog.selectForProfile(null, candidates, NAMESPACES, CONTRACT).equals(selected),
                "compatibility overload agrees for protocol 767");
        check(selected.stream().noneMatch(p -> p.registryId().equals("minecraft:enchantment")),
                "full or partial enchantment packets never leak into this tail");
        check(selected.stream().noneMatch(p -> p.shimId().contains("neovitae") || p.shimId().contains("irons-spellbooks")),
                "other 8.1 registries stay excluded");
        List<RegistryShimPacket> reversed = new ArrayList<>(candidates);
        Collections.reverse(reversed);
        check(select(767, reversed, NAMESPACES, CONTRACT).equals(selected), "candidate order cannot select unrelated data");
        check(select(767, candidates, NAMESPACES, CONTRACT).equals(selected), "repeated selection deterministic");
        expectUnsupported(() -> selected.add(fa), "selected list immutable");
        byte[] isolated = fa.packetBody(); isolated[0] ^= 1;
        check(digest(fa.packetBody()).equals(HASH), "payload defensive ownership preserved");

        for (int protocol : List.of(5, 47, 393, 766, 768, 776)) {
            check(select(protocol, candidates, NAMESPACES, CONTRACT).isEmpty(), "no 8.2 shim for protocol " + protocol);
        }
        for (String contract : List.of("", "0".repeat(64), CONTRACT.substring(0, 12),
                CONTRACT.toUpperCase(Locale.ROOT), CONTRACT + " ", " " + CONTRACT,
                CONTRACT.substring(0, 63) + "8")) {
            check(select(767, candidates, NAMESPACES, contract).isEmpty(), "unreviewed/malformed contract withheld: " + contract.length());
        }
        check(select(767, candidates, NAMESPACES, null).isEmpty(), "null contract has no enrichment");
        check(select(767, candidates, Set.of(), CONTRACT).isEmpty(), "namespace must be advertised");
        check(select(767, candidates, Set.of("FORBIDDEN_ARCANUS"), CONTRACT).isEmpty(), "namespace exact case");
        check(select(767, candidates, Set.of("forbidden_arcanus_extra"), CONTRACT).isEmpty(), "namespace prefix is not enough");
        check(select(767, candidates, Set.of("forbidden_arcanus"), "0".repeat(64)).isEmpty(), "namespace alone grants no data");
        check(select(767, List.of(), NAMESPACES, CONTRACT).isEmpty(), "operator-disabled candidate list respected");
        List<RegistryShimPacket> noFa = candidates.stream().filter(p -> !p.shimId().equals(SHIM)).toList();
        check(select(767, noFa, NAMESPACES, CONTRACT).isEmpty(), "operator removal not undone");
        check(select(767, List.of(fa), NAMESPACES, CONTRACT).equals(selected), "explicit sole reviewed candidate supported");

        if (patched) {
            expectInvalid(() -> select(767, List.of(fa, fa), NAMESPACES, CONTRACT), "duplicate candidate rejected");
            expectInvalid(() -> select(767, List.of(fa, copy(fa, "collision", REGISTRY, "forbidden_arcanus", 6, fa.packetBody(), HASH)), NAMESPACES, CONTRACT), "registry collision rejected");
            expectInvalid(() -> select(767, List.of(copy(fa, "unreviewed", REGISTRY, "forbidden_arcanus", 6, fa.packetBody(), HASH)), NAMESPACES, CONTRACT), "wrong shim identity rejected");
            expectInvalid(() -> select(767, List.of(copy(fa, SHIM, "test:other", "forbidden_arcanus", 6, fa.packetBody(), HASH)), NAMESPACES, CONTRACT), "wrong registry identity rejected");
            expectInvalid(() -> select(767, List.of(copy(fa, SHIM, REGISTRY, "other", 6, fa.packetBody(), HASH)), NAMESPACES, CONTRACT), "wrong candidate namespace rejected");
            expectInvalid(() -> select(767, List.of(copy(fa, SHIM, REGISTRY, "forbidden_arcanus", 7, fa.packetBody(), HASH)), NAMESPACES, CONTRACT), "wrong entry count rejected");
            expectInvalid(() -> select(767, List.of(copy(fa, SHIM, REGISTRY, "forbidden_arcanus", 6, new byte[]{1}, HASH)), NAMESPACES, CONTRACT), "wrong payload length rejected");
            expectInvalid(() -> select(767, List.of(copy(fa, SHIM, REGISTRY, "forbidden_arcanus", 6, fa.packetBody(), "0".repeat(64))), NAMESPACES, CONTRACT), "wrong declared hash rejected");
            byte[] tampered = fa.packetBody(); tampered[tampered.length - 2] ^= 1;
            expectInvalid(() -> select(767, List.of(copy(fa, SHIM, REGISTRY, "forbidden_arcanus", 6, tampered, HASH)), NAMESPACES, CONTRACT), "lying hash cannot bless modified bytes");
        }
        expectInvalid(() -> ForbiddenArcanusItemModifierRegistry.packet(2746), "packet budget still enforced");
        check(ForbiddenArcanusItemModifierRegistry.packet(2747).sha256().equals(HASH), "exact packet budget supported");
        check(RegistryShimCatalog.selectTagsForTransaction(767, null, selected, CONTRACT).isEmpty(), "no 8.1 NeoVitae tags borrowed");
        check(RegistryShimCatalog.selectPaperRegistryReplacement(767, candidates, CONTRACT).isEmpty(), "8.2 never gains full 8.1 enchantment replacement");
        String exact81 = Atm10Normal81EnchantmentRegistry.FULL_CLIENT_CONTRACT_SHA256;
        List<RegistryShimPacket> original81 = select(767, candidates, NAMESPACES, exact81);
        check(original81.stream().map(RegistryShimPacket::shimId).toList().equals(List.of(SHIM,
                RegistryShimCatalog.IRONS_SPELLBOOKS_ATM10_8_1, RegistryShimCatalog.NEOVITAE_SENTIENT_ATM10_8_1)), "exact 8.1 selection preserved");
        Files.write(evidence.resolve("exact-81-selection.txt"), original81.stream().map(p ->
                p.shimId() + " " + p.registryId() + " " + p.entryCount() + " " + p.sha256()).toList(), StandardCharsets.UTF_8);
        check(RegistryShimCatalog.selectPaperRegistryReplacement(767, candidates, exact81).orElseThrow().entryCount() == 139,
                "exact 8.1 full enchantment remains available");
        check(!RegistryShimCatalog.selectTagsForTransaction(767, null, original81, exact81).isEmpty(), "exact 8.1 NeoVitae paired tags preserved");

        SilentGearEmbeddedProfile tts = SilentGearEmbeddedProfile.loadReviewed(
                ArcanusRegistryRegressionHarness.class.getClassLoader(), 767, 1_048_576, 3_145_728);
        List<RegistryShimPacket> ttsSelection = RegistryShimCatalog.selectForProfile(767, tts,
                List.of(fa), Set.of("forbidden_arcanus"), CONTRACT);
        check(ttsSelection.equals(List.of(fa)), "existing selected profile has precedence");
        SilentGearEmbeddedProfile normal73 = SilentGearEmbeddedProfile.loadAtm10Normal73(
                ArcanusRegistryRegressionHarness.class.getClassLoader(), 767, 1_048_576, 3_145_728);
        check(RegistryShimCatalog.selectForProfile(767, normal73, candidates, NAMESPACES, CONTRACT)
                        == normal73.dynamicRegistries().packets(), "embedded dynamic transaction never combined with new tail");

        RegistryShimPacket giselle = Atm10Normal82GiselleEnchantmentExtension.packet(1_048_576);
        check(giselle.entryCount() == 4 && giselle.sha256().equals(
                "d03b3e1424a53d13f6e47170fec4be267e9e273b81be8e75abb83e8501767ee9"), "HF3 narrow Giselle bytes preserved");
        PaperRecipeLifecyclePolicy.Context recipe = new PaperRecipeLifecyclePolicy.Context(
                true, false, true, CONTRACT, NAMESPACES, Set.of(), List.of(), "none", "", "", 0,
                Set.of(RegistryShimReceipt.from(fa), RegistryShimReceipt.from(giselle)));
        PaperRecipeLifecyclePolicy.Evaluation decision = PaperRecipeLifecyclePolicy.decide(recipe);
        check(!decision.release(), "Arcanus plus Giselle receipts do not authorize recipe release");
        check(decision.unsatisfiedRequirements().equals(List.of(PaperRecipeLifecyclePolicy.Requirement.REVIEWED_STRUCTURAL_EVIDENCE)),
                "recipe safety reason unchanged");

        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Object session = new Object();
        DynamicRegistryInjectionFence<Object, Object> fence = new DynamicRegistryInjectionFence<>(id, session, 7, null);
        check(fence.permits(id, session, 7, null, true), "profile-free current lobby registry transaction allowed");
        check(!fence.permits(id, new Object(), 7, null, true), "different session cannot receive stale registry");
        check(!fence.permits(id, session, 8, null, true), "new lobby cycle rejects old transaction");
        check(!fence.permits(id, session, 7, new Object(), true), "profile identity fence retained");
        check(!fence.permits(id, session, 7, null, false), "completed/not-injecting state rejected");
        check(!fence.permits(UUID.randomUUID(), session, 7, null, true), "different player rejected by fence");
        Files.writeString(evidence.resolve("selected-82.txt"), selected.stream().map(p -> p.shimId() + ":" + p.packetBytes())
                .reduce((a, b) -> a + "\n" + b).orElse("") + "\n", StandardCharsets.UTF_8);
        System.out.println("MODE=" + args[0] + " CHECKS=" + checks + " RESULT=PASS");
    }

    private static List<RegistryShimPacket> select(int protocol, List<RegistryShimPacket> candidates,
            Set<String> namespaces, String contract) {
        return RegistryShimCatalog.selectForProfile(protocol, null, candidates, namespaces, contract);
    }
    private static RegistryShimPacket copy(RegistryShimPacket ignored, String id, String registry,
            String namespace, int count, byte[] bytes, String hash) {
        return new RegistryShimPacket(id, namespace, registry, count, bytes, hash);
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void check(boolean valid, String label) {
        if (!valid) { throw new AssertionError(label); }
        checks++; System.out.println("PASS " + label);
    }
    private static void expectInvalid(Action action, String label) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { check(true, label); return; }
        throw new AssertionError(label);
    }
    private static void expectUnsupported(Action action, String label) throws Exception {
        try { action.run(); } catch (UnsupportedOperationException expected) { check(true, label); return; }
        throw new AssertionError(label);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}

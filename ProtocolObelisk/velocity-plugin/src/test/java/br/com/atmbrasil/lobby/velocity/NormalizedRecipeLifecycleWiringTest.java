package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.common.LobbyReadyCodec;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

/** Calls actual session completion and captures its Paper readiness signal, not a policy copy. */
final class NormalizedRecipeLifecycleWiringTest {
    private static final String EXACT = Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256;
    private static final List<String> OPTIONAL_OBSERVATIONS = List.of(
            "20ba732374e84b35565ec15843fa35728495392ed05e413467e0d63b6d71063c",
            "d0d966c18445c53b560c8863b5fca9e2942c5f189387ad174637282cd256990b");
    @TempDir Path temporary;

    @Test
    void reviewedOptionalSessionsReleaseExactlyOneRecipeSignalAndKeepRawIdentity()
            throws Exception {
        // Normalization itself is covered by Atm10Normal81ClientContractEvidenceTest. These are
        // post-negotiation session states, matching the observed hashes from the live round.
        for (String observed : OPTIONAL_OBSERVATIONS) {
            Harness harness = new Harness(temporary, observed, EXACT);
            harness.complete();
            harness.complete();
            assertEquals(1, harness.readySignals.size());
            assertEquals(41L, LobbyReadyCodec.decode(harness.readySignals.getFirst()).sessionId());
            assertEquals(observed, get(harness.session, "fullClientContractSha256"));
            assertEquals(EXACT, get(harness.session, "normalizedClientContractSha256"));
            assertTrue((boolean) get(harness.session, "lobbyClientInitializationComplete"));
            assertEquals(0, harness.disconnects);
            assertTrue(harness.logged("admission=COMPLETE, recipeLifecycle=RELEASE"));
            assertTrue(harness.logged("EXACT_ATM10_81_RUNTIME_CATALOG"));
            assertTrue(harness.logged(observed.substring(0, 12)));
        }
    }

    @Test
    void canonicalFallbackReleasesAndUnknownIdentityRemainsAdmittedWithRecipesWithheld()
            throws Exception {
        Harness canonical = new Harness(temporary, EXACT, null);
        canonical.complete();
        assertEquals(1, canonical.readySignals.size());
        Harness unknown = new Harness(temporary, "0".repeat(64), null);
        unknown.complete();
        unknown.assertWithheld("REVIEWED_STRUCTURAL_EVIDENCE");
    }

    @Test
    void normalizedIdentityCannotReplaceAnyRequiredRegistryDeliveryReceipt() throws Exception {
        for (RegistryShimReceipt omitted : receipts()) {
            Harness harness = new Harness(temporary, OPTIONAL_OBSERVATIONS.getFirst(), EXACT);
            Set<RegistryShimReceipt> remaining = new LinkedHashSet<>(receipts());
            remaining.remove(omitted);
            set(harness.session, "registryShimReceipts", remaining);
            harness.complete();
            harness.assertWithheld(switch (omitted.registryId()) {
                case "minecraft:enchantment" -> "EXACT_ENCHANTMENT_RECEIPT";
                case "neovitae:sentient_upgrades" -> "EXACT_NEOVITAE_CLOSURE_RECEIPT";
                default -> "EXACT_IRONS_SPELLBOOKS_RECEIPT";
            });
        }
    }

    @Test
    void normalizedIdentityCannotBypassConfigWriteCompletionOrCatalogSequence()
            throws Exception {
        Harness incompleteWrite = new Harness(temporary, OPTIONAL_OBSERVATIONS.getFirst(), EXACT);
        set(incompleteWrite.session, "configurationPrefixWriteComplete", false);
        incompleteWrite.complete();
        incompleteWrite.assertWithheld("CONFIGURATION_PREFIX_WRITE");

        Harness missingConfig = new Harness(temporary, OPTIONAL_OBSERVATIONS.getFirst(), EXACT);
        List<String> configs = Atm10Normal81ServerConfigCatalog.catalog().fileNames();
        set(missingConfig.session, "transientServerConfigs", configs.subList(1, configs.size()));
        missingConfig.complete();
        missingConfig.assertWithheld("EXACT_SERVER_CONFIG_CATALOG_DELIVERY");
    }

    @Test
    void normalizedIdentityCannotBypassAdvertisedApothicBootstrap() throws Exception {
        Harness harness = new Harness(temporary, OPTIONAL_OBSERVATIONS.getFirst(), EXACT);
        set(harness.session, "clientboundPlayBootstrapChannelIds", Set.of());
        harness.complete();
        harness.assertWithheld("APOTHIC_ENCHANTMENT_INFO_BOOTSTRAP");
    }

    @Test
    void initializationBeforePlayBootstrapCompletionKeepsItsExistingFailureGuard()
            throws Exception {
        Harness harness = new Harness(temporary, OPTIONAL_OBSERVATIONS.getFirst(), EXACT);
        set(harness.session, "playBootstrapSent", false);
        harness.complete();
        assertTrue(harness.readySignals.isEmpty());
        assertEquals(1, harness.disconnects);
        assertFalse((boolean) get(harness.session, "lobbyClientInitializationComplete"));
        assertEquals("FAILED", ((Enum<?>) get(harness.session, "state")).name());
        assertTrue(harness.logged("before PLAY bootstraps were sent"));
    }

    @Test
    void wrongReceiptDigestAndConflictingRegistryReceiptsRemainWithheld() throws Exception {
        RegistryShimReceipt full = Atm10Normal81EnchantmentRegistry.expectedReceiptIfPresent()
                .orElseThrow();
        RegistryShimReceipt wrong = new RegistryShimReceipt(full.shimId(), full.registryId(),
                full.entryCount(), full.packetBytes(), "0".repeat(64));
        for (boolean keepCorrect : List.of(false, true)) {
            Harness harness = new Harness(temporary, OPTIONAL_OBSERVATIONS.getFirst(), EXACT);
            Set<RegistryShimReceipt> delivered = new LinkedHashSet<>(receipts());
            if (!keepCorrect) {
                delivered.remove(full);
            }
            delivered.add(wrong);
            set(harness.session, "registryShimReceipts", delivered);
            harness.complete();
            harness.assertWithheld("EXACT_ENCHANTMENT_RECEIPT");
        }
    }

    private static Set<RegistryShimReceipt> receipts() {
        return Set.of(Atm10Normal81EnchantmentRegistry.expectedReceiptIfPresent().orElseThrow(),
                RegistryShimReceipt.from(Atm10Normal81IronsSpellbooksRegistry.packet(65_536)),
                Atm10Normal81NeoVitaeSentientClosure.expectedReceiptIfPresent().orElseThrow());
    }

    private static final class Harness {
        private final List<byte[]> readySignals = new ArrayList<>();
        private final List<String> logLines = new ArrayList<>();
        private final Atm10LobbyVelocityPlugin plugin;
        private final Object session;
        private final Player player;
        private int disconnects;

        @SuppressWarnings("unchecked")
        private Harness(Path configDirectory, String observed, String normalized) throws Exception {
            UUID playerUuid = UUID.randomUUID();
            Logger logger = proxy(Logger.class, (instance, method, args) -> {
                if (args != null) {
                    logLines.add(Arrays.deepToString(args));
                }
                return method.getReturnType() == boolean.class ? false : null;
            });
            plugin = new Atm10LobbyVelocityPlugin(null, logger, configDirectory);
            // Newly generated operator defaults intentionally disable the bridge. This fixture
            // exercises an enabled runtime; keep production's disabled-config early return intact.
            BridgeConfig.load(configDirectory);
            Path properties = configDirectory.resolve("bridge.properties");
            Files.writeString(properties,
                    Files.readString(properties).replace("enabled=false", "enabled=true"));
            BridgeConfig config = BridgeConfig.load(configDirectory);
            assertTrue(config.enabled(), "runtime fixture must explicitly enable the bridge");
            set(plugin, "config", config);
            ServerConnection lobby = proxy(ServerConnection.class, (instance, method, args) -> {
                if (method.getName().equals("getServerInfo")) {
                    return new ServerInfo(config.lobbyServer(),
                            new InetSocketAddress("127.0.0.1", 25566));
                }
                if (method.getName().equals("sendPluginMessage")) {
                    assertEquals(LobbyReadyCodec.CHANNEL_ID,
                            ((ChannelIdentifier) args[0]).getId());
                    readySignals.add(((byte[]) args[1]).clone());
                    return true;
                }
                throw new AssertionError("unexpected ServerConnection call " + method);
            });
            player = proxy(Player.class, (instance, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> playerUuid;
                case "getUsername" -> "RecipeVariantTest";
                case "getCurrentServer" -> Optional.of(lobby);
                case "disconnect" -> { disconnects++; yield null; }
                default -> throw new AssertionError("unexpected Player call " + method);
            });
            Class<?> type = Class.forName(Atm10LobbyVelocityPlugin.class.getName() + "$BridgeSession");
            Constructor<?> constructor = type.getDeclaredConstructor(UUID.class);
            constructor.setAccessible(true);
            session = constructor.newInstance(playerUuid);
            ((Map<UUID, Object>) get(plugin, "sessions")).put(playerUuid, session);
            setEnum(session, "state", "LOBBY_PLAY");
            setEnum(session, "negotiation", "NEOFORGE");
            set(session, "readinessSessionId", 41L);
            set(session, "playBootstrapSent", true);
            set(session, "configurationPrefixWriteComplete", true);
            set(session, "fullClientContractSha256", observed);
            set(session, "normalizedClientContractSha256", normalized);
            set(session, "advertisedNamespaces", Set.of("minecraft",
                    ApothicEnchantingBootstrapPayload.REQUIRED_NAMESPACE));
            set(session, "clientboundPlayBootstrapChannelIds",
                    Set.of(ApothicEnchantingBootstrapPayload.CHANNEL_ID));
            Atm10Normal81ServerConfigCatalog.Catalog catalog = Atm10Normal81ServerConfigCatalog.catalog();
            set(session, "transientServerConfigs", catalog.fileNames());
            set(session, "reviewedTransientConfigCatalogId", catalog.id());
            set(session, "reviewedTransientConfigNameSequenceSha256", catalog.nameSequenceSha256());
            set(session, "reviewedTransientConfigPayloadSequenceSha256", catalog.payloadSequenceSha256());
            set(session, "reviewedTransientConfigCatalogEncodedBytes", catalog.totalEncodedBytes());
            set(session, "registryShimReceipts", receipts());
        }

        private void complete() throws Exception {
            Method method = Atm10LobbyVelocityPlugin.class.getDeclaredMethod(
                    "completeLobbyClientInitialization", Player.class, session.getClass());
            method.setAccessible(true);
            method.invoke(plugin, player, session);
        }

        private boolean logged(String text) {
            return logLines.stream().anyMatch(line -> line.contains(text));
        }

        private void assertWithheld(String requirement) throws Exception {
            assertTrue(readySignals.isEmpty());
            assertEquals(0, disconnects);
            assertTrue((boolean) get(session, "lobbyClientInitializationComplete"));
            assertEquals("LOBBY_PLAY", ((Enum<?>) get(session, "state")).name());
            assertTrue(logged("admission=COMPLETE, recipeLifecycle=WITHHELD"));
            assertTrue(logged(requirement));
            assertFalse(logged("recipeLifecycle=RELEASE"));
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object get(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static void setEnum(Object instance, String name, String value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object entry = Arrays.stream(field.getType().getEnumConstants())
                .filter(candidate -> ((Enum<?>) candidate).name().equals(value))
                .findFirst().orElseThrow();
        field.set(instance, entry);
    }
}

package br.com.atmbrasil.lobby.paper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Reasserts and traces the ProMenus BungeeCord transport without taking routing ownership.
 *
 * <p>HF2 is deliberately observational. The only synthetic packet is a harmless BungeeCord
 * {@code GetServer} query, sent through the ProMenus plugin identity when an administrator/player
 * opens a reviewed server-selection menu. Velocity remains the routing authority.</p>
 */
final class ProMenusBungeeCompatibility {
    static final String PROMENUS_PLUGIN_NAME = "ProMenus";
    static final String LEGACY_BUNGEE_CHANNEL = "BungeeCord";
    private static final AtomicBoolean LOGGED_ARMED = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_MISSING = new AtomicBoolean();
    private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean();
    private static final List<String> REVIEWED_COMMANDS = List.of(
            "/forbidden", "/forbiddenservers", "/serversforbidden",
            "/atm", "/atmbrasil", "/atmservers", "/serversatm");
    private static final List<String> REVIEWED_MENUS = List.of(
            "LOBBY_FORBIDDEN.yml", "LOBBY_ATM_BRASIL.yml");

    private ProMenusBungeeCompatibility() {
    }

    static void arm(JavaPlugin owner) {
        Objects.requireNonNull(owner, "owner");
        owner.getServer().getScheduler().runTask(owner, () -> {
            ensureRegistered(owner);
            auditMenus(owner);
            if (LISTENER_REGISTERED.compareAndSet(false, true)) {
                owner.getServer().getPluginManager().registerEvents(new TraceListener(owner), owner);
                owner.getLogger().info(
                        "[PROMENUS-TRACE] HF2 observer armed: commands=" + REVIEWED_COMMANDS
                                + ", syntheticProbe=GetServer, routingMutation=false");
            }
        });
    }

    static boolean ensureRegistered(JavaPlugin owner) {
        Objects.requireNonNull(owner, "owner");
        Plugin proMenus = owner.getServer().getPluginManager().getPlugin(PROMENUS_PLUGIN_NAME);
        if (proMenus == null || !proMenus.isEnabled()) {
            if (LOGGED_MISSING.compareAndSet(false, true)) {
                owner.getLogger().info(
                        "ProMenus Bungee compatibility idle: ProMenus is not enabled");
            }
            return false;
        }
        try {
            owner.getServer().getMessenger().registerOutgoingPluginChannel(
                    proMenus, LEGACY_BUNGEE_CHANNEL);
            if (LOGGED_ARMED.compareAndSet(false, true)) {
                owner.getLogger().info(
                        "ProMenus Bungee compatibility armed: plugin=ProMenus, "
                                + "outgoingChannel=BungeeCord, routingAuthority=Velocity");
            }
            return true;
        } catch (RuntimeException exception) {
            owner.getLogger().log(
                    Level.WARNING,
                    "Could not reassert ProMenus outgoing BungeeCord channel; "
                            + "ProMenus [bungee] actions may remain unavailable",
                    exception);
            return false;
        }
    }

    private static void sendControlProbe(JavaPlugin owner, Player player, String command) {
        Plugin proMenus = owner.getServer().getPluginManager().getPlugin(PROMENUS_PLUGIN_NAME);
        if (proMenus == null || !proMenus.isEnabled()) {
            owner.getLogger().warning(
                    "[PROMENUS-TRACE] controlProbe=SKIPPED reason=promenus-unavailable player="
                            + player.getName() + " command=" + command);
            return;
        }
        ensureRegistered(owner);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("GetServer");
            }
            byte[] payload = bytes.toByteArray();
            player.sendPluginMessage(proMenus, LEGACY_BUNGEE_CHANNEL, payload);
            owner.getLogger().info(
                    "[PROMENUS-TRACE] controlProbe=SENT player=" + player.getName()
                            + " plugin=ProMenus channel=BungeeCord subchannel=GetServer bytes="
                            + payload.length + " command=" + command
                            + " routingMutation=false");
        } catch (IOException | RuntimeException exception) {
            owner.getLogger().log(
                    Level.WARNING,
                    "[PROMENUS-TRACE] controlProbe=FAILED player=" + player.getName()
                            + " command=" + command,
                    exception);
        }
    }

    private static void auditMenus(JavaPlugin owner) {
        for (String menu : REVIEWED_MENUS) {
            Path path = resolveMenu(menu);
            if (path == null) {
                owner.getLogger().warning(
                        "[PROMENUS-TRACE] menuAudit=MISSING menu=" + menu
                                + " candidates=[../menus/LOBBIES,menus/LOBBIES]");
                continue;
            }
            try {
                byte[] raw = Files.readAllBytes(path);
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                List<String> actions = extractRelevantActions(lines);
                owner.getLogger().info(
                        "[PROMENUS-TRACE] menuAudit=FOUND menu=" + menu
                                + " path=" + path.toAbsolutePath().normalize()
                                + " bytes=" + raw.length
                                + " sha256=" + sha256(raw)
                                + " relevantActions=" + actions);
            } catch (IOException | RuntimeException exception) {
                owner.getLogger().log(
                        Level.WARNING,
                        "[PROMENUS-TRACE] menuAudit=FAILED menu=" + menu
                                + " path=" + path,
                        exception);
            }
        }
    }

    private static Path resolveMenu(String fileName) {
        for (Path candidate : List.of(
                Path.of("..", "menus", "LOBBIES", fileName),
                Path.of("menus", "LOBBIES", fileName))) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private static List<String> extractRelevantActions(List<String> lines) {
        List<String> actions = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String trimmed = lines.get(index).trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.contains("[bungee=")
                    || lower.contains("assert-string-equals")
                    || lower.contains("[player-command]")
                    || lower.contains("[console-command]")) {
                actions.add((index + 1) + ":" + trimmed);
            }
        }
        return List.copyOf(actions);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static final class TraceListener implements Listener {
        private final JavaPlugin owner;

        private TraceListener(JavaPlugin owner) {
            this.owner = owner;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
        public void onCommand(PlayerCommandPreprocessEvent event) {
            String command = firstToken(event.getMessage()).toLowerCase(Locale.ROOT);
            if (!REVIEWED_COMMANDS.contains(command)) {
                return;
            }
            owner.getLogger().info(
                    "[PROMENUS-TRACE] menuCommand player=" + event.getPlayer().getName()
                            + " command=" + command + " phase=preprocess");
            auditMenus(owner);
            sendControlProbe(owner, event.getPlayer(), command);
        }

        private static String firstToken(String message) {
            String trimmed = Objects.requireNonNullElse(message, "").trim();
            int separator = trimmed.indexOf(' ');
            return separator >= 0 ? trimmed.substring(0, separator) : trimmed;
        }
    }
}

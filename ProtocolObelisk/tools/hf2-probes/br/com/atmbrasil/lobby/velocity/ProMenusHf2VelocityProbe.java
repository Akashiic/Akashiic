package br.com.atmbrasil.lobby.velocity;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.LegacyChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;

public final class ProMenusHf2VelocityProbe {
    public static void main(String[] args) throws Exception {
        List<Object[]> logs = new ArrayList<>();
        Logger logger = (Logger) Proxy.newProxyInstance(
                ProMenusHf2VelocityProbe.class.getClassLoader(), new Class<?>[]{Logger.class},
                (proxy, method, methodArgs) -> {
                    if (method.getName().equals("info") && methodArgs != null) logs.add(methodArgs.clone());
                    return null;
                });
        ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(
                ProMenusHf2VelocityProbe.class.getClassLoader(), new Class<?>[]{ProxyServer.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getAllServers" -> List.of();
                    case "getServer" -> Optional.empty();
                    default -> null;
                });
        Atm10LobbyVelocityPlugin plugin = new Atm10LobbyVelocityPlugin(proxy, logger, Path.of("."));
        ServerInfo lobbyInfo = new ServerInfo("lobby", new InetSocketAddress("127.0.0.1", 25566));
        ServerInfo forbiddenInfo = new ServerInfo("forbidden-1", new InetSocketAddress("127.0.0.1", 25567));
        RegisteredServer forbidden = registered(forbiddenInfo);
        final Player[] playerRef = new Player[1];
        ServerConnection lobby = (ServerConnection) Proxy.newProxyInstance(
                ProMenusHf2VelocityProbe.class.getClassLoader(), new Class<?>[]{ServerConnection.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getPlayer" -> playerRef[0];
                    case "getServerInfo" -> lobbyInfo;
                    case "sendPluginMessage" -> true;
                    default -> null;
                });
        Player player = (Player) Proxy.newProxyInstance(
                ProMenusHf2VelocityProbe.class.getClassLoader(), new Class<?>[]{Player.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getUniqueId" -> UUID.fromString("d571e001-d1bf-4390-a707-35405e1e06be");
                    case "getUsername" -> "Akashiic";
                    case "getProtocolVersion" -> ProtocolVersion.getProtocolVersion(5);
                    case "getCurrentServer" -> Optional.of(lobby);
                    case "isActive" -> true;
                    case "sendPluginMessage" -> true;
                    default -> null;
                });
        playerRef[0] = player;
        byte[] connect = payload("Connect", "forbidden-1");
        PluginMessageEvent message = new PluginMessageEvent(
                lobby, player, MinecraftChannelIdentifier.from("bungeecord:main"), connect);
        plugin.onPluginMessage(message);
        if (!message.getResult().isAllowed()) throw new AssertionError("HF2 changed native forward result");
        if (!contains(logs, "[BUNGEE-TRACE] pluginMessage")) throw new AssertionError("message trace missing");

        PluginMessageEvent legacyMessage = new PluginMessageEvent(
                lobby, player, new LegacyChannelIdentifier("BungeeCord"), payload("GetServer"));
        plugin.onPluginMessage(legacyMessage);
        if (!legacyMessage.getResult().isAllowed()) throw new AssertionError("HF2 changed legacy native forward result");
        if (!contains(logs, "[BUNGEE-TRACE] pluginMessage")) throw new AssertionError("legacy message trace missing");

        ServerPreConnectEvent pre = new ServerPreConnectEvent(player, forbidden, registered(lobbyInfo));
        plugin.onServerPreConnect(pre);
        if (pre.getResult().getServer().orElseThrow() != forbidden) {
            throw new AssertionError("HF2 mutated preconnect target");
        }
        if (!contains(logs, "[BUNGEE-TRACE] preConnect")) throw new AssertionError("preconnect trace missing");
        System.out.println("VELOCITY_HF2_PROBE=PASS logs=" + logs.size());
    }

    private static RegisteredServer registered(ServerInfo info) {
        return (RegisteredServer) Proxy.newProxyInstance(
                ProMenusHf2VelocityProbe.class.getClassLoader(), new Class<?>[]{RegisteredServer.class},
                (p, m, a) -> m.getName().equals("getServerInfo") ? info : null);
    }

    private static byte[] payload(String... parts) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (String part : parts) out.writeUTF(part);
        }
        return bytes.toByteArray();
    }

    private static boolean contains(List<Object[]> logs, String fragment) {
        return logs.stream().anyMatch(args -> args.length > 0 && String.valueOf(args[0]).contains(fragment));
    }
}

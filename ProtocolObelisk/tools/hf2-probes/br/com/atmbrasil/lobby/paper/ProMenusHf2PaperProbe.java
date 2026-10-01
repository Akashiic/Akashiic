package br.com.atmbrasil.lobby.paper;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.scheduler.BukkitScheduler;

public final class ProMenusHf2PaperProbe {
    public static void main(String[] args) throws Exception {
        AtomicReference<Listener> registeredListener = new AtomicReference<>();
        AtomicReference<byte[]> payload = new AtomicReference<>();
        AtomicReference<Plugin> payloadPlugin = new AtomicReference<>();
        AtomicReference<String> payloadChannel = new AtomicReference<>();

        Plugin proMenus = (Plugin) Proxy.newProxyInstance(
                ProMenusHf2PaperProbe.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "getName" -> "ProMenus";
                    case "isEnabled" -> true;
                    default -> null;
                });

        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                ProMenusHf2PaperProbe.class.getClassLoader(), new Class<?>[]{PluginManager.class},
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "getPlugin" -> "ProMenus".equals(methodArgs[0]) ? proMenus : null;
                    case "registerEvents" -> { registeredListener.set((Listener) methodArgs[0]); yield null; }
                    case "getPlugins" -> new Plugin[]{proMenus};
                    default -> null;
                });
        Messenger messenger = (Messenger) Proxy.newProxyInstance(
                ProMenusHf2PaperProbe.class.getClassLoader(), new Class<?>[]{Messenger.class},
                (proxy, method, methodArgs) -> null);
        BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(
                ProMenusHf2PaperProbe.class.getClassLoader(), new Class<?>[]{BukkitScheduler.class},
                (proxy, method, methodArgs) -> {
                    if (method.getName().equals("runTask")) ((Runnable) methodArgs[1]).run();
                    return null;
                });
        Server server = (Server) Proxy.newProxyInstance(
                ProMenusHf2PaperProbe.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "getPluginManager" -> pluginManager;
                    case "getMessenger" -> messenger;
                    case "getScheduler" -> scheduler;
                    default -> null;
                });
        Logger logger = Logger.getLogger("hf2-paper-probe");
        JavaPlugin owner = new JavaPlugin() {
            @Override public Server getServer() { return server; }
            @Override public Logger getLogger() { return logger; }
            @Override public String getName() { return "ProtocolObelisk"; }
            @Override public boolean isEnabled() { return true; }
        };
        Player player = (Player) Proxy.newProxyInstance(
                ProMenusHf2PaperProbe.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "getName" -> "Akashiic";
                    case "sendPluginMessage" -> {
                        payloadPlugin.set((Plugin) methodArgs[0]);
                        payloadChannel.set((String) methodArgs[1]);
                        payload.set(((byte[]) methodArgs[2]).clone());
                        yield null;
                    }
                    case "isOnline" -> true;
                    default -> null;
                });

        ProMenusBungeeCompatibility.arm(owner);
        Listener listener = registeredListener.get();
        if (listener == null) throw new AssertionError("HF2 listener was not registered");
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent() {
            @Override public Player getPlayer() { return player; }
            @Override public String getMessage() { return "/forbidden"; }
        };
        var method = listener.getClass().getDeclaredMethod("onCommand", PlayerCommandPreprocessEvent.class);
        method.setAccessible(true);
        method.invoke(listener, event);

        if (payload.get() == null) throw new AssertionError("GetServer probe was not sent");
        if (payloadPlugin.get() != proMenus) throw new AssertionError("probe sender is not ProMenus");
        if (!"BungeeCord".equals(payloadChannel.get())) throw new AssertionError("wrong channel");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload.get()))) {
            if (!"GetServer".equals(in.readUTF())) throw new AssertionError("wrong subchannel");
        }
        System.out.println("PAPER_HF2_PROBE=PASS bytes=" + payload.get().length);
    }
}

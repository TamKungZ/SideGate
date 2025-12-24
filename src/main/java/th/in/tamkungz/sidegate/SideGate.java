package th.in.tamkungz.sidegate;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SideGate extends JavaPlugin implements Listener {

    private Set<String> allowedGuests;
    private final String GUEST_TAG = "SideGate_Guest";

    // Configuration Variables
    private boolean guestModeEnabled;
    private boolean allowAllGuests;
    private GameMode defaultGamemode;
    private String chatPrefix;
    private String joinTitle;
    private String joinSubtitle;
    private List<String> joinMessages;

    // Reflection Cache
    private Field cachedServerConnectionField;
    private Field cachedPacketListenerField;
    private Field cachedGameProfileField;
    private Field cachedStateField;
    private Object cachedReadyStateEnum;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();
        getServer().getPluginManager().registerEvents(this, this);
        registerPacketListener();
        getLogger().info("SideGate enabled: Cancel-First Injection Mode.");
    }

    private void loadConfiguration() {
        reloadConfig();

        guestModeEnabled = getConfig().getBoolean("enable-guest-mode", true);
        allowAllGuests = getConfig().getBoolean("allow-all-guests", false);

        allowedGuests = new HashSet<>();
        List<String> guests = getConfig().getStringList("allowed-guests");
        for (String name : guests) {
            allowedGuests.add(name.toLowerCase());
        }

        String gmName = getConfig().getString("guest-settings.default-gamemode", "SURVIVAL");
        try {
            defaultGamemode = GameMode.valueOf(gmName.toUpperCase());
        } catch (IllegalArgumentException e) {
            defaultGamemode = GameMode.SURVIVAL;
        }

        chatPrefix = color(getConfig().getString("guest-settings.chat-prefix", "&7[Guest] &r"));
        joinTitle = color(getConfig().getString("messages.join-title", "&cACCESS RESTRICTED"));
        joinSubtitle = color(getConfig().getString("messages.join-subtitle", "&7Guest Mode"));

        joinMessages = new ArrayList<>();
        for (String line : getConfig().getStringList("messages.join-message")) {
            joinMessages.add(color(line));
        }

        getLogger().info("Config Loaded. Allow All: " + allowAllGuests + ", Whitelist: " + allowedGuests.size());
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private void registerPacketListener() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(this, PacketType.Login.Client.START) {
                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!guestModeEnabled) return;

                            String playerName = event.getPacket().getStrings().read(0);
                            String lowerName = playerName.toLowerCase();

                            // Check permission
                            boolean shouldBypass = allowAllGuests || allowedGuests.contains(lowerName);

                            if (shouldBypass) {
                                getLogger().info("[SideGate] Bypassing auth for: " + playerName);

                                SocketAddress address = event.getPlayer().getAddress();

                                // 1. CANCEL the packet immediately to stop Standard Auth
                                event.setCancelled(true);

                                // 2. Schedule Injection on the main thread (Next Tick)
                                // This solves the "Could not find NetworkManager" race condition
                                Bukkit.getScheduler().runTask(SideGate.this, () -> {
                                    boolean success = forceLoginState(address, playerName);
                                    if (success) {
                                        getLogger().info("[SideGate] Success: " + playerName + " (State Injected)");
                                    } else {
                                        // If this fails, the player will time out (stuck on connecting)
                                        // But since we are on the main thread now, failure is very unlikely if the player is connected.
                                        getLogger().warning("[SideGate] FATAL: Could not inject state for " + playerName);
                                    }
                                });
                            }
                        } catch (Exception e) {
                            getLogger().warning("Error: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
        );
    }

    private boolean forceLoginState(SocketAddress address, String playerName) {
        try {
            Object mcServer = getMinecraftServer();
            if (mcServer == null) return false;

            Object serverConnection;
            if (cachedServerConnectionField != null) {
                serverConnection = cachedServerConnectionField.get(mcServer);
            } else {
                Field f = getFieldByTypeName(mcServer.getClass(), "ServerConnection");
                if (f == null) return false;
                cachedServerConnectionField = f;
                serverConnection = f.get(mcServer);
            }

            Object targetNetworkManager = null;
            int targetPort = -1;
            if (address instanceof InetSocketAddress) {
                targetPort = ((InetSocketAddress) address).getPort();
            }

            // Expanded Search: Check Collections (Lists/Sets)
            for (Field field : serverConnection.getClass().getDeclaredFields()) {
                if (Collection.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Collection<?> collection = (Collection<?>) field.get(serverConnection);
                    if (collection == null) continue;

                    // Sync is crucial
                    synchronized (collection) {
                        for (Object manager : collection) {
                            try {
                                if (!isNetworkManagerLike(manager)) continue;

                                SocketAddress mgrAddr = getNetworkManagerAddress(manager);

                                if (mgrAddr instanceof InetSocketAddress && targetPort != -1) {
                                    if (((InetSocketAddress) mgrAddr).getPort() == targetPort) {
                                        targetNetworkManager = manager;
                                        break;
                                    }
                                } else if (mgrAddr != null && mgrAddr.equals(address)) {
                                    targetNetworkManager = manager;
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    if (targetNetworkManager != null) break;
                }
            }

            if (targetNetworkManager == null) {
                getLogger().warning("Could not find matching NetworkManager (Port: " + targetPort + ")");
                return false;
            }

            Object packetListener;
            if (cachedPacketListenerField != null) {
                packetListener = cachedPacketListenerField.get(targetNetworkManager);
            } else {
                Field f = getFieldByTypeName(targetNetworkManager.getClass(), "PacketListener");
                if (f == null) return false;
                cachedPacketListenerField = f;
                packetListener = f.get(targetNetworkManager);
            }

            if (packetListener == null) return false;

            UUID offlineUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
            Object gameProfile = createGameProfile(offlineUUID, playerName);

            if (cachedGameProfileField != null) {
                cachedGameProfileField.set(packetListener, gameProfile);
            } else {
                Field f = findFieldByType(packetListener.getClass(), "com.mojang.authlib.GameProfile");
                if (f == null) return false;
                f.setAccessible(true);
                cachedGameProfileField = f;
                f.set(packetListener, gameProfile);
            }

            if (cachedStateField != null && cachedReadyStateEnum != null) {
                cachedStateField.set(packetListener, cachedReadyStateEnum);
            } else {
                boolean stateSet = false;
                for (Field f : packetListener.getClass().getDeclaredFields()) {
                    if (f.getType().isEnum()) {
                        Object[] constants = f.getType().getEnumConstants();
                        for (Object constant : constants) {
                            String name = constant.toString();
                            if (name.equals("READY_TO_ACCEPT") || name.equals("ACCEPTED")) {
                                f.setAccessible(true);
                                f.set(packetListener, constant);
                                cachedStateField = f;
                                cachedReadyStateEnum = constant;
                                stateSet = true;
                                break;
                            }
                        }
                    }
                    if (stateSet) break;
                }
                if (!stateSet) return false;
            }

            return true;

        } catch (Exception e) {
            getLogger().severe("Exception in Reflection: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean isNetworkManagerLike(Object obj) {
        if (obj == null) return false;
        return getFieldByTypeName(obj.getClass(), "Channel") != null ||
                getFieldByTypeName(obj.getClass(), "SocketAddress") != null;
    }

    private Object getMinecraftServer() {
        try {
            Object craftServer = Bukkit.getServer();
            Method getServerMethod = craftServer.getClass().getMethod("getServer");
            return getServerMethod.invoke(craftServer);
        } catch (Exception e) {
            return null;
        }
    }

    private SocketAddress getNetworkManagerAddress(Object networkManager) {
        try {
            Method getAddrMethod = getMethodByReturnType(networkManager.getClass(), SocketAddress.class);
            if (getAddrMethod != null) return (SocketAddress) getAddrMethod.invoke(networkManager);

            Object channel = getFieldByTypeName(networkManager.getClass(), "Channel", networkManager);
            if (channel != null) {
                Method remoteAddr = channel.getClass().getMethod("remoteAddress");
                return (SocketAddress) remoteAddr.invoke(channel);
            }

            return (SocketAddress) getFieldByTypeName(networkManager.getClass(), "SocketAddress", networkManager);
        } catch (Exception e) {
            return null;
        }
    }

    private Field getFieldByTypeName(Class<?> clazz, String typeNamePartial) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType().getSimpleName().contains(typeNamePartial) || f.getType().getName().contains(typeNamePartial)) {
                f.setAccessible(true);
                return f;
            }
        }
        if (clazz.getSuperclass() != null) return getFieldByTypeName(clazz.getSuperclass(), typeNamePartial);
        return null;
    }

    private Object getFieldByTypeName(Class<?> clazz, String typeNamePartial, Object instance) {
        try {
            Field f = getFieldByTypeName(clazz, typeNamePartial);
            if (f != null) return f.get(instance);
        } catch (Exception e) {}
        return null;
    }

    private Method getMethodByReturnType(Class<?> clazz, Class<?> returnType) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getReturnType().equals(returnType) && m.getParameterCount() == 0) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private Field findFieldByType(Class<?> clazz, String typeName) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType().getName().equals(typeName)) return f;
        }
        if (clazz.getSuperclass() != null) return findFieldByType(clazz.getSuperclass(), typeName);
        return null;
    }

    private Object createGameProfile(UUID uuid, String name) {
        try {
            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            java.lang.reflect.Constructor<?> constructor = gameProfileClass.getConstructor(UUID.class, String.class);
            return constructor.newInstance(uuid, name);
        } catch (Exception e) {
            throw new RuntimeException("Could not create GameProfile", e);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getUniqueId().version() == 3) {
            handleGuestJoin(player);
        }
    }

    private void handleGuestJoin(Player player) {
        player.addScoreboardTag(GUEST_TAG);

        for (String msg : joinMessages) {
            player.sendMessage(msg);
        }

        player.setGameMode(defaultGamemode);
        player.sendTitle(joinTitle, joinSubtitle, 10, 70, 20);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (event.getPlayer().getScoreboardTags().contains(GUEST_TAG)) {
            event.setFormat(chatPrefix + event.getFormat());
        }
    }
}
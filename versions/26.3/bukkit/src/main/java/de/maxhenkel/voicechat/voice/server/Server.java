package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.api.VoicechatSocket;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.debug.CooldownTimer;
import de.maxhenkel.voicechat.debug.VoicechatUncaughtExceptionHandler;
import de.maxhenkel.voicechat.legacy.LegacyCompatibility;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.net.PacketRateLimiter;
import de.maxhenkel.voicechat.permission.PermissionManager;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.plugins.impl.audiosender.AudioSenderImpl;
import de.maxhenkel.voicechat.voice.common.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class Server extends Thread {

    private static final String NONCE_MAC = "HmacSHA256";
    private static final int NONCE_KEY_SIZE_BYTES = 32;
    private static final long PERMISSION_CHECK_INTERVAL = 500L;
    private static final int SEND_QUEUE_CAPACITY = 20_000;

    private final Map<UUID, ClientConnection> connections;
    private final ConnectionSecrets connectionSecrets;
    private final SecretKeySpec nonceKey;
    private final int port;
    private final org.bukkit.Server server;
    private final VoicechatSocket socket;
    private final ProcessThread processThread;
    private final BlockingQueue<UdpPacket> packetQueue;
    private final PingManager pingManager;
    private final PlayerStateManager playerStateManager;
    private final ServerGroupManager groupManager;
    private final ServerCategoryManager categoryManager;
    /**
     * Dedicated thread pool for outbound UDP sends.
     * <p>
     * With 20 players in range, one mic packet can require up to 19 outbound
     * DatagramSocket.send() calls.  Each call can block briefly on the kernel.
     * Running these from the single {@code ProcessThread} means it blocks on I/O
     * instead of processing new incoming packets, causing the queue to fill up
     * and triggering the TTL-exceeded warning.
     * <p>
     * Using a fixed pool of 4 threads distributes the I/O load across cores and
     * lets {@code ProcessThread} immediately proceed to the next incoming packet.
     */
    private final ExecutorService sendExecutor;
    private final PacketRateLimiter packetRateLimiter;

    public Server() {
        int configPort = Voicechat.SERVER_CONFIG.voiceChatPort.get();
        if (configPort < 0) {
            Voicechat.LOGGER.info("Using the Minecraft servers port as voice chat port");
            port = Bukkit.getPort();
        } else {
            port = configPort;
        }
        this.server = Bukkit.getServer();
        socket = PluginManager.instance().getSocketImplementation();
        connections = new ConcurrentHashMap<>();
        connectionSecrets = new ConnectionSecrets();
        nonceKey = new SecretKeySpec(Utils.generateSecureRandomBytes(NONCE_KEY_SIZE_BYTES), NONCE_MAC);
        packetQueue = new LinkedBlockingQueue<>(Voicechat.SERVER_CONFIG.packetQueueSize.get());
        pingManager = new PingManager(this);
        playerStateManager = new PlayerStateManager();
        groupManager = new ServerGroupManager();
        categoryManager = new ServerCategoryManager();
        // The send queue is bounded: voice is real-time, so when the sender threads can't
        // keep up (e.g. blocked network), stale packets are dropped instead of piling up
        // in memory until the server runs out of heap.
        sendExecutor = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(SEND_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "VoiceChatSendThread");
                    t.setDaemon(true);
                    t.setUncaughtExceptionHandler(new VoicechatUncaughtExceptionHandler());
                    return t;
                },
                (r, executor) -> CooldownTimer.run("send_queue_full", () -> Voicechat.LOGGER.warn("Voice chat send queue is full, dropping packets")));
        packetRateLimiter = new PacketRateLimiter(Voicechat.SERVER_CONFIG.udpRateLimit.get());
        setDaemon(true);
        setName("VoiceChatServerThread");
        setUncaughtExceptionHandler(new VoicechatUncaughtExceptionHandler());
        processThread = new ProcessThread();
        processThread.start();
    }

    @Override
    public void run() {
        try {
            String bindAddress = getBindAddress();
            try {
                InetAddress.getByName(bindAddress);
            } catch (UnknownHostException e) {
                Voicechat.LOGGER.error("Failed to parse bind IP address '{}'", bindAddress, e);
                Voicechat.LOGGER.info("Binding to wildcard IP address");
                bindAddress = "";
            }
            socket.open(port, bindAddress);

            if (bindAddress.isEmpty()) {
                Voicechat.LOGGER.info("Voice chat server started at port {}", socket.getLocalPort());
            } else {
                Voicechat.LOGGER.info("Voice chat server started at {}:{}", bindAddress, socket.getLocalPort());
            }

            while (!socket.isClosed()) {
                try {
                    queuePacket(socket.read());
                } catch (ProtocolException e) {
                    if (Voicechat.debugMode()) {
                        Voicechat.LOGGER.warn("Failed to read packet", e);
                    } else {
                        CooldownTimer.run("socket_protocol_error", () -> {
                            Voicechat.LOGGER.warn("Failed to read packet: {}", e.getMessage());
                        });
                    }
                } catch (Exception e) {
                    // Only log an error if the error isn't caused by the socket being closed
                    if (!(e instanceof SocketException && e.getCause() instanceof AsynchronousCloseException)) {
                        if (Voicechat.debugMode()) {
                            Voicechat.LOGGER.error("Failed to read from socket", e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Voicechat.LOGGER.error("Voice chat server error", e);
        }
    }

    private String getBindAddress() {
        String bindAddress = Voicechat.SERVER_CONFIG.voiceChatBindAddress.get();

        if (bindAddress.trim().equals("*")) {
            bindAddress = "";
        } else if (bindAddress.trim().isEmpty()) {
            try {
                bindAddress = Bukkit.getIp();
                if (!bindAddress.trim().isEmpty()) {
                    try {
                        InetAddress address = InetAddress.getByName(bindAddress);
                        if (address.isLoopbackAddress()) {
                            bindAddress = "";
                        } else {
                            Voicechat.LOGGER.info("Using server-ip as bind address: {}", bindAddress);
                        }
                    } catch (Exception e) {
                        Voicechat.LOGGER.warn("Invalid server-ip", e);
                        bindAddress = "";
                    }
                }
            } catch (Throwable t) {
                Voicechat.LOGGER.warn("Failed to get server-ip from server.properties - binding to wildcard address", t);
            }
        }

        return bindAddress;
    }

    public ConnectionSecrets getConnectionSecrets() {
        return connectionSecrets;
    }

    /**
     * Creates a nonce that only this server can create for the given connection and address.
     * The server sends it to the address in the {@link AuthenticateAckPacket} and only accepts a {@link ConnectionCheckPacket} with it from the same address.
     * This way, a client can only connect from an address it can receive packets at.
     *
     * @param connectionId the connection ID of the player
     * @param address      the address of the client
     * @return the nonce
     */
    public byte[] createNonce(UUID connectionId, SocketAddress address) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(NONCE_MAC);
        mac.init(nonceKey);
        mac.update(ByteBuffer.allocate(16).putLong(connectionId.getMostSignificantBits()).putLong(connectionId.getLeastSignificantBits()).array());
        mac.update(addressToBytes(address));
        return Arrays.copyOf(mac.doFinal(), NetworkMessage.NONCE_SIZE_BYTES);
    }

    private static byte[] addressToBytes(SocketAddress address) {
        if (!(address instanceof InetSocketAddress)) {
            return address.toString().getBytes(StandardCharsets.UTF_8);
        }
        InetSocketAddress inetAddress = (InetSocketAddress) address;
        byte[] ip = inetAddress.getAddress().getAddress();
        return ByteBuffer.allocate(ip.length + 4).put(ip).putInt(inetAddress.getPort()).array();
    }

    public void disconnectClient(UUID playerUUID) {
        connections.remove(playerUUID);
        connectionSecrets.remove(playerUUID);
        PluginManager.instance().onPlayerDisconnected(playerUUID);
    }

    public void close() {
        socket.close();
        processThread.close();
        sendExecutor.shutdown();
        try {
            if (!sendExecutor.awaitTermination(1L, TimeUnit.SECONDS)) {
                sendExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sendExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        PluginManager.instance().onServerStopped();
    }

    public boolean isClosed() {
        return !processThread.running;
    }

    private class ProcessThread extends Thread {
        private volatile boolean running;
        private long lastKeepAlive;
        private long lastPermissionCheck;

        public ProcessThread() {
            running = true;
            lastKeepAlive = Utils.getMilliseconds();
            lastPermissionCheck = Utils.getMilliseconds();
            setDaemon(true);
            setName("VoiceChatPacketProcessingThread");
            setUncaughtExceptionHandler(new VoicechatUncaughtExceptionHandler());
        }

        @Override
        public void run() {
            while (running) {
                try {
                    pingManager.checkTimeouts();
                    long time = Utils.getMilliseconds();
                    if (time - lastKeepAlive > Voicechat.SERVER_CONFIG.keepAlive.get()) {
                        sendKeepAlives();
                        lastKeepAlive = time;
                    }
                    if (time - lastPermissionCheck > PERMISSION_CHECK_INTERVAL) {
                        // Bukkit permission checks are not thread-safe
                        Voicechat.compatibility.runTask(Server.this::pollPermissions);
                        lastPermissionCheck = time;
                    }

                    UdpPacket udpPacket = packetQueue.poll(10, TimeUnit.MILLISECONDS);
                    if (udpPacket == null) {
                        continue;
                    }

                    NetworkMessage message;
                    try {
                        message = NetworkMessage.readPacketServer(LegacyCompatibility.translateIncomingVoicePacket(Server.this, udpPacket), Server.this);
                    } catch (Exception e) {
                        CooldownTimer.run("failed_reading_packet", () -> {
                            Voicechat.LOGGER.warn("Failed to read packet from {}", udpPacket.getSocketAddress());
                        });
                        continue;
                    }

                    if (message == null) {
                        continue;
                    }

                    if (Utils.getMilliseconds() - message.getTimestamp() > message.getTTL()) {
                        CooldownTimer.run("ttl", () -> {
                            Voicechat.LOGGER.error("Dropping voice chat packets! Your Server might be overloaded!");
                            Voicechat.LOGGER.error("Packet queue has {} packets", packetQueue.size());
                        });
                        continue;
                    }

                    ConnectionSecret connectionSecret = connectionSecrets.getByConnectionId(message.getConnectionId());
                    if (connectionSecret == null) {
                        continue;
                    }

                    if (message.getPacket() instanceof AuthenticatePacket) {
                        // The connection is only created once the client sends the nonce back from the same address
                        sendPacket(new AuthenticateAckPacket(createNonce(connectionSecret.getConnectionId(), message.getAddress())), new ClientConnection(connectionSecret, message.getAddress()));
                        if (!connectionSecret.isAuthenticated()) {
                            connectionSecret.setAuthenticated(true);
                            Voicechat.LOGGER.info("Successfully authenticated player {}", connectionSecret.getPlayerUUID());
                        }
                    }

                    if (message.getPacket() instanceof ConnectionCheckPacket) {
                        ConnectionCheckPacket packet = (ConnectionCheckPacket) message.getPacket();
                        if (!MessageDigest.isEqual(createNonce(connectionSecret.getConnectionId(), message.getAddress()), packet.getNonce())) {
                            continue;
                        }
                        ClientConnection connection = connections.get(connectionSecret.getPlayerUUID());
                        if (connection != null) {
                            if (connection.getAddress().equals(message.getAddress())) {
                                sendPacket(new ConnectionCheckAckPacket(), connection);
                            }
                            continue;
                        }
                        connection = new ClientConnection(connectionSecret, message.getAddress());
                        connections.put(connection.getPlayerUUID(), connection);
                        Voicechat.LOGGER.info("Successfully validated connection of player {}", connection.getPlayerUUID());
                        Player player = server.getPlayer(connection.getPlayerUUID());
                        if (player != null) {
                            try {
                                playerStateManager.onPlayerVoicechatConnect(player);
                                PluginManager.instance().onPlayerConnected(player);
                                playerStateManager.evaluateForcedState(player);
                            } finally {
                                connection.setInitialized(true);
                            }
                            Voicechat.LOGGER.info("Player {} ({}) successfully connected to voice chat", player.getName(), connection.getPlayerUUID());
                        }
                        sendPacket(new ConnectionCheckAckPacket(), connection);
                        continue;
                    }

                    ClientConnection conn = getSender(message);
                    if (conn == null) {
                        continue;
                    }

                    if (!packetRateLimiter.allow(conn.getPlayerUUID())) {
                        // Remove the connection and remove the secret, so the player can't continue spamming until the kick is processed
                        connections.remove(conn.getPlayerUUID());
                        connectionSecrets.remove(conn.getPlayerUUID());
                        Voicechat.compatibility.runTask(() -> kickForExceedingRateLimit(conn.getPlayerUUID()));
                        continue;
                    }

                    if (message.getPacket() instanceof MicPacket) {
                        onMicPacket(conn.getPlayerUUID(), (MicPacket) message.getPacket());
                    } else if (message.getPacket() instanceof PingPacket) {
                        PingPacket packet = (PingPacket) message.getPacket();
                        pingManager.onPongPacket(packet);
                    } else if (message.getPacket() instanceof KeepAlivePacket) {
                        conn.setLastKeepAliveResponse(Utils.getMilliseconds());
                    }
                } catch (Exception e) {
                    Voicechat.LOGGER.error("Voice chat server error", e);
                }
            }
        }

        public void close() {
            running = false;
        }
    }

    private void kickForExceedingRateLimit(UUID playerUuid) {
        Player player = server.getPlayer(playerUuid);
        if (player == null) {
            return;
        }
        Voicechat.LOGGER.warn("Player {} exceeded voice chat packet rate limit", player.getDisplayName());
        player.kickPlayer(Voicechat.TRANSLATIONS.rateLimitKickMessage.get());
    }

    public void onMicPacket(UUID playerUuid, MicPacket packet) {
        Player player = server.getPlayer(playerUuid);
        if (player == null) {
            return;
        }
        PlayerState state = playerStateManager.getState(player.getUniqueId());
        if (state == null || state.isForceMuted() || state.isForceDisabled()) {
            return;
        }
        if (!PluginManager.instance().onMicPacket(player, state, packet)) {
            processMicPacket(player, state, packet);
        }
    }

    private void processMicPacket(Player player, PlayerState state, MicPacket packet) {
        if (state.hasGroup()) {
            @Nullable Group group = groupManager.getGroup(state.getGroup());
            processGroupPacket(state, player, packet);
            if (group == null || group.isOpen()) {
                processProximityPacket(state, player, packet);
            }
            return;
        }
        processProximityPacket(state, player, packet);
    }

    private void processGroupPacket(PlayerState senderState, Player sender, MicPacket packet) {
        UUID groupId = senderState.getGroup();
        if (groupId == null) {
            return;
        }
        GroupSoundPacket groupSoundPacket = new GroupSoundPacket(senderState.getUuid(), senderState.getUuid(), packet.getData(), packet.getSequenceNumber(), null);
        for (PlayerState state : playerStateManager.getStates()) {
            if (!groupId.equals(state.getGroup())) {
                continue;
            }
            if (senderState.getUuid().equals(state.getUuid())) {
                continue;
            }
            Player p = server.getPlayer(state.getUuid());
            if (p == null) {
                continue;
            }
            @Nullable ClientConnection connection = getConnection(state.getUuid());
            sendSoundPacket(sender, senderState, p, state, connection, groupSoundPacket, SoundPacketEvent.SOURCE_GROUP);
        }
    }

    private void processProximityPacket(PlayerState senderState, Player sender, MicPacket packet) {
        @Nullable UUID groupId = senderState.getGroup();
        float distance;
        if (packet.isWhispering()) {
            distance = Voicechat.SERVER_CONFIG.whisperDistance.get().floatValue();
        } else {
            distance = Utils.getDefaultDistance();
        }

        distance = PluginManager.instance().getDistance(sender, senderState, packet, distance);

        SoundPacket<?> soundPacket = null;
        String source = null;
        if (sender.getGameMode().equals(GameMode.SPECTATOR)) {
            if (Voicechat.SERVER_CONFIG.spectatorPlayerPossession.get()) {
                Entity camera = sender.getSpectatorTarget();
                if (camera instanceof Player) {
                    Player spectatingPlayer = (Player) camera;
                    if (spectatingPlayer != sender) {
                        PlayerState receiverState = playerStateManager.getState(spectatingPlayer.getUniqueId());
                        if (receiverState == null) {
                            return;
                        }
                        GroupSoundPacket groupSoundPacket = new GroupSoundPacket(senderState.getUuid(), senderState.getUuid(), packet.getData(), packet.getSequenceNumber(), null);
                        @Nullable ClientConnection connection = getConnection(receiverState.getUuid());
                        sendSoundPacket(sender, senderState, spectatingPlayer, receiverState, connection, groupSoundPacket, SoundPacketEvent.SOURCE_SPECTATOR);
                        return;
                    }
                }
            }
            if (Voicechat.SERVER_CONFIG.spectatorInteraction.get()) {
                soundPacket = new LocationSoundPacket(sender.getUniqueId(), sender.getUniqueId(), sender.getEyeLocation(), packet.getData(), packet.getSequenceNumber(), distance, null);
                source = SoundPacketEvent.SOURCE_SPECTATOR;
            }
        }

        if (soundPacket == null) {
            soundPacket = new PlayerSoundPacket(sender.getUniqueId(), sender.getUniqueId(), packet.getData(), packet.getSequenceNumber(), packet.isWhispering(), distance, null);
            source = SoundPacketEvent.SOURCE_PROXIMITY;
        }

        broadcast(ServerPlayerManager.getPlayersInRange(sender.getWorld(), sender.getLocation(), getBroadcastRange(distance), p -> !p.getUniqueId().equals(sender.getUniqueId())), soundPacket, sender, senderState, groupId, source);
    }

    public void sendSoundPacket(@Nullable Player sender, @Nullable PlayerState senderState, Player receiver, PlayerState receiverState, @Nullable ClientConnection connection, SoundPacket<?> soundPacket, String source) {
        if (soundPacket.getData().length > AudioUtils.MAX_OPUS_PAYLOAD_SIZE) {
            CooldownTimer.run("packet_data_too_large", () -> {
                Voicechat.LOGGER.warn("Tried to send a packet that's too large ({} bytes > {} bytes)", soundPacket.getData().length, AudioUtils.MAX_OPUS_PAYLOAD_SIZE);
            });
            return;
        }
        PluginManager.instance().onListenerAudio(receiver.getUniqueId(), soundPacket);

        if (connection == null) {
            return;
        }

        if (receiverState.isDisabledOrForceDisabled() || receiverState.isDisconnected()) {
            return;
        }

        if (PluginManager.instance().onSoundPacket(sender, senderState, receiver, receiverState, soundPacket, source)) {
            return;
        }

        if (!isAudibleFor(sender, receiver, soundPacket)) {
            // Early proximity filter: the receiver is beyond the effective range of
            // the position-based sound (e.g. inside the broadcast range but outside
            // the audible distance). Skipping avoids serializing, encrypting and
            // dispatching a 0-volume frame over UDP for every inaudible receiver.
            return;
        }
        sendPacket(soundPacket, connection);
    }

    /**
     * Returns whether {@code receiver} could actually hear {@code soundPacket}
     * given its position and effective range. Non-positional packets (group,
     * announcement) are always audible and return {@code true}.
     */
    private static boolean isAudibleFor(@Nullable Player sender, Player receiver, SoundPacket<?> soundPacket) {
        if (soundPacket instanceof PlayerSoundPacket) {
            PlayerSoundPacket playerSoundPacket = (PlayerSoundPacket) soundPacket;
            if (sender == null) {
                return true;
            }
            Location senderPos = sender.getLocation();
            Location receiverPos = receiver.getLocation();
            if (!senderPos.getWorld().equals(receiverPos.getWorld())) {
                return false;
            }
            return withinRadiusSquared(senderPos.getX() - receiverPos.getX(), senderPos.getY() - receiverPos.getY(), senderPos.getZ() - receiverPos.getZ(), playerSoundPacket.getDistance());
        } else if (soundPacket instanceof LocationSoundPacket) {
            LocationSoundPacket locationSoundPacket = (LocationSoundPacket) soundPacket;
            Location soundPos = locationSoundPacket.getLocation();
            Location receiverPos = receiver.getLocation();
            if (soundPos.getWorld() == null || !soundPos.getWorld().equals(receiverPos.getWorld())) {
                return true;
            }
            return withinRadiusSquared(soundPos.getX() - receiverPos.getX(), soundPos.getY() - receiverPos.getY(), soundPos.getZ() - receiverPos.getZ(), locationSoundPacket.getDistance());
        }
        return true;
    }

    private static boolean withinRadiusSquared(double dx, double dy, double dz, float radius) {
        return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
    }

    public double getBroadcastRange(float minRange) {
        double broadcastRange = Voicechat.SERVER_CONFIG.broadcastRange.get();
        if (broadcastRange < 0D) {
            broadcastRange = Voicechat.SERVER_CONFIG.voiceChatDistance.get() + 1D;
        }
        return Math.max(broadcastRange, minRange);
    }

    public void broadcast(Collection<Player> players, SoundPacket<?> packet, @Nullable Player sender, @Nullable PlayerState senderState, @Nullable UUID groupId, String source) {
        for (Player player : players) {
            PlayerState state = playerStateManager.getState(player.getUniqueId());
            if (state == null) {
                continue;
            }
            if (state.hasGroup() && state.getGroup().equals(groupId)) {
                continue;
            }
            @Nullable Group receiverGroup = null;
            if (state.hasGroup()) {
                receiverGroup = groupManager.getGroup(state.getGroup());
            }
            if (receiverGroup != null && receiverGroup.isIsolated()) {
                continue;
            }
            @Nullable ClientConnection connection = getConnection(state.getUuid());
            sendSoundPacket(sender, senderState, player, state, connection, packet, source);
        }
    }

    private void sendKeepAlives() {
        long timestamp = Utils.getMilliseconds();

        connections.values().removeIf(connection -> {
            if (timestamp - connection.getLastKeepAliveResponse() >= Voicechat.SERVER_CONFIG.keepAlive.get() * 10L) {
                // Don't call disconnectClient here!
                connectionSecrets.remove(connection.getPlayerUUID());
                Voicechat.LOGGER.info("Player {} timed out", connection.getPlayerUUID());
                Player player = server.getPlayer(connection.getPlayerUUID());
                if (player != null) {
                    Voicechat.LOGGER.info("Reconnecting player {}", player.getName());
                    Voicechat.SERVER.initializePlayerConnection(player);
                } else {
                    Voicechat.LOGGER.error("Reconnecting player {} failed (Could not find player)", connection.getPlayerUUID());
                }
                playerStateManager.onPlayerVoicechatDisconnect(connection.getPlayerUUID());
                PluginManager.instance().onPlayerDisconnected(connection.getPlayerUUID());
                return true;
            }
            return false;
        });

        KeepAlivePacket keepAlivePacket = new KeepAlivePacket();
        for (ClientConnection connection : connections.values()) {
            sendPacket(keepAlivePacket, connection);
        }

    }

    private void pollPermissions() {
        for (ClientConnection connection : connections.values()) {
            // The initial forced state evaluation needs to happen after the connected event
            if (connection.isInitialized()) {
                pollPermissions(connection.getPlayerUUID());
            }
        }
        for (UUID audioSenderPlayer : AudioSenderImpl.getRegisteredPlayers()) {
            pollPermissions(audioSenderPlayer);
        }
    }

    private void pollPermissions(UUID playerUuid) {
        Player player = server.getPlayer(playerUuid);
        if (player != null) {
            playerStateManager.evaluateForcedStateIfPermissionsChanged(player);
        }
    }

    public void evaluateForcedState(UUID playerUuid) {
        Player player = server.getPlayer(playerUuid);
        if (player != null) {
            playerStateManager.evaluateForcedState(player);
        }
    }

    @Nullable
    public ClientConnection getSender(NetworkMessage message) {
        ConnectionSecret connectionSecret = connectionSecrets.getByConnectionId(message.getConnectionId());
        if (connectionSecret == null) {
            return null;
        }
        ClientConnection connection = connections.get(connectionSecret.getPlayerUUID());
        if (connection == null || !connection.getAddress().equals(message.getAddress())) {
            return null;
        }
        return connection;
    }

    public Map<UUID, ClientConnection> getConnections() {
        return connections;
    }

    @Nullable
    public ClientConnection getConnection(UUID playerID) {
        return connections.get(playerID);
    }

    public VoicechatSocket getSocket() {
        return socket;
    }

    public int getPort() {
        return socket.getLocalPort();
    }

    /**
     * Asynchronously serializes and sends {@code packet} to {@code connection}.
     * <p>
     * Serialization (buffer allocation + AES-GCM encrypt) and the blocking
     * {@code DatagramSocket.send()} call are both offloaded to
     * {@link #sendExecutor}, so {@code ProcessThread} is never stalled waiting
     * for kernel I/O while 20 players are being served simultaneously.
     *
     * @param packet     the packet to send
     * @param connection the connection to send the packet to
     * @return {@code true} — submission to the executor always succeeds unless the
     *         server is shutting down, in which case the packet is silently dropped
     */
    public boolean sendPacket(Packet<?> packet, ClientConnection connection) {
        NetworkMessage message = new NetworkMessage(packet);
        try {
            sendExecutor.execute(() -> {
                try {
                    connection.send(Server.this, message);
                } catch (Exception e) {
                    Voicechat.LOGGER.error("Failed to send voice chat packet to {}", connection.getPlayerUUID());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Server is shutting down — silently drop the packet
        }
        return true;
    }

    /**
     * Sends the packet. You must handle potential errors
     *
     * @param packet     the packet to send
     * @param connection the connection to send the packet to
     * @throws Exception if an I/O error occurs
     */
    public void sendPacketRaw(Packet<?> packet, ClientConnection connection) throws Exception {
        connection.send(this, new NetworkMessage(packet));
    }

    private void queuePacket(RawUdpPacket rawUdpPacket) {
        if (!packetQueue.offer(new UdpPacket(rawUdpPacket.getData(), rawUdpPacket.getSocketAddress(), Utils.getMilliseconds()))) {
            CooldownTimer.run("packet_queue_full", () -> {
                Voicechat.LOGGER.warn("Voice chat packet queue is full ({} packets) - dropping packets", packetQueue.size());
            });
        }
    }

    public PacketRateLimiter getPacketRateLimiter() {
        return packetRateLimiter;
    }

    public PingManager getPingManager() {
        return pingManager;
    }

    public PlayerStateManager getPlayerStateManager() {
        return playerStateManager;
    }

    public ServerGroupManager getGroupManager() {
        return groupManager;
    }

    public ServerCategoryManager getCategoryManager() {
        return categoryManager;
    }

}
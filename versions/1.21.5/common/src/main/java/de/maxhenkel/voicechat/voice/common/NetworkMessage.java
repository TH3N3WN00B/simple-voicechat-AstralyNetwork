package de.maxhenkel.voicechat.voice.common;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.debug.PingHandler;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import de.maxhenkel.voicechat.voice.server.Server;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.ShortBufferException;
import java.net.SocketAddress;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.UUID;

public class NetworkMessage {

    public static final byte MAGIC_BYTE = (byte) 0b11111111;

    private final long timestamp;
    private Packet<? extends Packet> packet;
    private SocketAddress address;

    public NetworkMessage(long timestamp, Packet<?> packet) {
        this(timestamp);
        this.packet = packet;
    }

    public NetworkMessage(Packet<?> packet) {
        this(System.currentTimeMillis());
        this.packet = packet;
    }

    private NetworkMessage(long timestamp) {
        this.timestamp = timestamp;
    }

    @Nonnull
    public Packet<? extends Packet> getPacket() {
        return packet;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getTTL() {
        return packet.getTTL();
    }

    public SocketAddress getAddress() {
        return address;
    }

    // ─── Packet factory — no reflection on hot path ───────────────────────────
    // The original code used getDeclaredConstructor().newInstance() (reflection) on
    // every received UDP packet. Reflection has ~10-50x overhead vs direct dispatch.
    // With 20 players sending 50 packets/sec = 1000 reflected calls/sec on server.
    // This factory uses a plain switch statement for O(1) zero-reflection dispatch.
    @SuppressWarnings("unchecked")
    @Nullable
    private static Packet<? extends Packet<?>> createPacket(byte type) {
        return switch (type) {
            case 0x1 -> new MicPacket();
            case 0x2 -> new PlayerSoundPacket();
            case 0x3 -> new GroupSoundPacket();
            case 0x4 -> new LocationSoundPacket();
            case 0x5 -> new AuthenticatePacket();
            case 0x6 -> new AuthenticateAckPacket();
            case 0x7 -> new PingPacket();
            case 0x8 -> new KeepAlivePacket();
            case 0x9 -> new ConnectionCheckPacket();
            case 0xA -> new ConnectionCheckAckPacket();
            case 0xB -> new AnnouncementSoundPacket();
            default  -> null;
        };
    }

    private static byte getPacketType(Packet<? extends Packet> packet) {
        if (packet instanceof MicPacket)              return 0x1;
        if (packet instanceof PlayerSoundPacket)      return 0x2;
        if (packet instanceof GroupSoundPacket)       return 0x3;
        if (packet instanceof LocationSoundPacket)    return 0x4;
        if (packet instanceof AuthenticatePacket)     return 0x5;
        if (packet instanceof AuthenticateAckPacket)  return 0x6;
        if (packet instanceof PingPacket)             return 0x7;
        if (packet instanceof KeepAlivePacket)        return 0x8;
        if (packet instanceof ConnectionCheckPacket)  return 0x9;
        if (packet instanceof ConnectionCheckAckPacket) return (byte) 0xA;
        if (packet instanceof AnnouncementSoundPacket)  return (byte) 0xB;
        return -1;
    }

    @Nullable
    public static NetworkMessage readPacketServer(RawUdpPacket packet, Server server) {
        try {
            byte[] data = packet.getData();
            FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            if (b.readByte() != MAGIC_BYTE) {
                Voicechat.LOGGER.debug("Received invalid packet from {}", packet.getSocketAddress());
                return null;
            }
            UUID playerID = b.readUUID();
            Secret secret = server.getSecretOrNull(playerID);
            if (secret == null) {
                if (PingHandler.onPacket(server, packet.getSocketAddress(), playerID, b)) {
                    return null;
                }
                // Ignore packets if they are not from a player that has a secret
                Voicechat.LOGGER.debug("Player {} does not have a secret", playerID);
                return null;
            }
            int length = b.readVarInt();
            if (length < 0 || length > Utils.MAX_VOICE_CHAT_PACKET_SIZE || length > b.readableBytes()) {
                Voicechat.LOGGER.debug("Received invalid packet length from {}", packet.getSocketAddress());
                return null;
            }
            return readFromBytes(packet.getSocketAddress(), secret, data, b.readerIndex(), length, packet.getTimestamp());
        } catch (DecoderException | IndexOutOfBoundsException e) {
            Voicechat.LOGGER.debug("Received invalid packet from {}", packet.getSocketAddress());
            return null;
        } catch (Exception e) {
            Voicechat.LOGGER.debug("Failed to read server packet from {}", packet.getSocketAddress());
            return null;
        }
    }

    @Nullable
    public static NetworkMessage readFromBytes(SocketAddress socketAddress, Secret secret, byte[] encryptedPayload, long timestamp) {
        return readFromBytes(socketAddress, secret, encryptedPayload, 0, encryptedPayload.length, timestamp);
    }

    @Nullable
    public static NetworkMessage readFromBytes(SocketAddress socketAddress, Secret secret, byte[] encrypted, int offset, int length, long timestamp) {
        byte[] decrypt;
        try {
            decrypt = secret.decrypt(encrypted, offset, length);
        } catch (Exception e) {
            // Return null if decryption fails due to a wrong secret
            Voicechat.LOGGER.debug("Failed to decrypt packet from {}", socketAddress);
            return null;
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(decrypt));
        byte packetType = buffer.readByte();
        Packet<? extends Packet<?>> p = createPacket(packetType);
        if (p == null) {
            Voicechat.LOGGER.debug("Got invalid packet ID {}", packetType);
            return null;
        }

        NetworkMessage message = new NetworkMessage(timestamp);
        message.address = socketAddress;
        message.packet = p.fromBytes(buffer);

        return message;
    }

    public byte[] writeServer(Server server, ClientConnection connection) throws InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException, ShortBufferException {
        Secret secret = server.getSecretOrNull(connection.getPlayerUUID());
        if (secret == null) {
            // The player disconnected in the meantime - don't resurrect a secret for them
            throw new IllegalStateException("No secret for " + connection.getPlayerUUID());
        }
        return secret.encryptFramed(MAGIC_BYTE, serialize());
    }

    public byte[] write(Secret secret) throws InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException {
        return secret.encrypt(serialize());
    }

    private byte[] serialize() {
        SoundPacket<?> soundPacket = packet instanceof SoundPacket<?> sp ? sp : null;
        if (soundPacket != null) {
            byte[] cached = soundPacket.getSerializedCache();
            if (cached != null) {
                return cached;
            }
        }
        byte type = getPacketType(packet);
        if (type < 0) {
            throw new IllegalArgumentException("Packet type not found");
        }
        // Audio packets: an Opus frame is <= 1275 bytes plus envelope. Control packets stay small.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(soundPacket != null ? 1280 : 64));
        buffer.writeByte(type);
        packet.toBytes(buffer);
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        if (soundPacket != null) {
            soundPacket.setSerializedCache(bytes);
        }
        return bytes;
    }

}

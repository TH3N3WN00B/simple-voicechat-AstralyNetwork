package de.maxhenkel.voicechat.voice.common;

import javax.annotation.Nullable;
import java.util.UUID;

public abstract class SoundPacket<T extends SoundPacket> implements Packet<T> {

    public static final byte WHISPER_MASK = 0b1;
    public static final byte HAS_CATEGORY_MASK = 0b10;

    protected UUID channelId;
    protected UUID sender;
    protected byte[] data;
    protected long sequenceNumber;
    @Nullable
    protected String category;
    /**
     * Cached plaintext wire format. A sound packet is serialized once and then
     * encrypted separately for every receiver in range.
     */
    @Nullable
    private volatile byte[] serializedCache;

    public SoundPacket(UUID channelId, UUID sender, byte[] data, long sequenceNumber, @Nullable String category) {
        this.channelId = channelId;
        this.sender = sender;
        this.data = data;
        this.sequenceNumber = sequenceNumber;
        this.category = category;
    }

    public SoundPacket() {

    }

    public UUID getChannelId() {
        return channelId;
    }

    public UUID getSender() {
        return sender;
    }

    public byte[] getData() {
        return data;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    @Nullable
    public String getCategory() {
        return category;
    }

    @Nullable
    public byte[] getSerializedCache() {
        return serializedCache;
    }

    public void setSerializedCache(byte[] serializedCache) {
        this.serializedCache = serializedCache;
    }

    protected boolean hasFlag(byte data, byte mask) {
        return (data & mask) != 0b0;
    }

    protected byte setFlag(byte data, byte mask) {
        return (byte) (data | mask);
    }

}

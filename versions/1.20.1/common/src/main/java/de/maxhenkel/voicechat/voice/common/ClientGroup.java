package de.maxhenkel.voicechat.voice.common;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.plugins.impl.GroupImpl;
import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class ClientGroup {

    private final UUID id;
    private final String name;
    private final boolean hasPassword;
    private final boolean persistent;
    private final boolean hidden;
    private final de.maxhenkel.voicechat.api.Group.Type type;
    // Fork extension, not part of the original wire format (see GroupRolesPacket)
    @Nullable
    private volatile UUID ownerUuid;
    private volatile List<UUID> admins;

    public ClientGroup(UUID id, String name, boolean hasPassword, boolean persistent, boolean hidden, de.maxhenkel.voicechat.api.Group.Type type) {
        this(id, name, hasPassword, persistent, hidden, type, null, new ArrayList<>());
    }

    public ClientGroup(UUID id, String name, boolean hasPassword, boolean persistent, boolean hidden, de.maxhenkel.voicechat.api.Group.Type type, @Nullable UUID ownerUuid, List<UUID> admins) {
        this.id = id;
        this.name = name;
        this.hasPassword = hasPassword;
        this.persistent = persistent;
        this.hidden = hidden;
        this.type = type;
        this.ownerUuid = ownerUuid;
        this.admins = admins == null ? new ArrayList<>() : admins;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean hasPassword() {
        return hasPassword;
    }

    public boolean isPersistent() {
        return persistent;
    }

    public boolean isHidden() {
        return hidden;
    }

    public Group.Type getType() {
        return type;
    }

    @Nullable
    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public List<UUID> getAdmins() {
        return admins;
    }

    public void setRoles(@Nullable UUID ownerUuid, List<UUID> admins) {
        this.ownerUuid = ownerUuid;
        this.admins = admins == null ? new ArrayList<>() : admins;
    }

    public boolean isOwner(UUID playerUuid) {
        return ownerUuid != null && ownerUuid.equals(playerUuid);
    }

    public boolean isAdmin(UUID playerUuid) {
        return admins.contains(playerUuid) || isOwner(playerUuid);
    }

    public static ClientGroup fromBytes(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf(512);
        boolean hasPassword = buf.readBoolean();
        boolean persistent = buf.readBoolean();
        boolean hidden = buf.readBoolean();
        de.maxhenkel.voicechat.api.Group.Type type = GroupImpl.TypeImpl.fromInt(buf.readShort());
        return new ClientGroup(id, name, hasPassword, persistent, hidden, type);
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(name, 512);
        buf.writeBoolean(hasPassword);
        buf.writeBoolean(persistent);
        buf.writeBoolean(hidden);
        buf.writeShort(GroupImpl.TypeImpl.toInt(type));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ClientGroup group = (ClientGroup) o;

        return Objects.equals(id, group.id);
    }
}

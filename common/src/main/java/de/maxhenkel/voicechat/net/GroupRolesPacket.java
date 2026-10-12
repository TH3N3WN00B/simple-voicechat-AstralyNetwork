package de.maxhenkel.voicechat.net;

import de.maxhenkel.voicechat.Voicechat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fork extension: owner and administrators of a group.
 * <p>
 * This is intentionally a separate packet instead of extra fields in {@link AddGroupPacket}, so the wire
 * format of the original packets stays untouched and fork clients keep working on servers running the
 * original Simple Voice Chat (and the other way around).
 */
public class GroupRolesPacket implements Packet<GroupRolesPacket> {

    public static final CustomPacketPayload.Type<GroupRolesPacket> GROUP_ROLES = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Voicechat.MODID, "group_roles"));

    private static final int MAX_ADMINS = 1024;

    private UUID groupId;
    @Nullable
    private UUID owner;
    private List<UUID> admins = new ArrayList<>();

    public GroupRolesPacket() {

    }

    public GroupRolesPacket(UUID groupId, @Nullable UUID owner, List<UUID> admins) {
        this.groupId = groupId;
        this.owner = owner;
        this.admins = admins;
    }

    public UUID getGroupId() {
        return groupId;
    }

    @Nullable
    public UUID getOwner() {
        return owner;
    }

    public List<UUID> getAdmins() {
        return admins;
    }

    @Override
    public GroupRolesPacket fromBytes(FriendlyByteBuf buf) {
        groupId = buf.readUUID();
        owner = buf.readBoolean() ? buf.readUUID() : null;
        int count = Math.min(buf.readVarInt(), MAX_ADMINS);
        admins = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            admins.add(buf.readUUID());
        }
        return this;
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUUID(groupId);
        buf.writeBoolean(owner != null);
        if (owner != null) {
            buf.writeUUID(owner);
        }
        int count = Math.min(admins.size(), MAX_ADMINS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeUUID(admins.get(i));
        }
    }

    @Override
    public Type<GroupRolesPacket> type() {
        return GROUP_ROLES;
    }

}

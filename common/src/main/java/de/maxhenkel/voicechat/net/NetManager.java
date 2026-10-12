package de.maxhenkel.voicechat.net;

import de.maxhenkel.voicechat.Voicechat;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public abstract class NetManager {

    public Channel<UpdateStatePacket> updateStateChannel;
    public Channel<PlayerStatePacket> playerStateChannel;
    public Channel<PlayerStatesPacket> playerStatesChannel;
    public Channel<RemovePlayerStatePacket> removePlayerStateChannel;
    public Channel<SecretPacket> secretChannel;
    public Channel<RequestSecretPacket> requestSecretChannel;
    public Channel<AddGroupPacket> addGroupChannel;
    public Channel<RemoveGroupPacket> removeGroupChannel;
    public Channel<JoinGroupPacket> joinGroupChannel;
    public Channel<CreateGroupPacket> createGroupChannel;
    public Channel<LeaveGroupPacket> leaveGroupChannel;
    public Channel<JoinedGroupPacket> joinedGroupChannel;
    public Channel<ManageGroupPacket> manageGroupChannel;
    public Channel<GroupRolesPacket> groupRolesChannel;
    public Channel<AddCategoryPacket> addCategoryChannel;
    public Channel<RemoveCategoryPacket> removeCategoryChannel;

    public void init() {
        updateStateChannel = registerReceiver(UpdateStatePacket.class, false, true);
        playerStateChannel = registerReceiver(PlayerStatePacket.class, true, false);
        playerStatesChannel = registerReceiver(PlayerStatesPacket.class, true, false);
        removePlayerStateChannel = registerReceiver(RemovePlayerStatePacket.class, true, false);
        secretChannel = registerReceiver(SecretPacket.class, true, false);
        requestSecretChannel = registerReceiver(RequestSecretPacket.class, false, true);
        addGroupChannel = registerReceiver(AddGroupPacket.class, true, false);
        removeGroupChannel = registerReceiver(RemoveGroupPacket.class, true, false);
        joinGroupChannel = registerReceiver(JoinGroupPacket.class, false, true);
        createGroupChannel = registerReceiver(CreateGroupPacket.class, false, true);
        leaveGroupChannel = registerReceiver(LeaveGroupPacket.class, false, true);
        joinedGroupChannel = registerReceiver(JoinedGroupPacket.class, true, false);
        manageGroupChannel = registerReceiver(ManageGroupPacket.class, false, true);
        groupRolesChannel = registerReceiver(GroupRolesPacket.class, true, false);
        addCategoryChannel = registerReceiver(AddCategoryPacket.class, true, false);
        removeCategoryChannel = registerReceiver(RemoveCategoryPacket.class, true, false);
    }

    public abstract <T extends Packet<T>> Channel<T> registerReceiver(Class<T> packetType, boolean toClient, boolean toServer);

    public static void sendToClient(ServerPlayer player, Packet<?> packet) {
        if (!Voicechat.SERVER.isCompatible(player)) {
            return;
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        packet.toBytes(buffer);
        player.connection.send(new ClientboundCustomPayloadPacket(packet.getIdentifier(), buffer));
    }

    /**
     * Sends a fork-only packet. Clients without the fork extension may not know the channel,
     * in which case sending can fail - that must never affect the regular voice chat flow.
     */
    public static void sendToClientOptional(ServerPlayer player, Packet<?> packet) {
        try {
            sendToClient(player, packet);
        } catch (Throwable e) {
            Voicechat.LOGGER.debug("Failed to send optional packet {} to {}", packet.getClass().getSimpleName(), player.getName().getString(), e);
        }
    }

    public interface ServerReceiver<T extends Packet<T>> {
        void onPacket(MinecraftServer server, ServerPlayer player, ServerGamePacketListenerImpl handler, T packet);
    }

}

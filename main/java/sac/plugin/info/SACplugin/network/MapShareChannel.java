package sac.plugin.info.SACplugin.network;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import sac.plugin.info.SACplugin.SACplugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** SAC Mod地図共有の招待、承認、近距離転送を仲介します。 */
public final class MapShareChannel implements PluginMessageListener, Listener {
    public static final String CHANNEL = "sacmod:map_share";
    private static final int MAX_MAP_BYTES = 16 * 1024 * 1024;
    private static final int MAX_CHUNK_BYTES = 24_000;
    private static final double SHARE_RADIUS_SQUARED = 25.0;
    private static final long INVITE_LIFETIME_MS = 60_000L;
    private final SACplugin plugin;
    private final Set<UUID> moddedPlayers = new HashSet<>();
    private final Set<UUID> markerAppliedThisSession = new HashSet<>();
    private final Map<UUID, Invitation> invitations = new HashMap<>();
    private final Map<UUID, Transfer> transfers = new HashMap<>();
    private final Map<UUID, MapPin> pins = new HashMap<>();

    public MapShareChannel(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        try (DataInputStream envelope = new DataInputStream(new ByteArrayInputStream(message))) {
            int length = readVarInt(envelope);
            if (length < 1 || length > MAX_CHUNK_BYTES + 512 || length != envelope.available()) return;
            byte[] payload = envelope.readNBytes(length);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
                expireInvitations();
                switch (in.readUnsignedByte()) {
                    case 0 -> {
                        UUID playerId = player.getUniqueId();
                        moddedPlayers.add(playerId);
                        if (markerAppliedThisSession.add(playerId)) {
                            updateModMarker(player);
                        }
                    }
                    case 1 -> sendNearbyPlayers(player);
                    case 2 -> createInvitation(player, in);
                    case 3 -> decideInvitation(player, in);
                    case 4 -> startTransfer(player, in);
                    case 5 -> receiveChunk(player, in);
                    case 6 -> updatePin(player, in);
                    case 7 -> sendPartyPins(player);
                    case 8 -> removePin(player);
                    default -> { }
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("SAC Modの地図共有パケットを読み取れませんでした: " + exception.getMessage());
        }
    }

    /** 汎用Modの利用者だけTABリストに印を付けます。 */
    private void updateModMarker(Player player) {
        if (!player.isOnline() || !moddedPlayers.contains(player.getUniqueId())) return;
        player.setPlayerListName("§b◆ §r" + player.getName());
    }
    private void updatePin(Player player, DataInputStream in) throws Exception {
        String dimension = in.readUTF();
        int x = in.readInt();
        int z = in.readInt();
        int color = in.readInt() & 0xFFFFFF;
        UUID playerId = player.getUniqueId();
        if (!moddedPlayers.contains(playerId) || dimension.isBlank() || dimension.length() > 256) return;
        pins.put(playerId, new MapPin(playerId, player.getName(), dimension, x, z, color));
        sendPartyPins(player);
    }

    private void removePin(Player player) throws Exception {
        if (!moddedPlayers.contains(player.getUniqueId())) return;
        pins.remove(player.getUniqueId());
        sendPartyPins(player);
    }
    private void sendPartyPins(Player requester) throws Exception {
        Set<UUID> partyIds = new HashSet<>();
        partyIds.add(requester.getUniqueId());
        List<UUID> memberIds = plugin.getPartyManager().getPartyMembers(requester);
        if (memberIds != null) partyIds.addAll(memberIds);
        List<MapPin> visiblePins = new ArrayList<>();
        for (UUID memberId : partyIds) {
            if (!moddedPlayers.contains(memberId)) continue;
            Player member = Bukkit.getPlayer(memberId);
            MapPin pin = pins.get(memberId);
            if (member != null && member.isOnline() && pin != null) visiblePins.add(pin);
        }
        byte[] packet = frame(out -> {
            out.writeByte(26);
            out.writeInt(visiblePins.size());
            for (MapPin pin : visiblePins) {
                writeUuid(out, pin.playerId);
                out.writeUTF(pin.playerName);
                out.writeUTF(pin.dimension);
                out.writeInt(pin.x);
                out.writeInt(pin.z);
                out.writeInt(pin.color);
            }
        });
        for (UUID memberId : partyIds) {
            if (!moddedPlayers.contains(memberId)) continue;
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) send(member, packet);
        }
    }
    private void sendNearbyPlayers(Player requester) throws Exception {
        Set<Player> nearby = new HashSet<>();
        for (UUID id : Set.copyOf(moddedPlayers)) {
            Player target = Bukkit.getPlayer(id);
            if (target == null || !target.isOnline() || target.equals(requester) || !isNearby(requester, target)) continue;
            nearby.add(target);
        }
        send(requester, frame(out -> {
            out.writeByte(20);
            out.writeInt(nearby.size());
            for (Player target : nearby) {
                writeUuid(out, target.getUniqueId());
                out.writeUTF(target.getName());
                out.writeFloat((float) Math.sqrt(requester.getLocation().distanceSquared(target.getLocation())));
            }
        }));
    }

    private void createInvitation(Player sender, DataInputStream in) throws Exception {
        UUID inviteId = readUuid(in);
        UUID targetId = readUuid(in);
        String dimension = in.readUTF();
        Player target = Bukkit.getPlayer(targetId);
        if (!moddedPlayers.contains(sender.getUniqueId()) || target == null || !target.isOnline()
                || !moddedPlayers.contains(targetId) || !isNearby(sender, target)
                || dimension.isBlank() || dimension.length() > 256) {
            sendStatus(sender, inviteId, 4);
            return;
        }
        Invitation invitation = new Invitation(sender.getUniqueId(), targetId, dimension,
                System.currentTimeMillis());
        invitations.put(inviteId, invitation);
        send(target, frame(out -> {
            out.writeByte(21);
            writeUuid(out, inviteId);
            writeUuid(out, sender.getUniqueId());
            out.writeUTF(sender.getName());
            out.writeUTF(dimension);
        }));
        sendStatus(sender, inviteId, 0);
    }

    private void decideInvitation(Player target, DataInputStream in) throws Exception {
        UUID inviteId = readUuid(in);
        boolean accepted = in.readBoolean();
        Invitation invitation = invitations.get(inviteId);
        if (invitation == null || !invitation.targetId.equals(target.getUniqueId())) return;
        Player sender = Bukkit.getPlayer(invitation.senderId);
        if (!accepted) {
            invitations.remove(inviteId);
            if (sender != null && sender.isOnline()) sendStatus(sender, inviteId, 1);
            return;
        }
        if (sender == null || !sender.isOnline() || !isNearby(sender, target)) {
            invitations.remove(inviteId);
            if (sender != null && sender.isOnline()) sendStatus(sender, inviteId, 4);
            return;
        }
        invitation.accepted = true;
        send(sender, frame(out -> {
            out.writeByte(22);
            writeUuid(out, inviteId);
        }));
    }

    private void startTransfer(Player sender, DataInputStream in) throws Exception {
        UUID inviteId = readUuid(in);
        UUID targetId = readUuid(in);
        String dimension = in.readUTF();
        int totalBytes = in.readInt();
        int chunkCount = in.readInt();
        Invitation invitation = invitations.get(inviteId);
        int expectedChunks = totalBytes <= 0 ? 0 : (totalBytes + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;
        Player target = Bukkit.getPlayer(targetId);
        if (invitation == null || !invitation.accepted || !invitation.senderId.equals(sender.getUniqueId())
                || !invitation.targetId.equals(targetId) || !invitation.dimension.equals(dimension)
                || target == null || !target.isOnline() || !isNearby(sender, target)
                || totalBytes <= 0 || totalBytes > MAX_MAP_BYTES || chunkCount != expectedChunks) {
            invitations.remove(inviteId);
            sendStatus(sender, inviteId, 4);
            return;
        }
        transfers.put(inviteId, new Transfer(invitation, totalBytes, chunkCount));
    }

    private void receiveChunk(Player sender, DataInputStream in) throws Exception {
        UUID inviteId = readUuid(in);
        UUID targetId = readUuid(in);
        int index = in.readInt();
        int length = in.readInt();
        if (length <= 0 || length > MAX_CHUNK_BYTES || length > in.available()) return;
        byte[] chunk = in.readNBytes(length);
        Transfer transfer = transfers.get(inviteId);
        if (transfer == null || !transfer.invitation.senderId.equals(sender.getUniqueId())
                || !transfer.invitation.targetId.equals(targetId) || index < 0
                || index >= transfer.chunks.length || transfer.chunks[index] != null) return;
        transfer.chunks[index] = chunk;
        transfer.received++;
        if (transfer.received == transfer.chunks.length) {
            transfers.remove(inviteId);
            relay(sender, inviteId, transfer);
        }
    }

    private void relay(Player sender, UUID inviteId, Transfer transfer) {
        Player target = Bukkit.getPlayer(transfer.invitation.targetId);
        if (target == null || !target.isOnline() || !isNearby(sender, target)) {
            invitations.remove(inviteId);
            sendStatus(sender, inviteId, 4);
            return;
        }
        ByteArrayOutputStream map = new ByteArrayOutputStream(transfer.totalBytes);
        for (byte[] chunk : transfer.chunks) {
            if (chunk == null) return;
            map.writeBytes(chunk);
        }
        if (map.size() != transfer.totalBytes) return;
        byte[] header = frame(out -> {
            out.writeByte(24);
            writeUuid(out, inviteId);
            writeUuid(out, sender.getUniqueId());
            out.writeUTF(sender.getName());
            out.writeUTF(transfer.invitation.dimension);
            out.writeInt(transfer.totalBytes);
            out.writeInt(transfer.chunks.length);
        });
        send(target, header);
        for (int i = 0; i < transfer.chunks.length; i++) {
            final int chunkIndex = i;
            byte[] chunk = transfer.chunks[i];
            send(target, frame(out -> {
                out.writeByte(25);
                writeUuid(out, inviteId);
                out.writeInt(chunkIndex);
                out.writeInt(chunk.length);
                out.write(chunk);
            }));
        }
        invitations.remove(inviteId);
        sendStatus(sender, inviteId, 3);
    }

    private boolean isNearby(Player first, Player second) {
        return first.getWorld() == second.getWorld()
                && first.getLocation().distanceSquared(second.getLocation()) <= SHARE_RADIUS_SQUARED;
    }

    private void expireInvitations() {
        long now = System.currentTimeMillis();
        invitations.entrySet().removeIf(entry -> {
            if (now - entry.getValue().createdAt <= INVITE_LIFETIME_MS) return false;
            Player sender = Bukkit.getPlayer(entry.getValue().senderId);
            if (sender != null && sender.isOnline()) sendStatus(sender, entry.getKey(), 2);
            transfers.remove(entry.getKey());
            return true;
        });
    }

    private void sendStatus(Player player, UUID inviteId, int result) {
        send(player, frame(out -> {
            out.writeByte(23);
            writeUuid(out, inviteId);
            out.writeByte(result);
        }));
    }

    private void send(Player player, byte[] payload) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 5);
        writeVarInt(bytes, payload.length);
        bytes.writeBytes(payload);
        player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        moddedPlayers.remove(id);
        markerAppliedThisSession.remove(id);
        pins.remove(id);
        event.getPlayer().setPlayerListName(event.getPlayer().getName());
        invitations.entrySet().removeIf(entry -> entry.getValue().senderId.equals(id)
                || entry.getValue().targetId.equals(id));
        transfers.entrySet().removeIf(entry -> entry.getValue().invitation.senderId.equals(id)
                || entry.getValue().invitation.targetId.equals(id));
    }

    private record MapPin(UUID playerId, String playerName, String dimension, int x, int z, int color) { }

    private static byte[] frame(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                writer.write(out);
            }
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws Exception {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws Exception {
        return new UUID(in.readLong(), in.readLong());
    }

    private static int readVarInt(DataInputStream in) throws Exception {
        int value = 0;
        int position = 0;
        byte current;
        do {
            current = in.readByte();
            value |= (current & 0x7F) << position;
            position += 7;
            if (position >= 35) throw new IllegalArgumentException("invalid varint");
        } while ((current & 0x80) != 0);
        return value;
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & -128) != 0) {
            out.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws Exception;
    }

    private static final class Invitation {
        final UUID senderId;
        final UUID targetId;
        final String dimension;
        final long createdAt;
        boolean accepted;

        Invitation(UUID senderId, UUID targetId, String dimension, long createdAt) {
            this.senderId = senderId;
            this.targetId = targetId;
            this.dimension = dimension;
            this.createdAt = createdAt;
        }
    }

    private static final class Transfer {
        final Invitation invitation;
        final int totalBytes;
        final byte[][] chunks;
        int received;

        Transfer(Invitation invitation, int totalBytes, int chunkCount) {
            this.invitation = invitation;
            this.totalBytes = totalBytes;
            this.chunks = new byte[chunkCount][];
        }
    }
}
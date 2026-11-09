package sac.plugin.info.SACplugin.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.npc.SAOQuestData;
import sac.plugin.info.SACplugin.mob.SAOMobData;
import sac.plugin.info.SACplugin.item.SAOGeneralItemData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.TreeMap;

/** SAC Modからの要求に応じ、本人のクエスト・熟練度と、同じパーティにいるオンラインメンバーの情報を返します。 */
public final class PlayerDataChannel implements PluginMessageListener {
    public static final String CHANNEL = "sacmod:player_data";
    private static final int PROTOCOL = 1;
    private final SACplugin plugin;

    public PlayerDataChannel(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            if (readVarInt(input) != PROTOCOL) return;
            PlayerData data = plugin.getPlayerManager().getStats(player);
            if (data == null) return;
            player.sendPluginMessage(plugin, CHANNEL, encode(data, player));
        } catch (Exception exception) {
            plugin.getLogger().warning("SAC Mod向けデータ要求を処理できませんでした: " + exception.getMessage());
        }
    }

    private byte[] encode(PlayerData data, Player player) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("protocol", PROTOCOL);
        JsonObject questJson = new JsonObject();
        String questId = data.currentQuestId == null ? "" : data.currentQuestId;
        SAOQuestData quest = questId.isEmpty() ? null : plugin.getQuestManager().getQuest(questId);
        boolean active = quest != null;
        questJson.addProperty("active", active);
        questJson.addProperty("name", active && quest.questName != null ? quest.questName : "");
        questJson.addProperty("target", active ? targetName(quest) : "");
        questJson.addProperty("type", active && quest.type != null ? quest.type.toUpperCase(java.util.Locale.ROOT) : "UNKNOWN");
        questJson.addProperty("progress", active ? data.questProgress : 0);
        questJson.addProperty("required", active ? quest.requiredAmount : 0);
        questJson.addProperty("completed", active && data.questCompleted);
        root.add("quest", questJson);

        JsonObject skills = new JsonObject();
        Map<String, Integer> ordered = data.proficiency == null ? Map.of() : new TreeMap<>(data.proficiency);
        for (Map.Entry<String, Integer> entry : ordered.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) continue;
            JsonObject value = new JsonObject();
            value.addProperty("level", entry.getValue() == null ? 0 : entry.getValue());
            Integer exp = data.proficiencyExp == null ? null : data.proficiencyExp.get(entry.getKey());
            value.addProperty("exp", exp == null ? 0 : exp);
            Integer progress = data.usedDurability == null ? null : data.usedDurability.get(entry.getKey());
            value.addProperty("progress", progress == null ? -1 : progress);
            skills.add(entry.getKey(), value);
        }
        root.add("skills", skills);

        JsonArray party = new JsonArray();
        List<UUID> memberIds = plugin.getPartyManager().getPartyMembers(player);
        if (memberIds != null) {
            for (UUID memberId : List.copyOf(memberIds)) {
                Player member = Bukkit.getPlayer(memberId);
                if (member == null || !member.isOnline()) continue;
                PlayerData memberData = plugin.getPlayerManager().getStats(member);
                Location location = member.getLocation();
                JsonObject memberJson = new JsonObject();
                memberJson.addProperty("uuid", memberId.toString());
                memberJson.addProperty("name", member.getName());
                memberJson.addProperty("hp", memberData == null ? member.getHealth() : memberData.currentHP);
                memberJson.addProperty("maxHp", memberData == null ? member.getMaxHealth() : memberData.maxHP);
                memberJson.addProperty("world", location.getWorld() == null ? "" : location.getWorld().getName());
                memberJson.addProperty("x", location.getX());
                memberJson.addProperty("y", location.getY());
                memberJson.addProperty("z", location.getZ());
                party.add(memberJson);
            }
        }
        root.add("party", party);
        byte[] json = root.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream(json.length + 5);
        writeVarInt(output, json.length);
        output.write(json);
        return output.toByteArray();
    }

    private String targetName(SAOQuestData quest) {
        String targetId = quest.targetId == null ? "" : quest.targetId;
        if ("MOB".equalsIgnoreCase(quest.type)) {
            SAOMobData mob = plugin.getSaoMobManager().getMobData(targetId);
            if (mob != null && mob.name != null && !mob.name.isBlank()) return mob.name;
        } else if ("ITEM".equalsIgnoreCase(quest.type)) {
            SAOGeneralItemData item = plugin.getSaoItemManager().getGeneralItem(targetId);
            if (item != null && item.name != null && !item.name.isBlank()) return item.name;
        }
        return targetId;
    }

    private void writeVarInt(ByteArrayOutputStream output, int value) {
        while ((value & -128) != 0) {
            output.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private int readVarInt(DataInputStream input) throws Exception {
        int value = 0;
        int position = 0;
        byte current;
        do {
            current = input.readByte();
            value |= (current & 0x7F) << position;
            position += 7;
            if (position >= 35) throw new IllegalArgumentException("invalid protocol varint");
        } while ((current & 0x80) != 0);
        return value;
    }
}
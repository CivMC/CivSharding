package net.civmc.shards.api.mirror;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Where one thing that moves is, on the shard that owns it.
 *
 * <p>Everything the mirror drew before this either does not move - a block, a frame, a stand - or is
 * a person. What is left is most of what makes a place look inhabited: a minecart running the rail
 * beside a seam, a farm's animals, a dropped item sitting where somebody died. A border with all the
 * buildings drawn and none of this reads as a diorama.</p>
 *
 * <p>Sent at the rate positions are, and like a player's position it is only ever the latest: a
 * message that arrives late is replaced rather than applied, so nothing is missed by dropping one.</p>
 *
 * @param uuid the owner's own id for it, which is how one announcement is matched to the last. It is
 *     <strong>not</strong> what the far side draws it under: every shard's world began as a copy of
 *     one map, so a cow from before the split exists on every shard with this same uuid
 * @param type the Bukkit {@code EntityType} name, which is turned back into a protocol type on the
 *     far side. A name that version cannot make is not drawn rather than drawn as something else
 * @param headYaw where the head is turned, which the body lags behind. Meaningless for a minecart
 *     and harmless there
 * @param equipment what it is wearing and holding, by {@code EquipmentSlot} name, each one Base64 of
 *     the server's own item bytes - the same form a frame's item travels in. A skeleton's bow, and
 *     the armour a zombie picked up
 * @param item the stack a dropped item is, in the same form, or empty for everything else. Without
 *     it a dropped item draws as nothing at all: the stack is the whole of what is rendered
 */
public record MirrorMob(UUID uuid, String type, double x, double y, double z, float yaw, float pitch,
                        float headYaw, boolean onGround, Map<String, String> equipment, String item) {

    public MirrorMob {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(type, "type");
        equipment = equipment == null ? Map.of() : Map.copyOf(equipment);
        item = item == null ? "" : item;
    }
}

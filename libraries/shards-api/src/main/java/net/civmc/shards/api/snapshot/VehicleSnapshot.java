package net.civmc.shards.api.snapshot;

import java.util.Map;
import java.util.Objects;

/**
 * The thing a player was riding when they crossed a border.
 *
 * <p>Carried only by a transfer, never by an ordinary quit. On a quit the vehicle stays in the world
 * and the server saves it like any other entity, so putting it in that snapshot too would recreate it
 * at the next login and leave two.</p>
 *
 * <p>Which vehicle it is comes from {@code type} alone - boats are a separate entity type per wood and
 * minecarts one per kind - so the rest of this record is the state that makes it <em>that</em>
 * vehicle rather than a fresh one.</p>
 *
 * <p>Not carried, because nothing here has a public round trip worth the risk of getting wrong:
 * llama strength and decor, the entity's own age lock, and whatever a modded or unusual vehicle keeps
 * beyond these fields. A vehicle that cannot be described this way is better left behind than
 * recreated wrong.</p>
 *
 * <p>What it is <em>wearing</em> is carried separately from what it holds, because the game keeps the
 * two separately: a happy ghast's harness and a horse's saddle are equipment, not inventory, and a
 * vehicle described by its inventory alone crossed a border stripped of them.</p>
 *
 * @param type the {@code EntityType} name
 * @param customName the legacy-formatted name, null if unnamed
 * @param inventory base64 of the vehicle's own contents, null if it holds nothing - a chest minecart's
 *     cargo, or a horse's saddle and armour
 * @param ownerUuid the taming owner, null if untamed or not tameable
 * @param equipment what the vehicle is wearing, by {@code EquipmentSlot} name, each one Base64 of the
 *     server's own item bytes. Not the same thing as its {@code inventory}: a happy ghast's harness
 *     and a horse's saddle are worn in an equipment slot rather than held in a container, so a
 *     vehicle described by its inventory alone arrived stripped of both. Null on a payload written
 *     before this was carried
 * @param velocityX how the vehicle was already moving, so a minecart at speed arrives at speed. Null
 *     on a payload written before this was carried, which reads as a vehicle that was standing still -
 *     the same thing every rebuilt vehicle used to be
 */
public record VehicleSnapshot(
    String type,
    String customName,
    Double health,
    String inventory,
    Boolean tamed,
    String ownerUuid,
    Integer domestication,
    Integer maxDomestication,
    Double jumpStrength,
    String horseColor,
    String horseStyle,
    Boolean carryingChest,
    Boolean saddled,
    Boolean adult,
    Integer age,
    Double velocityX,
    Double velocityY,
    Double velocityZ,
    Map<String, String> equipment
) {

    public VehicleSnapshot {
        Objects.requireNonNull(type, "type");
        // Left null rather than emptied, so "written before this was carried" and "wearing nothing"
        // stay tellable apart in a payload
        equipment = equipment == null ? null : Map.copyOf(equipment);
    }
}

package net.civmc.shards.api.mirror;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Where one player is standing, for the shards that can see that spot but are not serving them.
 *
 * <p>Carries their skin with them rather than relying on anything else having it. The proxy does put
 * every cross-shard player in everybody's tab list, which would be enough for a client to render the
 * right skin - but depending on that would mean this quietly showing blank people the day somebody
 * turns the tab list off.</p>
 *
 * <p>What they are wearing and holding travels with them too. It is sent at the rate positions are,
 * which was once the argument for leaving it out - but the skin is already in every one of these
 * messages and is several times the size of an armour set, so a message of its own would be saving
 * the small half. What it costs is paid on the sending side instead: the bytes for an item are worked
 * out when the item changes and not once a tick.</p>
 *
 * <p>Equipment is the one part of an entity that is <strong>not</strong> a numbered metadata field -
 * it is sent with its slot named - so nothing about this has to be learned off a real entity or
 * guessed. That is why armour arrives before skin layers do.</p>
 *
 * @param headYaw where the head is turned, which is not the same as {@code yaw} - the body lags the
 *     head, and without it everybody looks like they are strafing
 * @param equipment what they are wearing and holding, by {@code EquipmentSlot} name, each one Base64
 *     of the server's own item bytes - the same form a frame's item travels in
 * @param pose what they are doing with themselves, as the Bukkit {@code Pose} name - crouching,
 *     swimming, gliding, asleep. The whole pose rather than the three booleans it used to be: a
 *     player lying in a bed, dying or using a riptide trident was drawn standing up, and every one of
 *     those is a pose the game already works out and this was throwing away
 * @param swing the hand they have just swung this tick, 1 for the main one and 2 for the off hand,
 *     and 0 for a tick in which they swung neither. A swing is an instant rather than a state, so it
 *     rides along on the position that is sent every tick anyway: a message that is dropped loses one
 *     swing, which is the right thing to lose
 * @param effects the colours of the potions they are visibly under, as packed RGB. The swirls are
 *     not particles anybody sends - a client makes them for itself out of this list - so somebody
 *     standing in a cloud of them on their own shard gave off nothing at all on anyone else's
 * @param skinParts which layers of their skin the person themselves has switched on - the hat, the
 *     jacket, the sleeves - as the client's own settings byte. Their choice rather than anything this
 *     server decides, so it is read from them and carried, not worked out on the far side
 */
public record MirrorPlayer(UUID uuid, String name, String skinTexture, String skinSignature,
                           double x, double y, double z, float yaw, float pitch, float headYaw,
                           String pose, int swing, boolean onGround,
                           Map<String, String> equipment, List<Integer> effects, int skinParts) {

    /** A tick in which they swung neither hand, which is almost all of them. */
    public static final int NOT_SWINGING = 0;
    public static final int MAIN_HAND = 1;
    public static final int OFF_HAND = 2;

    public MirrorPlayer {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        equipment = equipment == null ? Map.of() : Map.copyOf(equipment);
        pose = pose == null ? "" : pose;
        effects = effects == null ? List.of() : List.copyOf(effects);
    }
}

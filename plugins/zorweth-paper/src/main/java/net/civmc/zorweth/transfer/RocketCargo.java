package net.civmc.zorweth.transfer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * A rocket's hold, as it travels between shards.
 *
 * <p>Everything about a launched rocket that is not a passenger: its chests and what is in them,
 * where each sits relative to the rocket's origin, the fuel and uses left in its flight computer, and
 * the group its reinforcements belong to. Passengers are not here - they travel as themselves,
 * through the same handover that carries anyone walking over a border, and are never taken apart and
 * rebuilt.</p>
 *
 * <p>Carried as JSON with each chest's contents in Bukkit's own item encoding, so the round trip
 * through {@code ItemStack} is the one the server itself guarantees rather than a description of an
 * item this plugin invented.</p>
 *
 * @param requestedX where the pilot aimed for; the landing shard decides the actual site
 * @param flightComputerGroupId the group to reinforce the landed rocket to, null if the computer was
 *     not diamond-reinforced
 */
public record RocketCargo(String destinationWorld, int requestedX, int requestedZ, UUID pilotUuid,
                          Integer flightComputerGroupId, double fuelKg, int usesRemaining,
                          List<Chest> chests) {

    /**
     * The type name this kind of parcel is registered and routed under.
     */
    public static final String TYPE = "rocket";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public record Chest(int x, int y, int z, String contents) {

        public ItemStack[] items() {
            return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(this.contents));
        }

        public static Chest of(final RocketBlockPosition position, final ItemStack[] items) {
            return new Chest(position.x(), position.y(), position.z(),
                Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items)));
        }
    }

    public byte[] toBytes() {
        return GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
    }

    public static RocketCargo fromBytes(final byte[] bytes) {
        final RocketCargo cargo = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), RocketCargo.class);
        if (cargo == null || cargo.destinationWorld() == null) {
            throw new IllegalArgumentException("That is not a rocket cargo payload");
        }
        return cargo;
    }
}

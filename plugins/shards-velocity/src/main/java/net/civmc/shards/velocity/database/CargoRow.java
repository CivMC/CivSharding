package net.civmc.shards.velocity.database;

import java.util.UUID;
import net.civmc.shards.api.CargoParcel;
import net.civmc.shards.api.CargoState;
import net.civmc.shards.api.PlayerLocation;

/**
 * A row of {@code shard_cargo}: one parcel, and which shard is answerable for it.
 *
 * <p>{@code owningServerUuid} is never null, unlike a player's. A player with no owner is a player
 * nobody is playing, which is the ordinary resting state; a parcel with no owner would be items
 * nobody is going to put anywhere, which is the one state this table exists to make impossible.</p>
 */
public record CargoRow(UUID cargoId, String type, String sourceServer, UUID owningServerUuid, byte[] payload,
                       String state, String destinationWorld, Double destinationX, Double destinationY,
                       Double destinationZ, String landedWorld, Double landedX, Double landedY, Double landedZ,
                       long sentAtEpochMillis) {

    public CargoState cargoState() {
        return CargoState.valueOf(this.state);
    }

    public PlayerLocation destination() {
        return location(this.destinationWorld, this.destinationX, this.destinationY, this.destinationZ);
    }

    /**
     * Where the parcel is, or is going to be. While it is pending this is the site its owner has
     * reserved, and once it has landed it is where it really is - the same column, because they are
     * the same place: reserving one and then landing somewhere else is exactly what must not happen.
     */
    public PlayerLocation landedAt() {
        return location(this.landedWorld, this.landedX, this.landedY, this.landedZ);
    }

    public CargoParcel toParcel() {
        return new CargoParcel(this.cargoId, this.type, this.sourceServer,
            java.util.Base64.getEncoder().encodeToString(this.payload), destination(), landedAt(),
            this.sentAtEpochMillis);
    }

    private static PlayerLocation location(final String world, final Double x, final Double y, final Double z) {
        if (world == null || x == null || y == null || z == null) {
            return null;
        }
        return new PlayerLocation(world, x, y, z);
    }
}

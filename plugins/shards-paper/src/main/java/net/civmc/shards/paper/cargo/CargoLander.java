package net.civmc.shards.paper.cargo;

import net.civmc.shards.api.CargoParcel;
import net.civmc.shards.api.PlayerLocation;

/**
 * Puts a kind of parcel into this server's world.
 *
 * <p>Registered by whichever plugin understands the payload. Shards carries parcels and says whose
 * they are; it has no idea what a rocket is, and a lander is where that knowledge lives.</p>
 *
 * <p>It is two methods rather than one because a parcel can be offered more than once - an answer to
 * the proxy can go missing, or the server can be killed between writing the blocks and reporting
 * them - and both of those resolve towards landing it again. Erring the other way would lose it. So
 * the part that <em>decides</em> is separated from the part that <em>writes</em>: the decision is
 * made once, written down by the proxy, and every later attempt is handed the same answer. A second
 * attempt then puts the same thing in the same place, overwriting the first rather than standing
 * beside it.</p>
 *
 * <p>Both methods are called on the main thread.</p>
 */
public interface CargoLander {

    /**
     * Works out where this parcel should go.
     *
     * <p>Called once per parcel, ever - the answer is written down before anything is put in the
     * world, and a later attempt at the same parcel is given that answer instead of asking again.
     * Free to search, and free to be non-deterministic.</p>
     *
     * @return where it should go, or null if this server cannot decide yet - a world still loading,
     *     say. The parcel stays owned here and is offered again on the next sweep
     */
    PlayerLocation chooseSite(CargoParcel parcel);

    /**
     * Puts the parcel in the world at {@code site}, and makes it durable.
     *
     * <p>Two rules, and neither is optional:</p>
     *
     * <ol>
     *   <li><strong>Land at {@code site}, not somewhere of your own choosing.</strong> It may be the
     *       site {@link #chooseSite} gave a moment ago, or one chosen before a restart. Landing
     *       anywhere else is how a parcel offered twice becomes two rockets.</li>
     *   <li><strong>Persist before returning.</strong> The proxy is told the parcel has landed on the
     *       strength of this returning, and stops counting it as owed. Blocks written into a chunk
     *       that is never saved are gone at the next crash, and by then nothing else holds a copy.
     *       {@link ChunkFlush} is here for that.</li>
     * </ol>
     *
     * @throws RuntimeException to defer the parcel. Nothing is marked landed, so it is not lost - it
     *     is offered again on the next sweep
     */
    void land(CargoParcel parcel, PlayerLocation site);
}

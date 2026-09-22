package net.civmc.shards.paper.cargo;

import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.CargoFetchRequest;
import net.civmc.shards.api.CargoFetchResponse;
import net.civmc.shards.api.CargoLandStatus;
import net.civmc.shards.api.CargoLandedRequest;
import net.civmc.shards.api.CargoParcel;
import net.civmc.shards.api.CargoReserveRequest;
import net.civmc.shards.api.CargoReserveResponse;
import net.civmc.shards.api.CargoSendRequest;
import net.civmc.shards.api.CargoSendResponse;
import net.civmc.shards.api.CargoSendStatus;
import net.civmc.shards.api.CargoState;
import net.civmc.shards.api.CargoStatusRequest;
import net.civmc.shards.api.CargoStatusResponse;
import net.civmc.shards.api.PlayerLocation;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Moving things between shards when no player is carrying them.
 *
 * <p>A player's belongings survive a crossing because they only ever exist on the player: the
 * snapshot and the ownership change are one write, and what the sending shard gives up is a session
 * rather than something in a world. A rocket's hold is not like that - it is real blocks and real
 * chest contents, which persist only when their chunk is saved - so moving it means destroying
 * something on one machine and creating it on another.</p>
 *
 * <p>This is the sequence that makes that safe, and the order of it is the whole thing:</p>
 *
 * <ol>
 *   <li>The sender writes the parcel to the proxy's store, addressed to the destination shard, and
 *       does not touch its own world until it is told that worked. A failure here leaves the sender
 *       holding everything, so a launch simply does not happen.</li>
 *   <li>Only then does the sender destroy its copy - and flush that destruction to disk, because a
 *       chunk that still holds the old chests on disk is a second copy waiting for a crash.</li>
 *   <li>The destination reads the parcel, puts it in its world, flushes, and only then says it has
 *       landed.</li>
 * </ol>
 *
 * <p>Both ends persist before they report. So a crash at any point resolves towards the parcel being
 * landed twice rather than never - and landing is written to be idempotent, which makes twice
 * harmless. The failure this is built to make impossible is the other one.</p>
 */
public final class CargoService {

    // Nothing pushes an arrival, so this is also how long a rocket's passengers wait on the ground
    // before their hold is reported down. Two seconds is comfortably inside the pause a launch
    // already has, and the cost of asking is one indexed read per shard
    private static final long SWEEP_TICKS = 20L * 2L;
    // A parcel that cannot be landed keeps being offered, and something that fails every time would
    // otherwise fill the log at the sweep rate
    private static final long COMPLAIN_EVERY = 30L;

    private final JavaPlugin plugin;
    private final ShardsClient client;
    private final Logger logger;
    private final String serverName;
    private final BooleanSupplier ready;
    private final Map<String, CargoLander> landers = new ConcurrentHashMap<>();
    // Parcels this server is in the middle of landing. The sweep runs on a timer and a landing takes
    // as long as it takes, so without this a slow one would be started again underneath itself
    private final Set<UUID> landing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> deferrals = new ConcurrentHashMap<>();

    public CargoService(final JavaPlugin plugin, final ShardsClient client, final Logger logger,
                        final String serverName, final BooleanSupplier ready) {
        this.plugin = plugin;
        this.client = client;
        this.logger = logger;
        this.serverName = serverName;
        this.ready = ready;
    }

    /**
     * Says which code puts parcels of a given type into this world.
     *
     * <p>Every shard that could ever receive one has to register it, not just the shards that send
     * them: a parcel is landed by whoever owns the ground it was addressed to.</p>
     */
    public void register(final String type, final CargoLander lander) {
        final CargoLander existing = this.landers.putIfAbsent(type, lander);
        if (existing != null) {
            throw new IllegalStateException("Something already lands cargo of type " + type);
        }
        this.logger.info("Cargo of type " + type + " can be landed here");
    }

    /**
     * Hands a parcel to whichever shard owns {@code target}.
     *
     * <p>On {@link CargoSendStatus#SENT} or {@link CargoSendStatus#ALREADY_SENT} the caller
     * <strong>must</strong> destroy its own copy and flush that. Anything else means nothing was
     * written and the caller still has it.</p>
     *
     * @param cargoId the caller's own id, so that re-sending after a lost answer addresses the same
     *     parcel rather than making a second one
     */
    public CompletableFuture<CargoSendResponse> send(final UUID cargoId, final String type, final byte[] payload,
                                                     final PlayerLocation target) {
        return this.client.sendCargo(CargoSendRequest.toLocation(this.serverName, cargoId, type,
            Base64.getEncoder().encodeToString(payload), target));
    }

    /**
     * The same, for a parcel with no particular place to be - the destination decides where it goes.
     */
    public CompletableFuture<CargoSendResponse> sendToShard(final UUID cargoId, final String type,
                                                            final byte[] payload, final String shardName) {
        return this.client.sendCargo(CargoSendRequest.toShard(this.serverName, cargoId, type,
            Base64.getEncoder().encodeToString(payload), shardName));
    }

    /**
     * Asks where a parcel was put down, once.
     *
     * <p>Answers null while it is still waiting to be landed, and for a parcel nothing knows about.
     * The caller is expected to be the sender, which can tell those apart because it has just sent
     * it.</p>
     */
    public CompletableFuture<PlayerLocation> whereItLanded(final UUID cargoId) {
        return this.client.cargoStatus(CargoStatusRequest.create(this.serverName, cargoId))
            .thenApply(response -> {
                if (response.failed() || response.state() != CargoState.LANDED) {
                    return null;
                }
                return response.landedAt();
            });
    }

    /**
     * Starts asking the proxy what is waiting for this server.
     *
     * <p>Polled rather than pushed. A push would be quicker and would still need this behind it,
     * because an announcement can be missed and a missed parcel is somebody's belongings that never
     * arrive - so the mechanism that cannot miss one is the only one there is. It also means a shard
     * that has been off for a week collects everything sent to it while it was away, in the order it
     * was sent.</p>
     */
    public void startSweep() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, this::sweep, SWEEP_TICKS, SWEEP_TICKS);
    }

    private void sweep() {
        // Before the startup handshake this server does not yet know which ground is its own, and a
        // lander that has to choose a site cannot choose one. Nothing is lost by waiting: the parcels
        // stay owned by this server and are still there on the next sweep
        if (!this.ready.getAsBoolean() || this.landers.isEmpty()) {
            return;
        }
        this.client.fetchCargo(CargoFetchRequest.create(this.serverName))
            .whenComplete(this::offer);
    }

    private void offer(final CargoFetchResponse response, final Throwable error) {
        if (error != null) {
            this.logger.log(Level.FINE, "Could not ask what cargo is waiting here", error);
            return;
        }
        if (response.failed()) {
            this.logger.warning("Could not ask what cargo is waiting here: " + response.failureMessage());
            return;
        }
        for (final CargoParcel parcel : response.parcels()) {
            if (!this.landing.add(parcel.cargoId())) {
                continue;
            }
            // Onto the main thread: a lander writes blocks
            Bukkit.getScheduler().runTask(this.plugin, () -> landOnMainThread(parcel));
        }
    }

    /**
     * Decides where a parcel goes, if that has not already been decided, and writes the decision down
     * before anything is put in the world.
     */
    private void landOnMainThread(final CargoParcel parcel) {
        final CargoLander lander = this.landers.get(parcel.type());
        if (lander == null) {
            // Left where it is rather than dropped. A shard without the plugin for a parcel is a
            // deployment part way through, not a reason to destroy somebody's cargo
            deferred(parcel, "nothing here lands cargo of type " + parcel.type());
            this.landing.remove(parcel.cargoId());
            return;
        }

        // Already decided, on an earlier attempt or before a restart. Asking again would choose
        // somewhere else, and landing there would be a second copy rather than the same one
        if (parcel.reservedAt() != null) {
            put(lander, parcel, parcel.reservedAt());
            return;
        }

        final PlayerLocation chosen;
        try {
            chosen = lander.chooseSite(parcel);
        } catch (final RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Could not choose a site for cargo " + parcel.cargoId()
                + " from " + parcel.sourceServer() + "; it stays owned by this server", exception);
            this.landing.remove(parcel.cargoId());
            return;
        }
        if (chosen == null) {
            deferred(parcel, "the lander was not ready to choose a site");
            this.landing.remove(parcel.cargoId());
            return;
        }

        this.client.reserveCargoSite(CargoReserveRequest.create(this.serverName, parcel.cargoId(), chosen))
            .whenComplete((response, error) -> onReserved(lander, parcel, response, error));
    }

    private void onReserved(final CargoLander lander, final CargoParcel parcel,
                            final CargoReserveResponse response, final Throwable error) {
        if (error != null || response.status() != CargoLandStatus.RECORDED || response.site() == null) {
            // Nothing is in the world yet, so nothing is half done. The parcel is still owned here and
            // still pending, and the next sweep starts it over
            this.logger.log(Level.WARNING, "Could not reserve a site for cargo " + parcel.cargoId()
                + "; nothing has been put down and it will be offered again",
                error);
            this.landing.remove(parcel.cargoId());
            return;
        }
        // The site the proxy holds, which is not always the one just asked for: a reservation made
        // earlier wins, and landing at the newer choice would put a second copy beside the first
        Bukkit.getScheduler().runTask(this.plugin, () -> put(lander, parcel, response.site()));
    }

    private void put(final CargoLander lander, final CargoParcel parcel, final PlayerLocation site) {
        try {
            lander.land(parcel, site);
        } catch (final RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Could not land cargo " + parcel.cargoId() + " from "
                + parcel.sourceServer() + " at " + site + "; it stays owned by this server and will be "
                + "offered again", exception);
            this.landing.remove(parcel.cargoId());
            return;
        }

        this.deferrals.remove(parcel.cargoId());
        // Only now, and only because land() has made it durable. Saying so first would let a crash in
        // between take the parcel with it while the proxy had already stopped counting it as owed
        this.client.cargoLanded(CargoLandedRequest.create(this.serverName, parcel.cargoId(), site))
            .whenComplete((response, error) -> {
                this.landing.remove(parcel.cargoId());
                if (error != null) {
                    // It is in this world and still marked pending, so the next sweep offers it again -
                    // which lands the same thing at the same site, and is why that had to be written
                    // down first
                    this.logger.log(Level.WARNING, "Landed cargo " + parcel.cargoId()
                        + " but could not tell the proxy; it will be offered again", error);
                    return;
                }
                if (response.status() != CargoLandStatus.RECORDED) {
                    this.logger.severe("Landed cargo " + parcel.cargoId() + " and the proxy answered "
                        + response.status() + ": " + response.failureMessage());
                }
            });
    }

    /**
     * Notes a parcel that could not be landed this time, without saying so every two seconds.
     */
    private void deferred(final CargoParcel parcel, final String why) {
        final long attempts = this.deferrals.merge(parcel.cargoId(), 1L, Long::sum);
        if (attempts == 1L || attempts % COMPLAIN_EVERY == 0L) {
            this.logger.warning("Cargo " + parcel.cargoId() + " from " + parcel.sourceServer()
                + " is still waiting after " + attempts + " attempt(s): " + why);
        }
    }

    /**
     * Whether anything at all can be landed here. A shard with no landers registered is one that
     * should never be sent cargo.
     */
    public boolean landsAnything() {
        return !this.landers.isEmpty();
    }

    /**
     * For a sender deciding whether to bother: a status response that says nothing went wrong.
     */
    public static boolean sent(final CargoSendResponse response) {
        return response.status() == CargoSendStatus.SENT
            || response.status() == CargoSendStatus.ALREADY_SENT;
    }

    /**
     * Exposed for a caller that wants the whole answer rather than just the location.
     */
    public CompletableFuture<CargoStatusResponse> status(final UUID cargoId) {
        return this.client.cargoStatus(CargoStatusRequest.create(this.serverName, cargoId));
    }
}

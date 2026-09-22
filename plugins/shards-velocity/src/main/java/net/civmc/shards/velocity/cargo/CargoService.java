package net.civmc.shards.velocity.cargo;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.civmc.shards.api.CargoParcel;
import net.civmc.shards.api.CargoState;
import net.civmc.shards.api.PlayerLocation;
import net.civmc.shards.velocity.database.CargoRow;
import net.civmc.shards.velocity.database.CargoStatements;
import org.jdbi.v3.core.Jdbi;

/**
 * The store of things being moved between shards with no player carrying them.
 *
 * <p>Deliberately the same shape as player ownership, one table along: a row names exactly one shard
 * that is answerable for the parcel, and only that shard can change its state. What differs is that a
 * parcel's owner is never nobody. A player with no owner is simply a player who is offline; a parcel
 * with no owner would be items nobody was ever going to put anywhere.</p>
 *
 * <p>The table is in the same database as {@code shard_player_data} on purpose. A rocket carries
 * both - passengers and a hold - and keeping them in one database is what leaves open the option of
 * writing both in one transaction. They are not written together today.</p>
 */
@Singleton
public final class CargoService {

    // Deliberately small. A parcel carries its whole payload, and a rocket with a full hold is tens of
    // kilobytes - so a generous limit here is a single broker message of several megabytes the first
    // time a shard comes back to a backlog. A shard lands what it is given and asks again two seconds
    // later, so a backlog drains at a couple a second rather than arriving as one reply too big to
    // deliver
    private static final int FETCH_LIMIT = 4;

    private final Jdbi jdbi;

    @Inject
    public CargoService(final Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /**
     * Makes {@code owningServerUuid} answerable for a parcel.
     *
     * <p>After this returns {@link CargoSendResult.Stored} the parcel is the destination's, and the
     * sender is required to destroy its own copy. That is the whole contract: the row is written
     * first and the world is emptied second, so a crash between them leaves the parcel landing twice
     * rather than not at all.</p>
     */
    public CargoSendResult store(final UUID cargoId, final String type, final String sourceServer,
                                 final UUID owningServerUuid, final byte[] payload,
                                 final PlayerLocation destination, final long sentAtEpochMillis) {
        return this.jdbi.inTransaction(handle -> {
            final CargoStatements statements = handle.attach(CargoStatements.class);
            final Optional<CargoRow> existing = statements.selectForUpdate(cargoId);
            if (existing.isPresent()) {
                // The id is the sender's own, so the same id addressed somewhere else is the sender
                // having reused one rather than anything this end can resolve
                return existing.get().owningServerUuid().equals(owningServerUuid)
                    ? new CargoSendResult.AlreadyStored()
                    : new CargoSendResult.IdReused(existing.get().owningServerUuid());
            }
            statements.insert(cargoId, type, sourceServer, owningServerUuid, payload,
                destination == null ? null : destination.world(),
                destination == null ? null : destination.x(),
                destination == null ? null : destination.y(),
                destination == null ? null : destination.z(),
                sentAtEpochMillis);
            return new CargoSendResult.Stored();
        });
    }

    /**
     * What a shard owns and has not yet put in its world.
     */
    public List<CargoParcel> pending(final UUID owningServerUuid) {
        return this.jdbi.withExtension(CargoStatements.class,
                statements -> statements.selectPending(owningServerUuid, FETCH_LIMIT))
            .stream()
            .map(CargoRow::toParcel)
            .toList();
    }

    /**
     * Writes down where the owning shard is about to put a parcel.
     *
     * <p>Once only. A shard coming back to a parcel it had already chosen a site for is told nothing
     * changed and goes on to use the site in the row, which is what stops a repeat landing building a
     * second copy somewhere else.</p>
     */
    public CargoReserveResult reserve(final UUID cargoId, final UUID owningServerUuid,
                                      final PlayerLocation site) {
        return this.jdbi.inTransaction(handle -> {
            final CargoStatements statements = handle.attach(CargoStatements.class);
            final Optional<CargoRow> existing = statements.selectForUpdate(cargoId);
            if (existing.isEmpty()) {
                return new CargoReserveResult(new CargoLandResult.NoRow(), null);
            }
            final CargoRow row = existing.get();
            if (!row.owningServerUuid().equals(owningServerUuid)) {
                return new CargoReserveResult(new CargoLandResult.HeldByOther(row.owningServerUuid()), null);
            }
            // Whatever was already written wins. The asker is coming back to a parcel it had already
            // chosen a site for, and using its new choice would build a second copy beside the first
            if (row.landedAt() != null) {
                return new CargoReserveResult(new CargoLandResult.Recorded(), row.landedAt());
            }
            statements.reserve(cargoId, owningServerUuid, site.world(), site.x(), site.y(), site.z());
            return new CargoReserveResult(new CargoLandResult.Recorded(), site);
        });
    }

    /**
     * @param site the place written against the parcel once the reservation settled, which is the
     *     place the shard has to use - not necessarily the one it asked for
     */
    public record CargoReserveResult(CargoLandResult outcome, PlayerLocation site) {
    }

    /**
     * Records that the owning shard has put a parcel down and flushed it.
     */
    public CargoLandResult landed(final UUID cargoId, final UUID owningServerUuid, final PlayerLocation landedAt) {
        return this.jdbi.inTransaction(handle -> {
            final CargoStatements statements = handle.attach(CargoStatements.class);
            final Optional<CargoRow> existing = statements.selectForUpdate(cargoId);
            if (existing.isEmpty()) {
                return new CargoLandResult.NoRow();
            }
            final CargoRow row = existing.get();
            if (!row.owningServerUuid().equals(owningServerUuid)) {
                return new CargoLandResult.HeldByOther(row.owningServerUuid());
            }
            // Already landed. The receiver puts a parcel down idempotently, so a second report is a
            // retry whose first answer went missing, not a second landing
            if (row.cargoState() == CargoState.LANDED) {
                return new CargoLandResult.Recorded();
            }
            statements.markLanded(cargoId, owningServerUuid,
                landedAt == null ? null : landedAt.world(),
                landedAt == null ? null : landedAt.x(),
                landedAt == null ? null : landedAt.y(),
                landedAt == null ? null : landedAt.z());
            return new CargoLandResult.Recorded();
        });
    }

    public Optional<CargoRow> find(final UUID cargoId) {
        return this.jdbi.withExtension(CargoStatements.class, statements -> statements.select(cargoId));
    }

    /**
     * Forgets parcels that landed longer ago than {@code retention}.
     *
     * <p>Landed ones only. A parcel still waiting is the only copy of somebody's belongings however
     * long it has been waiting, so age is never a reason to delete one - a shard that has been off
     * for a month comes back to everything that was sent to it.</p>
     */
    public int pruneLanded(final Duration retention) {
        return this.jdbi.withExtension(CargoStatements.class,
            statements -> statements.pruneLanded(System.currentTimeMillis() - retention.toMillis()));
    }
}

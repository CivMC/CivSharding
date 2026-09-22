package net.civmc.zorweth.transfer;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import isaac.bastion.Bastion;
import java.util.concurrent.ThreadLocalRandom;
import net.civmc.shards.api.CargoParcel;
import net.civmc.shards.api.PlayerLocation;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.cargo.CargoLander;
import net.civmc.shards.paper.cargo.ChunkFlush;
import net.civmc.zorweth.ZorwethPlugin;
import net.civmc.zorweth.flight.FlightComputer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Dispenser;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;
import vg.civcraft.mc.citadel.CitadelPermissionHandler;
import vg.civcraft.mc.citadel.ReinforcementLogic;

/**
 * Puts a launched rocket down on this shard.
 *
 * <p>Where a rocket lands used to be worked out during the first passenger's login, which made
 * arriving and landing the same event: a rocket whose passengers all disconnected on the way never
 * landed at all, and its hold sat in a table with nothing left to trigger it. Landing is now its own
 * thing, driven by the cargo sweep, and the passengers are sent to wherever it ended up. A rocket
 * with nobody aboard still arrives.</p>
 */
public final class RocketCargoLander implements CargoLander {

    private static final int SPIRAL_ITERATION_DISTANCE = 8;
    private static final int MAX_SPIRAL_DISTANCE = 1000;

    private final ZorwethPlugin plugin;
    private final ShardBorder border;

    public RocketCargoLander(final ZorwethPlugin plugin, final ShardBorder border) {
        this.plugin = plugin;
        this.border = border;
    }

    @Override
    public PlayerLocation chooseSite(final CargoParcel parcel) {
        final RocketCargo cargo = RocketCargo.fromBytes(java.util.Base64.getDecoder().decode(parcel.payload()));
        final World world = Bukkit.getWorld(cargo.destinationWorld());
        if (world == null) {
            // Deferred rather than failed: a world that is not loaded now may be in a moment, and the
            // parcel keeps until it is
            return null;
        }
        return findSite(world, cargo);
    }

    @Override
    public void land(final CargoParcel parcel, final PlayerLocation site) {
        final RocketCargo cargo = RocketCargo.fromBytes(java.util.Base64.getDecoder().decode(parcel.payload()));
        final World world = Bukkit.getWorld(site.world());
        if (world == null) {
            throw new IllegalStateException("The world " + site.world() + " a rocket was to land in is gone");
        }
        final int originX = (int) Math.floor(site.x());
        final int originY = (int) Math.floor(site.y());
        final int originZ = (int) Math.floor(site.z());

        final Clipboard clipboard = this.plugin.getRocketClipboard();
        final Region region = clipboard.getRegion();
        final BlockVector3 corner = region.getMinimumPoint();

        paste(world, clipboard, region, corner, originX, originY, originZ);
        fillChests(world, cargo, originX, originY, originZ);
        markComputer(world, cargo, originX, originY, originZ);

        // Before this returns, because the proxy is told the rocket has arrived on the strength of it
        // returning. Everything above is in memory until now, and a server killed here would come back
        // with the rocket gone and nothing left holding a copy of what was in it
        ChunkFlush.blocks(world,
            originX, originZ,
            originX + region.getWidth(), originZ + region.getLength(),
            this.plugin.getLogger());
    }

    /**
     * Looks outward from where the pilot aimed for somewhere the rocket fits.
     *
     * <p>Three things rule a place out: ground this shard does not own, a bastion the pilot may not
     * reinforce under, and anything already standing where the rocket's own blocks would go.</p>
     *
     * <p>The first of those is new, and it is the one that matters under sharding. The search walks up
     * to a thousand blocks, which is far enough to cross a border - and a rocket built on a
     * neighbour's ground is built into this server's year-out-of-date copy of it, where the shard that
     * really owns that ground cannot see it and never will.</p>
     */
    private PlayerLocation findSite(final World world, final RocketCargo cargo) {
        int aimX = cargo.requestedX() - FlightComputer.RELATIVE_POSITION.getX();
        int aimZ = cargo.requestedZ() - FlightComputer.RELATIVE_POSITION.getZ();

        final double radiusSquared = this.plugin.getWorldRadius() * (double) this.plugin.getWorldRadius();
        final double distanceSquared = distanceSquaredFromCentre(aimX, aimZ);
        if (distanceSquared > radiusSquared) {
            final double scale = this.plugin.getWorldRadius() / Math.sqrt(distanceSquared);
            aimX = this.plugin.getRocketCentreX() + (int) ((aimX - this.plugin.getRocketCentreX()) * scale);
            aimZ = this.plugin.getRocketCentreZ() + (int) ((aimZ - this.plugin.getRocketCentreZ()) * scale);
        }

        final Clipboard clipboard = this.plugin.getRocketClipboard();
        final Region region = clipboard.getRegion();
        final BlockVector3 corner = region.getMinimumPoint();

        int distance = 0;
        while (distance < MAX_SPIRAL_DISTANCE) {
            final double angle = ThreadLocalRandom.current().nextDouble(0.0, 2.0 * Math.PI);
            final int tryX = aimX + (int) (distance * Math.cos(angle));
            final int tryZ = aimZ + (int) (distance * Math.sin(angle));
            distance += SPIRAL_ITERATION_DISTANCE;

            if (distanceSquaredFromCentre(tryX, tryZ) > radiusSquared) {
                continue;
            }
            if (!ownsFootprint(tryX, tryZ, region)) {
                continue;
            }
            final Integer groundY = clearGroundAt(world, cargo, tryX, tryZ, region, corner);
            if (groundY == null) {
                continue;
            }
            if (!standsClear(world, clipboard, region, corner, tryX, groundY, tryZ)) {
                continue;
            }
            return new PlayerLocation(world.getName(), tryX, groundY, tryZ);
        }

        this.plugin.getLogger().warning("Could not find anywhere on this shard for a rocket aimed at "
            + cargo.requestedX() + ", " + cargo.requestedZ() + "; it stays in the hold and will be tried "
            + "again");
        return null;
    }

    /**
     * Whether every block the rocket would stand on belongs to this shard.
     *
     * <p>All four corners rather than one, because areas are rectilinear but need not be large: a
     * rocket eleven blocks wide can have its origin inside this shard and its far side over a
     * border.</p>
     */
    private boolean ownsFootprint(final int x, final int z, final Region region) {
        final int lastX = x + region.getWidth() - 1;
        final int lastZ = z + region.getLength() - 1;
        return !this.border.isOutside(x, z)
            && !this.border.isOutside(lastX, z)
            && !this.border.isOutside(x, lastZ)
            && !this.border.isOutside(lastX, lastZ);
    }

    /**
     * The height to stand the rocket at, or null if this place will not do.
     *
     * <p>Refuses a site whose ground holds anything with an inventory. The rocket's own air blocks are
     * written into the world when it lands, so a chest standing where the rocket's hollow interior
     * would be is a chest about to be deleted along with what is in it - somebody's belongings
     * destroyed by a landing they had nothing to do with.</p>
     */
    private Integer clearGroundAt(final World world, final RocketCargo cargo, final int x, final int z,
                                  final Region region, final BlockVector3 corner) {
        int highestY = world.getMinHeight();
        for (final BlockVector3 position : region) {
            if (position.getY() != corner.getY()) {
                continue;
            }
            final BlockVector3 relative = position.subtract(corner);
            final int columnX = x + relative.getX();
            final int columnZ = z + relative.getZ();
            final int blockY = world.getHighestBlockYAt(columnX, columnZ);
            highestY = Math.max(highestY, blockY);

            if (!Bastion.getBastionManager().getBlockingBastionsWithoutPermission(
                new org.bukkit.Location(world, columnX, blockY, columnZ), cargo.pilotUuid(),
                CitadelPermissionHandler.getReinforce()).isEmpty()) {
                return null;
            }
            if (highestY + 1 + region.getHeight() >= world.getMaxHeight()) {
                return null;
            }
        }
        return highestY + 1;
    }

    /**
     * Whether the rocket's own blocks would go into open air.
     *
     * <p>Air cells are checked too, unlike the emptiness test this replaces. They are written when the
     * rocket lands - that is what hollows it out - so a container sitting in one is a container the
     * landing would destroy.</p>
     */
    private boolean standsClear(final World world, final Clipboard clipboard, final Region region,
                                final BlockVector3 corner, final int originX, final int originY,
                                final int originZ) {
        for (final BlockVector3 position : region) {
            final BlockVector3 relative = position.subtract(corner);
            final Block target = world.getBlockAt(originX + relative.getX(), originY + relative.getY(),
                originZ + relative.getZ());
            final boolean rocketBlockHere = !Bukkit
                .createBlockData(clipboard.getBlock(position).getAsString()).getMaterial().isAir();
            if (rocketBlockHere && !target.getType().isAir()) {
                return false;
            }
            if (!rocketBlockHere && holdsItems(target)) {
                return false;
            }
        }
        return true;
    }

    private void paste(final World world, final Clipboard clipboard, final Region region,
                       final BlockVector3 corner, final int originX, final int originY, final int originZ) {
        for (final BlockVector3 position : region) {
            final BlockVector3 relative = position.subtract(corner);
            final Block target = world.getBlockAt(originX + relative.getX(), originY + relative.getY(),
                originZ + relative.getZ());
            final org.bukkit.block.data.BlockData data =
                Bukkit.createBlockData(clipboard.getBlock(position).getAsString());
            // The site was chosen with nothing of this sort in it, but a landing can be minutes behind
            // the choice and somebody can have built since. Left alone rather than cleared: a rocket
            // with something in its hold is a curiosity, and a deleted chest is somebody's loss
            if (data.getMaterial().isAir() && holdsItems(target)) {
                this.plugin.getLogger().warning("Left a " + target.getType() + " at " + target.getX() + ", "
                    + target.getY() + ", " + target.getZ() + " where a landing rocket would have cleared "
                    + "it; it has items in it");
                continue;
            }
            target.setBlockData(data, false);
        }
    }

    /**
     * Puts each chest's contents back.
     *
     * <p>Set, not added, which is what makes landing the same rocket twice harmless: the second
     * attempt writes the same contents into the same chests and the result is one rocket's worth of
     * cargo either way.</p>
     */
    private void fillChests(final World world, final RocketCargo cargo, final int originX, final int originY,
                            final int originZ) {
        for (final RocketCargo.Chest chest : cargo.chests()) {
            final Block block = world.getBlockAt(originX + chest.x(), originY + chest.y(), originZ + chest.z());
            if (!(block.getState(false) instanceof Chest state)) {
                // Nothing to put them in. Said loudly, because it is items that have nowhere to go and
                // the parcel is about to be marked landed
                this.plugin.getLogger().severe("A rocket's chest should be at " + block.getX() + ", "
                    + block.getY() + ", " + block.getZ() + " and there is a " + block.getType()
                    + " there; its contents could not be put back");
                continue;
            }
            state.getBlockInventory().setContents(chest.items());
            if (cargo.flightComputerGroupId() != null) {
                FlightComputer.reinforceBlock(block, cargo.flightComputerGroupId(), Material.STONE);
            }
        }
    }

    private void markComputer(final World world, final RocketCargo cargo, final int originX, final int originY,
                              final int originZ) {
        final Block computer = world.getBlockAt(
            originX + FlightComputer.RELATIVE_POSITION.getX(),
            originY + FlightComputer.RELATIVE_POSITION.getY(),
            originZ + FlightComputer.RELATIVE_POSITION.getZ());
        if (!(computer.getState(false) instanceof Dispenser dispenser)) {
            // Complained about rather than thrown. Throwing would defer the parcel, and the cargo is
            // already in the world - what is lost is the rocket being launchable again, not the cargo
            this.plugin.getLogger().severe("A rocket landed with no flight computer at " + computer.getX()
                + ", " + computer.getY() + ", " + computer.getZ() + "; it will not launch again");
            return;
        }
        dispenser.getPersistentDataContainer().set(FlightComputer.ROCKET_COMPUTER_KEY,
            PersistentDataType.BOOLEAN, true);
        dispenser.getPersistentDataContainer().set(FlightComputer.ROCKET_FUEL_KEY, PersistentDataType.DOUBLE,
            cargo.fuelKg());
        dispenser.getPersistentDataContainer().set(FlightComputer.ROCKET_USES_REMAINING_KEY,
            PersistentDataType.INTEGER, cargo.usesRemaining());
        dispenser.update(false, false);
        if (cargo.flightComputerGroupId() != null) {
            FlightComputer.reinforceBlock(computer, cargo.flightComputerGroupId(), Material.DIAMOND);
        }
    }

    private static boolean holdsItems(final Block block) {
        if (ReinforcementLogic.getReinforcementAt(block.getLocation()) != null) {
            return true;
        }
        return block.getState(false) instanceof InventoryHolder holder
            && !holder.getInventory().isEmpty();
    }

    private double distanceSquaredFromCentre(final int x, final int z) {
        final double relativeX = x - this.plugin.getRocketCentreX();
        final double relativeZ = z - this.plugin.getRocketCentreZ();
        return relativeX * relativeX + relativeZ * relativeZ;
    }
}

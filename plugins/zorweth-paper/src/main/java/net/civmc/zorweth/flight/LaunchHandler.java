package net.civmc.zorweth.flight;

import com.devotedmc.ExilePearl.ExilePearlPlugin;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.civmc.shards.api.CargoSendResponse;
import net.civmc.shards.api.PlayerLocation;
import net.civmc.shards.paper.cargo.CargoService;
import net.civmc.shards.paper.cargo.ChunkFlush;
import net.civmc.zorweth.ZorwethPlugin;
import net.civmc.zorweth.mechanics.Fuel;
import net.civmc.zorweth.transfer.RocketBlockPosition;
import net.civmc.zorweth.transfer.RocketEntityPosition;
import net.civmc.zorweth.transfer.RocketManifest;
import net.civmc.zorweth.transfer.RocketManifestChest;
import net.civmc.zorweth.transfer.RocketCargo;
import net.civmc.zorweth.transfer.RocketManifestPassenger;
import net.civmc.zorweth.transfer.ShardTransfers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import vg.civcraft.mc.citadel.ReinforcementLogic;
import vg.civcraft.mc.citadel.model.Reinforcement;

// Handles launching logic
public class LaunchHandler {

    // A landing is put down by the destination's cargo sweep, which runs every couple of seconds,
    // so this is a handful of polls rather than a wait. Generous because the cost of giving up early
    // is passengers left behind, and the cost of waiting is a few more seconds of standing still
    private static final long LANDING_POLL_TICKS = 20L;
    private static final int LANDING_ATTEMPTS = 30;

    public static final double FUEL_ITEM_MASS_KG = 4.0;
    public static final double EXHAUST_VELOCITY_METERS_PER_SECOND = 5_000.0;
    public static final double ROCKET_DRY_MASS_KG = 150.0;
    public static final double SITTING_PLAYER_MASS_KG = 10.0;
    private static final String COMPACTED_LORE = "Compacted Item";

    public static FuelStatus calculateFuelStatus(final Block computer, final List<RocketManifestPassenger> passengers,
                                                  final List<RocketManifestChest> chests) {
        final double cargoMass = calculateCargoMass(chests, passengers);
        final int sittingPlayers = Math.max(1, passengers.size());
        final double nonFuelMass = ROCKET_DRY_MASS_KG + cargoMass + sittingPlayers * SITTING_PLAYER_MASS_KG;
        final double requiredFuelKg = nonFuelMass * (Math.exp(getDeltaVMetersPerSecond() / EXHAUST_VELOCITY_METERS_PER_SECOND) - 1.0);
        final double fuelKg = FlightComputer.getFuelKg(computer);
        return new FuelStatus(
            (int) (fuelKg / FUEL_ITEM_MASS_KG),
            fuelKg,
            requiredFuelKg,
            (int) Math.ceil(requiredFuelKg / FUEL_ITEM_MASS_KG),
            cargoMass,
            sittingPlayers
        );
    }

    public static RocketWeightPayload collectRocketWeightPayload(final Block computer, final Clipboard rocket) {
        final Region region = rocket.getRegion();
        final BlockVector3 schematicNorthWestCorner = region.getMinimumPoint();
        final Block origin = FlightComputer.getRocketOrigin(computer);
        final List<RocketManifestChest> chests = new ArrayList<>();

        for (final BlockVector3 position : region) {
            final BlockVector3 relative = position.subtract(schematicNorthWestCorner);
            final Block actualBlock = origin.getRelative(relative.getX(), relative.getY(), relative.getZ());
            if (actualBlock.getState(false) instanceof Chest chest) {
                chests.add(new RocketManifestChest(
                    new RocketBlockPosition(relative.getX(), relative.getY(), relative.getZ()),
                    chest.getBlockInventory().getStorageContents()
                ));
            }
        }

        final List<RocketManifestPassenger> passengers = new ArrayList<>();
        for (final Player seated : Bukkit.getOnlinePlayers()) {
            if (!seated.getWorld().equals(origin.getWorld())) {
                continue;
            }
            if (seated.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            final Location location = seated.getLocation();
            final BlockVector3 relative = BlockVector3.at(
                location.getBlockX() - origin.getX(),
                location.getBlockY() - origin.getY(),
                location.getBlockZ() - origin.getZ()
            );
            if (!region.contains(schematicNorthWestCorner.add(relative)) || !FlightComputer.isSittingWithGSit(seated)) {
                continue;
            }
            passengers.add(new RocketManifestPassenger(
                seated.getUniqueId(),
                new RocketEntityPosition(
                    location.getX() - origin.getX(),
                    location.getY() - origin.getY(),
                    location.getZ() - origin.getZ(),
                    location.getYaw(),
                    location.getPitch()
                ),
                seated.getInventory().getContents(),
                seated.getHealth(),
                seated.getLevel(),
                seated.getExp(),
                seated.getFoodLevel(),
                seated.getSaturation(),
                seated.getExhaustion(),
                seated.getInventory().getHeldItemSlot(),
                seated.getGameMode()
            ));
        }

        return new RocketWeightPayload(passengers, chests, getRemainingFuel(chests, passengers, computer));
    }

    private static double calculateCargoMass(final List<RocketManifestChest> chests,
                                             final List<RocketManifestPassenger> passengers) {
        double mass = 0.0;
        for (final RocketManifestChest chest : chests) {
            mass += calculateItemMass(chest.contents());
        }
        for (final RocketManifestPassenger passenger : passengers) {
            mass += calculateItemMass(passenger.inventoryContents());
        }
        return mass;
    }

    private static double calculateItemMass(final ItemStack[] items) {
        double mass = 0.0;
        for (final ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            int itemAmount = item.getAmount();
            if (isCompacted(item)) {
                itemAmount *= getCompactStackSize(item.getType());
            }
            double itemMass = itemAmount / (double) item.getMaxStackSize();
            if (FlightComputer.isFuel(item)) {
                itemMass = itemAmount * FUEL_ITEM_MASS_KG;
            } else if (Fuel.isCrudeOil(item)) {
                itemMass = itemAmount * Fuel.CRUDE_OIL_ITEM_MASS_KG;
            }
            mass += itemMass;
            if (item.getItemMeta() instanceof BundleMeta bundleMeta) {
                mass += itemAmount * calculateItemMass(bundleMeta.getItems().toArray(ItemStack[]::new));
            }
        }
        return mass;
    }

    private static int getCompactStackSize(final Material material) {
        return switch (material.getMaxStackSize()) {
            case 64 -> 64;
            case 16 -> 16;
            case 1 -> 8;
            default -> 1;
        };
    }

    private static boolean isCompacted(final ItemStack item) {
        if (!item.hasItemMeta()) {
            return false;
        }
        final ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore()) {
            return false;
        }
        for (final String lore : meta.getLore()) {
            if (COMPACTED_LORE.equals(lore)) {
                return true;
            }
        }
        return false;
    }

    public static RocketManifestResult collectLaunchManifest(final ZorwethPlugin plugin, final Block computer, final Player player, Clipboard rocket) {
        final Region region = rocket.getRegion();
        final BlockVector3 schematicNorthWestCorner = region.getMinimumPoint();
        final Block origin = FlightComputer.getRocketOrigin(computer);
        final RocketWeightPayload payload = collectRocketWeightPayload(computer, rocket);
        final Map<Long, Integer> highestRocketYByColumn = new HashMap<>();

        for (final BlockVector3 position : region) {
            final BlockVector3 relative = position.subtract(schematicNorthWestCorner);
            final com.sk89q.worldedit.world.block.BlockState expectedState = rocket.getBlock(position);
            final Material expected = Bukkit.createBlockData(expectedState.getAsString()).getMaterial();
            final Block actualBlock = origin.getRelative(relative.getX(), relative.getY(), relative.getZ());
            final Material actual = actualBlock.getType();
            if (actual != expected) {
                return new RocketManifestResult(null,
                    Component.text("Rocket is not structurally intact", NamedTextColor.RED));
            }
            if (!expected.isAir()) {
                final long column = packColumn(relative.getX(), relative.getZ());
                highestRocketYByColumn.merge(column, actualBlock.getY(), Math::max);
            }
        }

        for (final Map.Entry<Long, Integer> entry : highestRocketYByColumn.entrySet()) {
            final int x = unpackColumnX(entry.getKey()) + origin.getX();
            final int z = unpackColumnZ(entry.getKey()) + origin.getZ();
            for (int y = entry.getValue() + 1; y < origin.getWorld().getMaxHeight(); y++) {
                if (!origin.getWorld().getBlockAt(x, y, z).getType().isAir()) {
                    return new RocketManifestResult(null,
                        Component.text("Rocket launch path is obstructed", NamedTextColor.RED));
                }
            }
        }

        for (final Player seated : Bukkit.getOnlinePlayers()) {
            if (!seated.getWorld().equals(origin.getWorld())) {
                continue;
            }
            if (seated.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            final Location location = seated.getLocation();
            final BlockVector3 relative = BlockVector3.at(
                location.getBlockX() - origin.getX(),
                location.getBlockY() - origin.getY(),
                location.getBlockZ() - origin.getZ()
            );
            final boolean insideRocket = region.contains(schematicNorthWestCorner.add(relative));
            if (insideRocket && !FlightComputer.isSittingWithGSit(seated)) {
                return new RocketManifestResult(null,
                    Component.text((seated.equals(player) ? "Pilot" : "Passenger") + " " + seated.getName()
                        + " is not seated.", NamedTextColor.RED));
            }
        }

        final FlightComputerGui.Coordinates destination = FlightComputer.getDestination(computer);
        if (destination == null) {
            return new RocketManifestResult(null,
                Component.text("Destination not set.", NamedTextColor.RED));
        }

        if (containsIllegalItem(payload)) {
            return new RocketManifestResult(null,
                Component.text("Filled maps and pearls cannot be transferred on rockets.", NamedTextColor.RED));
        }

        return new RocketManifestResult(new RocketManifest(
            UUID.randomUUID(),
            plugin.getServerName(),
            plugin.getDestinationServer(),
            origin.getWorld().getName(),
            plugin.getDestinationWorld(),
            new RocketBlockPosition(origin.getX(), origin.getY(), origin.getZ()),
            destination.x(),
            destination.z(),
            player.getUniqueId(),
            getDiamondFlightComputerGroupId(computer),
            payload.passengers(),
            payload.chests(),
            getRemainingFuel(payload.chests(), payload.passengers(), computer),
            FlightComputer.getUsesRemaining(computer)
        ), null);
    }

    private static long packColumn(final int x, final int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int unpackColumnX(final long column) {
        return (int) (column >> 32);
    }

    private static int unpackColumnZ(final long column) {
        return (int) column;
    }

    private static boolean containsIllegalItem(final RocketWeightPayload payload) {
        for (final RocketManifestPassenger passenger : payload.passengers()) {
            if (containsIllegalItem(passenger.inventoryContents())) {
                return true;
            }
        }
        for (final RocketManifestChest chest : payload.chests()) {
            if (containsIllegalItem(chest.contents())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIllegalItem(final ItemStack[] contents) {
        for (final ItemStack item : contents) {
            if (item != null && (item.getType() == Material.FILLED_MAP
                || (item.getType() == Material.ENDER_PEARL
                    && Bukkit.getPluginManager().isPluginEnabled("ExilePearl")
                    && ExilePearlPlugin.getApi() != null
                    && ExilePearlPlugin.getApi().getPearlFromItemStack(item) != null))) {
                return true;
            }
            if (item != null && item.getItemMeta() instanceof BundleMeta bundleMeta
                && containsIllegalItem(bundleMeta.getItems().toArray(ItemStack[]::new))) {
                return true;
            }
        }
        return false;
    }

    private static double getRemainingFuel(final List<RocketManifestChest> chests,
                                           final List<RocketManifestPassenger> passengers,
                                           final Block computer) {
        final double cargoMass = calculateCargoMass(chests, passengers);
        final double dryMass = ROCKET_DRY_MASS_KG + cargoMass + passengers.size() * SITTING_PLAYER_MASS_KG;
        final double wetMass = FlightComputer.getFuelKg(computer) + dryMass;
        return wetMass / (Math.exp(getDeltaVMetersPerSecond() / EXHAUST_VELOCITY_METERS_PER_SECOND)) - dryMass;
    }

    private static double getDeltaVMetersPerSecond() {
        return JavaPlugin.getPlugin(ZorwethPlugin.class).getDeltaVMetersPerSecond();
    }

    private static Integer getDiamondFlightComputerGroupId(final Block computer) {
        final Reinforcement reinforcement = ReinforcementLogic.getReinforcementAt(computer.getLocation());
        if (reinforcement == null || reinforcement.getType().getItem() == null
            || reinforcement.getType().getItem().getType() != Material.DIAMOND) {
            return null;
        }
        return reinforcement.getGroupId();
    }

    /**
     * Sends a rocket, its hold first and its passengers after it.
     *
     * <p>The order is the whole of what makes this safe, and it is the opposite of what it used to
     * be. Nothing on this side is destroyed until the destination shard is answerable for the hold: a
     * failure at any point before that leaves the rocket standing, fuelled, with everything still in
     * it, and the launch simply does not happen. Once the hold has been handed over it is destroyed
     * here and that destruction is written to disk, because a cleared chest that is still full on
     * disk is a second copy waiting for the next crash.</p>
     *
     * <p>Passengers are not taken apart at all. They are handed over as themselves, by the same
     * mechanism that carries anyone walking across a border, so there is no moment where a player's
     * inventory exists in a table and nowhere else - which is what the old path did, and what made
     * every failure after it a loss.</p>
     */
    public static void commitLaunch(final ZorwethPlugin plugin, final Block computer, final Player clicker) {
        if (!computer.getWorld().getName().equals(plugin.getSourceWorld())) {
            clicker.sendMessage(Component.text("Rockets can only launch from the overworld.", NamedTextColor.RED));
            return;
        }

        final LaunchHandler.RocketManifestResult manifestResult = LaunchHandler.collectLaunchManifest(plugin,
            computer, clicker, plugin.getRocketClipboard());
        if (manifestResult.failure() != null) {
            clicker.sendMessage(manifestResult.failure());
            return;
        }

        final RocketManifest manifest = manifestResult.manifest();
        final LaunchHandler.FuelStatus fuelStatus = LaunchHandler.calculateFuelStatus(computer,
            manifest.passengers(), manifest.chests());
        if (fuelStatus.currentFuelKg() < fuelStatus.requiredFuelKg()) {
            clicker.sendMessage(Component.text("Rocket is insufficiently fuelled", NamedTextColor.RED));
            return;
        }

        final int uses = FlightComputer.getUsesRemaining(computer);
        if (uses <= 0) {
            clicker.sendMessage(Component.text("The rocket is broken beyond repair.", NamedTextColor.RED));
            return;
        }

        final CargoService cargo = ShardTransfers.cargo().orElse(null);
        if (cargo == null) {
            clicker.sendMessage(Component.text("Rockets cannot launch right now. Please try again shortly.",
                NamedTextColor.RED));
            return;
        }

        final RocketCargo hold = hold(manifest, uses - 1);
        final Block origin = FlightComputer.getRocketOrigin(computer);

        // Held still, and nothing else done to them. They keep everything they have for as long as
        // this takes, and get it all back by simply being let go if the launch does not happen
        for (final RocketManifestPassenger passenger : manifest.passengers()) {
            plugin.getStasisHandler().putInStasis(Bukkit.getPlayer(passenger.playerUuid()));
        }
        // What is in the hold has now been read, and is about to exist at the destination as well.
        // Anything taken out of a chest between here and the handover settling would be taken out of
        // a copy that has already gone, so for that moment nothing may reach the rocket at all
        plugin.getLaunchGuard().arm(manifest.transferId(), box(plugin, origin));
        clicker.sendMessage(Component.text("Preparing rocket transfer.", NamedTextColor.GREEN));

        // Addressed to where the pilot aimed. Which shard that is is the proxy's to work out, and the
        // exact spot is the landing shard's - this side names a place, not a server
        cargo.send(manifest.transferId(), RocketCargo.TYPE, hold.toBytes(),
                new PlayerLocation(manifest.destinationWorld(), manifest.destinationRequestedX(), 0.0,
                    manifest.destinationRequestedZ()))
            .whenComplete((response, error) -> Bukkit.getScheduler().runTask(plugin,
                () -> onHoldSent(plugin, manifest, origin, clicker, response, error)));
    }

    private static void onHoldSent(final ZorwethPlugin plugin, final RocketManifest manifest,
                                   final Block origin, final Player clicker,
                                   final CargoSendResponse response, final Throwable error) {
        if (error != null || !CargoService.sent(response)) {
            // Nothing has been touched. The rocket is still standing with everything in it and the
            // passengers still have everything they had, so there is nothing to put back
            plugin.getLogger().log(Level.SEVERE, "Could not hand over a rocket's hold for transfer "
                + manifest.transferId() + (error == null
                    ? ": " + response.status() + " " + response.failureMessage() : ""), error);
            plugin.getLaunchGuard().disarm(manifest.transferId());
            releasePassengers(plugin, manifest);
            clicker.sendMessage(Component.text("Rocket launch failed. Nothing has been moved - please try "
                + "again shortly.", NamedTextColor.RED));
            return;
        }

        // The hold belongs to the other shard from here on, so this copy of it must go - and go
        // durably. Leaving it is duplicating it
        clearRocket(plugin.getRocketClipboard(), origin);
        flushCleared(plugin, origin);
        // There is nothing left to guard: the rocket is air and its chests are somebody else's
        plugin.getLaunchGuard().disarm(manifest.transferId());

        clicker.sendMessage(Component.text("Ignition.", NamedTextColor.GREEN));
        awaitLanding(plugin, manifest, clicker, 0);
    }

    /**
     * Waits for the landing shard to say where the rocket ended up, then sends the passengers there.
     *
     * <p>Asked for rather than pushed, and worth being plain about what running out of patience here
     * means: the hold has landed or will land regardless - it is owned by the other shard and its
     * sweep will put it down - and the passengers keep everything they are carrying. What they lose
     * is the ride.</p>
     */
    private static void awaitLanding(final ZorwethPlugin plugin, final RocketManifest manifest,
                                     final Player clicker, final int attempt) {
        if (attempt >= LANDING_ATTEMPTS) {
            plugin.getLogger().severe("Rocket " + manifest.transferId() + " was handed over but its landing "
                + "was not reported within " + (LANDING_ATTEMPTS * LANDING_POLL_TICKS / 20L) + "s. Its hold "
                + "is safe with " + manifest.destinationServer() + " and will be landed there; its "
                + "passengers stay here with everything they were carrying");
            releasePassengers(plugin, manifest);
            clicker.sendMessage(Component.text("The rocket launched without you. Nothing you were carrying "
                + "was lost - please contact an admin about the rocket itself.", NamedTextColor.RED));
            return;
        }

        final CargoService cargo = ShardTransfers.cargo().orElse(null);
        if (cargo == null) {
            releasePassengers(plugin, manifest);
            return;
        }
        cargo.whereItLanded(manifest.transferId())
            .whenComplete((landedAt, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (error == null && landedAt != null) {
                    boardPassengers(plugin, manifest, landedAt);
                    return;
                }
                Bukkit.getScheduler().runTaskLater(plugin,
                    () -> awaitLanding(plugin, manifest, clicker, attempt + 1), LANDING_POLL_TICKS);
            }));
    }

    /**
     * Sends each passenger to their own seat in the rocket, wherever it came down.
     */
    private static void boardPassengers(final ZorwethPlugin plugin, final RocketManifest manifest,
                                        final PlayerLocation landedAt) {
        for (final RocketManifestPassenger passenger : manifest.passengers()) {
            final Player player = Bukkit.getPlayer(passenger.playerUuid());
            if (player == null) {
                // They disconnected while the rocket was on its way. Nothing was taken off them, so
                // they are wherever they last were with everything they had
                continue;
            }
            plugin.getStasisHandler().removeStasis(player);
            final RocketEntityPosition seat = passenger.relativePosition();
            if (!ShardTransfers.toLocation(player, landedAt.world(),
                landedAt.x() + seat.x(),
                // Above the seat, as the old arrival did - the seat itself is inside a block
                landedAt.y() + seat.y() + 1.2,
                landedAt.z() + seat.z())) {
                plugin.getLogger().severe("Could not send " + passenger.playerUuid() + " to the rocket that "
                    + "landed at " + landedAt + "; they keep everything they are carrying");
                player.sendMessage(Component.text("The rocket landed without you. Nothing you were carrying "
                    + "was lost.", NamedTextColor.RED));
            }
        }
    }

    private static void releasePassengers(final ZorwethPlugin plugin, final RocketManifest manifest) {
        for (final RocketManifestPassenger passenger : manifest.passengers()) {
            final Player player = Bukkit.getPlayer(passenger.playerUuid());
            if (player != null) {
                plugin.getStasisHandler().removeStasis(player);
            }
        }
    }

    /**
     * Writes the now-empty rocket pad to disk.
     *
     * <p>Not optional. Until this happens the chests are cleared only in memory, and what is on disk
     * is the rocket as it was, full - so a server killed between the handover and the next autosave
     * comes back with a second copy of everything that just left.</p>
     */
    private static void flushCleared(final ZorwethPlugin plugin, final Block origin) {
        final Region region = plugin.getRocketClipboard().getRegion();
        ChunkFlush.blocks(origin.getWorld(), origin.getX(), origin.getZ(),
            origin.getX() + region.getWidth(), origin.getZ() + region.getLength(), plugin.getLogger());
    }

    private static LaunchGuard.Box box(final ZorwethPlugin plugin, final Block origin) {
        final Region region = plugin.getRocketClipboard().getRegion();
        return new LaunchGuard.Box(origin.getWorld().getName(),
            origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + region.getWidth() - 1,
            origin.getY() + region.getHeight() - 1,
            origin.getZ() + region.getLength() - 1);
    }

    private static RocketCargo hold(final RocketManifest manifest, final int usesRemaining) {
        final List<RocketCargo.Chest> chests = new ArrayList<>();
        for (final RocketManifestChest chest : manifest.chests()) {
            chests.add(RocketCargo.Chest.of(chest.relativePosition(), chest.contents()));
        }
        return new RocketCargo(manifest.destinationWorld(), manifest.destinationRequestedX(),
            manifest.destinationRequestedZ(), manifest.pilotUuid(), manifest.flightComputerGroupId(),
            manifest.fuelKg(), usesRemaining, chests);
    }

    private static void clearRocket(final Clipboard clipboard, final Block origin) {
        final Region region = clipboard.getRegion();
        final BlockVector3 schematicNorthWestCorner = region.getMinimumPoint();

        for (final BlockVector3 position : region) {
            final BlockVector3 relative = position.subtract(schematicNorthWestCorner);
            final Block actualBlock = origin.getRelative(relative.getX(), relative.getY(), relative.getZ());
            Reinforcement reinforcement = ReinforcementLogic.getReinforcementAt(actualBlock.getLocation());
            if (reinforcement != null) {
                reinforcement.setHealth(-1);
            }
            actualBlock.setType(Material.AIR, false);
        }
    }

    public record FuelStatus(int fuelItems, double currentFuelKg, double requiredFuelKg, int requiredFuelItems,
                             double cargoMassKg, int sittingPlayers) {

    }

    public record RocketWeightPayload(List<RocketManifestPassenger> passengers, List<RocketManifestChest> chests,
                                      double fuelKg) {

    }

    public record RocketManifestResult(RocketManifest manifest, Component failure) {

    }
}

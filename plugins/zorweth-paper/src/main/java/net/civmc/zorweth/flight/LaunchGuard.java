package net.civmc.zorweth.flight;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;

/**
 * Puts a rocket out of reach while its hold is being handed to another shard.
 *
 * <p>There is a moment, between reading what is in a rocket and being told the other shard is
 * answerable for it, where the two exist at once by design - and it is a round trip long rather than
 * instant. Anything taken out of a chest in that moment would be taken out of a copy that has already
 * been sent: the item would arrive at the destination <em>and</em> be in somebody's hand here.</p>
 *
 * <p>So for that moment nothing may touch the rocket. Its passengers are already held still by
 * {@link net.civmc.zorweth.StasisHandler}; this is the same idea applied to its blocks, and it covers
 * hoppers and pistons as well as people, because a hopper does not have to be a player to empty a
 * chest.</p>
 */
public final class LaunchGuard implements Listener {

    private final Map<UUID, Box> inFlight = new ConcurrentHashMap<>();

    /**
     * Seals a rocket. Closes anything already open inside it, because a chest that is open when the
     * guard goes up can still be taken from.
     */
    public void arm(final UUID launchId, final Box box) {
        this.inFlight.put(launchId, box);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final Inventory open = player.getOpenInventory().getTopInventory();
            if (open.getLocation() != null && box.contains(open.getLocation())) {
                player.closeInventory();
            }
        }
    }

    public void disarm(final UUID launchId) {
        this.inFlight.remove(launchId);
    }

    private boolean guarded(final Location location) {
        if (location == null || this.inFlight.isEmpty()) {
            return false;
        }
        for (final Box box : this.inFlight.values()) {
            if (box.contains(location)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(final InventoryOpenEvent event) {
        if (guarded(event.getInventory().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(final InventoryClickEvent event) {
        if (event.getClickedInventory() != null && guarded(event.getClickedInventory().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(final InventoryDragEvent event) {
        if (guarded(event.getInventory().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * Hoppers and droppers, which empty a chest with nobody present.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(final InventoryMoveItemEvent event) {
        if (guarded(event.getSource().getLocation()) || guarded(event.getDestination().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (guarded(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        if (guarded(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        for (final org.bukkit.block.Block pushed : event.getBlocks()) {
            if (guarded(pushed.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        for (final org.bukkit.block.Block pulled : event.getBlocks()) {
            if (guarded(pulled.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * The block area a rocket occupies, in one world.
     */
    public record Box(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        public boolean contains(final Location location) {
            return location.getWorld() != null
                && location.getWorld().getName().equals(this.world)
                && location.getBlockX() >= this.minX && location.getBlockX() <= this.maxX
                && location.getBlockY() >= this.minY && location.getBlockY() <= this.maxY
                && location.getBlockZ() >= this.minZ && location.getBlockZ() <= this.maxZ;
        }
    }
}

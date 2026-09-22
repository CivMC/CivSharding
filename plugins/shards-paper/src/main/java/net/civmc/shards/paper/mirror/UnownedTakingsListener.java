package net.civmc.shards.paper.mirror;

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import net.civmc.shards.paper.border.ShardBorder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * Stops this shard taking things off ground it does not own.
 *
 * <p>{@link UnownedGroundListener} stops this server putting mobs on a neighbour's land and
 * {@link UnownedEntityView} stops it showing what is there. Neither stops it <em>collecting</em> any
 * of it, and hiding an entity does not make it unreachable: what a player is shown is packets, but
 * picking an item up is a distance test the server does on its own, against a real entity, whether or
 * not that entity was ever drawn.</p>
 *
 * <p>So a player standing on the last block inside their shard, with an invisible dropped item of
 * this server's one block past the line, picked it up. Nothing appeared, nothing was clicked, and an
 * item that was never legitimately this shard's arrived in their inventory. The same held for
 * experience, for shearing or milking or leashing something out there, and for killing it and taking
 * what it left.</p>
 *
 * <p><strong>Refused, not moved, and the reason is duplication.</strong> Anything that came from our
 * side and wandered out is already dragged back by
 * {@link net.civmc.shards.paper.border.BorderEntitySweep}, which is the whole of what "bring it
 * home" can honestly mean. What is left is mostly not ours in any sense worth honouring: a shard's
 * world is a <em>clone</em>, and cloning a world clones its entities, so every minecart, stand and
 * cow that existed at the split exists on every shard under the same uuid. Our copy of one standing
 * on a neighbour's ground is a duplicate of an entity they hold the real one of - and pulling it
 * inside would make that duplicate real, reachable and lootable while they still have theirs.</p>
 *
 * <p>Nothing at runtime can tell that copy from something this server genuinely spawned out there
 * before the ground was sealed. A cow past the line carries no record of which it is. So a rule that
 * moved things in would have to move the duplicates in too, which is why there is no such rule.</p>
 *
 * <p><strong>Not tied to {@code hide-unowned-entities}.</strong> That setting is about what a player
 * sees and what can be farmed near a border, and an operator may reasonably turn it off while judging
 * the farming question. This is about what a player <em>gets</em>, which is not a question of
 * cosmetics and is not theirs to turn off.</p>
 *
 * <p>Players are deliberately not protected from each other by the damage rule below. Somebody past
 * this shard's line is mid-crossing or about to be handed over, and what happens to them there is the
 * crossing machinery's business - see {@code SeamCrossing}. This rule is about taking things, and a
 * player is not a thing to be taken.</p>
 */
public final class UnownedTakingsListener implements Listener {

    private final ShardBorder border;

    public UnownedTakingsListener(final ShardBorder border) {
        this.border = border;
    }

    /**
     * An item lying on somebody else's ground is not this shard's to pick up.
     *
     * <p>{@code PlayerAttemptPickupItemEvent} rather than the pickup itself, because it is the one
     * that fires before anything is taken and cancels cleanly - the older event is deprecated and
     * fires with the stack already half accounted for.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(final PlayerAttemptPickupItemEvent event) {
        if (isUnowned(event.getItem().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * The same for a mob of ours picking something up out there, which is how an item that cannot be
     * collected by hand would otherwise end up in a zombie's fist and walk back over the line.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPickup(final EntityPickupItemEvent event) {
        if (isUnowned(event.getItem().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * A hopper is a pickup too, and the only one with no player anywhere near it.
     *
     * <p>Hard to reach with a hopper in the ground, which can only take what is in the block above
     * it - a block outside the border has a hopper outside the border under it. A hopper minecart is
     * the one that can: it takes what it rolls over, and a rail running along a seam puts it within
     * reach of the far side.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopper(final InventoryPickupItemEvent event) {
        if (isUnowned(event.getItem().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * Experience is picked up the same way an item is - by being near it - and is just as much
     * something this shard did not produce.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExperience(final PlayerPickupExperienceEvent event) {
        if (isUnowned(event.getExperienceOrb().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * Shearing, milking, leashing, saddling, or taking something out of a chest on a cart.
     *
     * <p>Mostly unreachable, because the thing is hidden and a client cannot click what it has not
     * been sent. Mostly is not the same as never: tracking is decided once, so something that walks
     * out stays visible until the sweep catches it, and that window is exactly long enough to shear a
     * sheep in.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEntityEvent event) {
        if (isUnowned(event.getRightClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(final PlayerArmorStandManipulateEvent event) {
        if (isUnowned(event.getRightClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * Killing something out there for what it drops.
     *
     * <p>Only things, never people. A player past this shard's line is mid-crossing rather than
     * standing on a neighbour's land by choice, and what happens to them there belongs to the
     * crossing, not here.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player) {
            return;
        }
        if (isUnowned(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    private boolean isUnowned(final Location at) {
        return this.border.isConfigured() && this.border.isOutside(at);
    }
}

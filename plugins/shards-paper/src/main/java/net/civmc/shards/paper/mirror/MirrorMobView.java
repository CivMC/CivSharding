package net.civmc.shards.paper.mirror;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.civmc.shards.api.MobPositionMessage;
import net.civmc.shards.api.mirror.MirrorMob;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Camel;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Draws the minecarts, animals and dropped items a neighbouring shard has, on ground this one can
 * see.
 *
 * <p>The last of the things a border was missing. With the blocks, the frames, the signs and the
 * people drawn, what was still absent was everything that moves and is not a person - and a town with
 * no animals in its pens and no carts on its rails looks abandoned in a way that is hard to name
 * until it is fixed.</p>
 *
 * <p><strong>None of them exist here.</strong> Packets and nothing else: no entity, nothing in the
 * world, nothing any plugin can find, nothing to hit, shear, milk, kill or pick up. A dropped item
 * drawn across a border is the clearest case in the whole mirror of why that matters - there is
 * nothing there to take, so there is nothing to duplicate.</p>
 *
 * <p><strong>A fresh uuid, never the owner's.</strong> Every shard's world began as a copy of one
 * map, so a cow from before the split exists on every shard under the same uuid, and a client keys
 * entities by uuid as well as by id - whichever arrives second is refused. Mirrored players are the
 * exception that proves it: a player's uuid really is unique, and even there it had to be taken off
 * a ghost before the real person could arrive. The owner's uuid is kept only as the key that matches
 * one announcement to the last.</p>
 *
 * <p><strong>What is not drawn, and why.</strong> A baby is drawn full grown, a horse in the wrong
 * colours, a boat in oak whatever it is made of: all of those are numbered metadata fields that
 * cannot be identified by their type alone, and a guessed field number is what once took every player
 * on both shards offline. A dropped item's stack is the exception, because an item field <em>can</em>
 * be identified that way - see {@link LearnedEntityDataLayout} - and without it a dropped item is
 * nothing at all rather than merely the wrong shade.</p>
 *
 * <p>Passengers are not drawn as passengers. Somebody riding a minecart on the far side is announced
 * by the player machinery at the cart's position, so they are drawn standing where the cart is,
 * moving with it, rather than sitting in it.</p>
 */
public final class MirrorMobView implements Listener, MirrorMobs {

    // What a client is shown, and roughly their own tracking range. Beyond it they are removed rather
    // than left standing in a field
    private static final double SHOW_WITHIN = 160.0;
    // How long one survives without being mentioned again. The same three seconds a mirrored player
    // gets, and for the same reason: short enough that a shard going down does not leave a herd
    // standing in it, long enough that a hiccup does not flicker
    private static final long FORGET_AFTER_NANOS = TimeUnit.SECONDS.toNanos(3L);

    // Null when nothing here can say what number a field has, which is a server with no packet
    // library loaded or one where it could not be read
    private final LearnedEntityDataLayout layout;

    // viewer -> the owner's id for the thing -> what was drawn for it
    private final Map<UUID, Map<UUID, Drawn>> drawn = new ConcurrentHashMap<>();
    // Everything recently announced, so one that stops being mentioned can be taken away
    private final Map<UUID, Long> lastHeardOf = new ConcurrentHashMap<>();

    public MirrorMobView(final LearnedEntityDataLayout layout) {
        this.layout = layout;
    }

    /**
     * Applies one announcement. Main thread.
     */
    @Override
    public void apply(final MobPositionMessage message) {
        if (!available()) {
            return;
        }
        final long now = System.nanoTime();
        for (final MirrorMob subject : message.mobs()) {
            this.lastHeardOf.put(subject.uuid(), now);
            for (final Player viewer : Bukkit.getOnlinePlayers()) {
                show(viewer, message.world(), subject);
            }
        }
    }

    /**
     * Takes away anything that has stopped being announced - it wandered out of range of every
     * border, was killed, was picked up, or its shard went down. Main thread, on a timer.
     */
    @Override
    public void expire() {
        if (!available()) {
            return;
        }
        final long now = System.nanoTime();
        this.lastHeardOf.entrySet().removeIf(entry -> {
            if (now - entry.getValue() < FORGET_AFTER_NANOS) {
                return false;
            }
            forgetEverywhere(entry.getKey());
            return true;
        });
    }

    private void show(final Player viewer, final String world, final MirrorMob subject) {
        final Map<UUID, Drawn> theirs = this.drawn.computeIfAbsent(viewer.getUniqueId(),
            ignored -> new ConcurrentHashMap<>());
        final boolean visible = viewer.getWorld().getName().equals(world)
            && viewer.getLocation().distanceSquared(
                new Location(viewer.getWorld(), subject.x(), subject.y(), subject.z()))
            <= SHOW_WITHIN * SHOW_WITHIN;
        final Drawn existing = theirs.get(subject.uuid());
        if (!visible) {
            if (existing != null) {
                theirs.remove(subject.uuid());
                send(viewer, new WrapperPlayServerDestroyEntities(existing.entityId()));
            }
            return;
        }
        if (existing == null) {
            spawn(viewer, theirs, subject);
            return;
        }
        if (existing.type() != bukkitTypeOf(subject)) {
            // The owner reused a uuid for something of a different kind, which nothing should do -
            // but a client cannot be told an entity has changed shape, so it is drawn again
            theirs.remove(subject.uuid());
            send(viewer, new WrapperPlayServerDestroyEntities(existing.entityId()));
            spawn(viewer, theirs, subject);
            return;
        }
        send(viewer, new WrapperPlayServerEntityTeleport(existing.entityId(),
            new Vector3d(subject.x(), subject.y(), subject.z()), subject.yaw(), subject.pitch(),
            subject.onGround()));
        send(viewer, new WrapperPlayServerEntityHeadLook(existing.entityId(), subject.headYaw()));
        if (!existing.equipment().equals(subject.equipment())) {
            // Only what it has taken off or picked up. These messages carry the whole of it every
            // tick so that nothing can be missed; sending the whole of it every tick is what would be
            // wasteful
            dress(viewer, existing.entityId(), existing.equipment(), subject.equipment());
        }
        if (!existing.item().equals(subject.item()) || !existing.pose().equals(subject.pose())
            || existing.sitting() != subject.sitting() || existing.baby() != subject.baby()
            || !existing.effects().equals(subject.effects())) {
            // A stack that grew when another was thrown onto it, or a different one entirely - and a
            // pose, which is the one thing here that a thing standing still can still change about
            // itself
            describe(viewer, existing.entityId(), subject);
        }
        theirs.put(subject.uuid(), new Drawn(existing.entityId(), existing.type(), subject.equipment(),
            subject.item(), subject.pose(), subject.sitting(), subject.baby(), subject.effects()));
    }

    private void spawn(final Player viewer, final Map<UUID, Drawn> theirs, final MirrorMob subject) {
        final EntityType bukkitType = bukkitTypeOf(subject);
        final com.github.retrooper.packetevents.protocol.entity.type.EntityType type =
            bukkitType == null ? null : SpigotConversionUtil.fromBukkitEntityType(bukkitType);
        if (type == null) {
            // Something this version cannot make: a neighbour on a newer game, or a plugin's own kind
            // of entity. Left undrawn rather than drawn as something else - a hole is honest, and a
            // cow standing in for a camel is not
            return;
        }
        final int entityId = FakeEntityIds.next();
        theirs.put(subject.uuid(), new Drawn(entityId, bukkitType, subject.equipment(), subject.item(),
            subject.pose(), subject.sitting(), subject.baby(), subject.effects()));
        send(viewer, new WrapperPlayServerSpawnEntity(entityId,
            // A uuid of our own, never the owner's - see the class comment
            Optional.of(UUID.randomUUID()), type,
            new Vector3d(subject.x(), subject.y(), subject.z()), subject.pitch(), subject.yaw(),
            subject.headYaw(), 0, Optional.empty()));
        send(viewer, new WrapperPlayServerEntityHeadLook(entityId, subject.headYaw()));
        dress(viewer, entityId, Map.of(), subject.equipment());
        describe(viewer, entityId, subject);
    }

    /**
     * Sends the numbered fields that are carried: the stack a dropped item is, what the thing is
     * doing with itself, whether it is a baby, whether a camel is sat down, and the swirls of any
     * potion it is under.
     *
     * <p>Nothing else: a cow's colour and a boat's wood are numbered fields that cannot be
     * identified by their type alone, and a guessed number is what once took every player on both
     * shards offline. These two can be asked for - an entity has exactly one field holding an item,
     * and a pose is declared on the base entity class - so these two are sent. Where the layout will
     * not say, nothing is sent: a mirrored item lies on the ground invisible rather than the client
     * being handed a likely-looking number, which is the rule the whole of
     * {@link LearnedEntityDataLayout} exists to keep.</p>
     */
    private void describe(final Player viewer, final int entityId, final MirrorMob subject) {
        if (this.layout == null) {
            return;
        }
        final EntityType bukkitType = bukkitTypeOf(subject);
        if (bukkitType == null) {
            return;
        }
        final List<EntityData<?>> described = new ArrayList<>(5);
        carried(subject, bukkitType).ifPresent(described::add);
        // Standing is not sent for something that has never been anything else: it is what a client
        // draws an entity as anyway, and this runs for every thing in sight every time one moves
        if (!subject.pose().isEmpty() && !"STANDING".equals(subject.pose())) {
            this.layout.pose(Poses.of(subject.pose())).ifPresent(described::add);
        }
        this.layout.baby(bukkitType, subject.baby()).ifPresent(described::add);
        if (isACamel(bukkitType)) {
            // On this server's clock, not the owner's - see the layout. Camels only: the field is
            // theirs alone, and a cat sitting keeps it somewhere else entirely
            this.layout.camelSitting(subject.sitting(), viewer.getWorld().getGameTime())
                .ifPresent(described::add);
        }
        if (isLiving(bukkitType)) {
            // Only a living thing has this field. Sending it for a minecart would be sending a number
            // that means something else there, which is the one mistake this whole arrangement is
            // built to make impossible
            this.layout.effectParticles(subject.effects()).ifPresent(described::add);
        }
        if (!described.isEmpty()) {
            send(viewer, new WrapperPlayServerEntityMetadata(entityId, described));
        }
    }

    /**
     * The stack a dropped item is, ready to send. Empty for everything else, which has no item field
     * for the layout to find.
     */
    private Optional<EntityData<?>> carried(final MirrorMob subject, final EntityType bukkitType) {
        if (subject.item().isEmpty()) {
            return Optional.empty();
        }
        final org.bukkit.inventory.ItemStack item = MirrorEquipment.decode(subject.item());
        if (item == null) {
            return Optional.empty();
        }
        return this.layout.itemData(bukkitType, SpigotConversionUtil.fromBukkitItemStack(item));
    }

    private void dress(final Player viewer, final int entityId, final Map<String, String> was,
                       final Map<String, String> now) {
        final List<Equipment> worn = MirrorEquipment.of(was, now);
        if (worn.isEmpty()) {
            return;
        }
        send(viewer, new WrapperPlayServerEntityEquipment(entityId, worn));
    }

    private void forgetEverywhere(final UUID subjectUuid) {
        for (final Map.Entry<UUID, Map<UUID, Drawn>> viewer : this.drawn.entrySet()) {
            final Drawn one = viewer.getValue().remove(subjectUuid);
            if (one == null) {
                continue;
            }
            final Player player = Bukkit.getPlayer(viewer.getKey());
            if (player != null) {
                send(player, new WrapperPlayServerDestroyEntities(one.entityId()));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        // Nothing is sent: they have gone, and their client has forgotten everything anyway
        this.drawn.remove(event.getPlayer().getUniqueId());
    }

    /**
     * By what the kind <em>is</em> rather than by name, so a husk of one counts as one.
     */
    private static boolean isACamel(final EntityType type) {
        final Class<?> entityClass = type.getEntityClass();
        return entityClass != null && Camel.class.isAssignableFrom(entityClass);
    }

    private static boolean isLiving(final EntityType type) {
        final Class<?> entityClass = type.getEntityClass();
        return entityClass != null && LivingEntity.class.isAssignableFrom(entityClass);
    }

    private static EntityType bukkitTypeOf(final MirrorMob subject) {
        try {
            return EntityType.valueOf(subject.type());
        } catch (final IllegalArgumentException notAThingHere) {
            return null;
        }
    }

    private static void send(final Player viewer, final PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    /**
     * Whether packets can be sent at all. PacketEvents is a soft dependency: everything else about
     * the mirror works without it, and a shard that will not start is worse than one that cannot show
     * a cow across a border.
     */
    private static boolean available() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
        } catch (final RuntimeException | NoClassDefFoundError exception) {
            return false;
        }
    }

    /**
     * @param type what it was drawn as, so a uuid that comes back as something else is redrawn rather
     *     than teleported into the wrong shape
     * @param equipment what it was last drawn wearing, so a packet goes only when that changes
     * @param item the stack it was last drawn as, for the same reason
     * @param pose what it was last drawn doing, so a fox that lies down is described again and one
     *     that goes on lying there is not
     * @param sitting whether it was last drawn sat down, for the same reason
     * @param baby whether it was last drawn as a baby, for the same reason
     * @param effects the potion colours it was last drawn giving off, for the same reason
     */
    private record Drawn(int entityId, EntityType type, Map<String, String> equipment, String item,
                         String pose, boolean sitting, boolean baby, List<Integer> effects) {
    }
}

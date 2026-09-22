package net.civmc.shards.paper.mirror;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Reads a metadata field number off the server's own classes.
 *
 * <p>The numbers are not invented by the protocol; the server declares each one as a field on the
 * class the entity is, and then sends whatever that field says. So the number can simply be read from
 * there, where it is <em>defined</em>, rather than inferred from packets the server happens to send.
 * It is the same number the server itself will use, on whatever version is running, and it is
 * available before anybody has logged in.</p>
 *
 * <p>This is what {@link LearnedEntityDataLayout} could never do. Watching packets can only identify
 * a field by its type, so it answers for the item in a frame - an entity has exactly one field holding
 * an item - and cannot answer for skin layers, which are a byte among other bytes. Worse, it can only
 * learn what this server has been asked to send: a shard with no item frames of its own never sees one
 * described, so it draws every frame on every neighbour empty forever. That is what was happening.</p>
 *
 * <p><strong>Still never a guess.</strong> The number is read by name and then checked by type: the
 * field must exist, must be an entity data accessor, and its serializer must be the one expected for
 * what is going to be sent. A version that renames or retypes it teaches nothing here rather than
 * teaching something wrong, and nothing is sent - which is the rule that came out of a guessed field
 * number taking every player on both shards offline.</p>
 *
 * <p>By reflection and by name rather than against the server jar, because this plugin is built
 * against the API and not against the server. A failure to read anything at all is expected on a
 * server that is not this one, and leaves the watcher as the only source, exactly as before.</p>
 */
final class ServerFieldNumbers {

    private static final String ACCESSOR = "net.minecraft.network.syncher.EntityDataAccessor";
    private static final String SERIALIZERS = "net.minecraft.network.syncher.EntityDataSerializers";

    // Read once each and remembered, answer or no answer. Nothing about this can change while the
    // server is running, and the reflection is not free
    private static final Map<String, OptionalInt> READ = new ConcurrentHashMap<>();

    private ServerFieldNumbers() {
    }

    /**
     * Which field of an item frame holds the item it is showing.
     */
    static OptionalInt itemFrameItem(final Logger logger) {
        return numberOf(logger, "net.minecraft.world.entity.decoration.ItemFrame", "DATA_ITEM",
            "ITEM_STACK");
    }

    /**
     * Which field of a player holds the skin layers they have switched on - the hat and the jacket.
     *
     * <p>Two classes because the player was split in two: everything a person-shaped entity has moved
     * to {@code Avatar}, and on a version from before that it is still on {@code Player}. Both are
     * asked for by name and the first one that answers is the answer.</p>
     */
    static OptionalInt playerSkinLayers(final Logger logger) {
        final OptionalInt avatar = numberOf(logger, "net.minecraft.world.entity.Avatar",
            "DATA_PLAYER_MODE_CUSTOMISATION", "BYTE");
        if (avatar.isPresent()) {
            return avatar;
        }
        return numberOf(logger, "net.minecraft.world.entity.player.Player",
            "DATA_PLAYER_MODE_CUSTOMISATION", "BYTE");
    }

    /**
     * Which field holds what any entity is doing with itself: standing, sneaking, swimming, gliding.
     */
    static OptionalInt pose(final Logger logger) {
        return numberOf(logger, "net.minecraft.world.entity.Entity", "DATA_POSE", "POSE");
    }

    /**
     * Which field says a camel is sitting down.
     *
     * <p>Not its pose, though a sitting camel has one. The client decides whether to draw a camel sat
     * down from the <em>sign</em> of the tick its pose last changed on - negative means sitting - and
     * animates the sitting down from how long ago that was. So a camel sent nothing but
     * {@code SITTING} stands up perfectly straight on the far side, which is what it did.</p>
     */
    static OptionalInt camelPoseChange(final Logger logger) {
        return numberOf(logger, "net.minecraft.world.entity.animal.camel.Camel",
            "LAST_POSE_CHANGE_TICK", "LONG");
    }

    /**
     * Which field says an animal is a baby, for everything that grows up.
     */
    static OptionalInt ageableBaby(final Logger logger) {
        return numberOf(logger, "net.minecraft.world.entity.AgeableMob", "DATA_BABY_ID", "BOOLEAN");
    }

    /**
     * The same for a zombie, which has a baby field of its own rather than growing up like an animal.
     *
     * <p>Two class names because the zombie moved house: it is under {@code monster.zombie} on this
     * version and was directly under {@code monster} before. Asked for by name in turn, and the first
     * one that answers is the answer.</p>
     */
    static OptionalInt zombieBaby(final Logger logger) {
        final OptionalInt moved = numberOf(logger, "net.minecraft.world.entity.monster.zombie.Zombie",
            "DATA_BABY_ID", "BOOLEAN");
        if (moved.isPresent()) {
            return moved;
        }
        return numberOf(logger, "net.minecraft.world.entity.monster.Zombie", "DATA_BABY_ID", "BOOLEAN");
    }

    /**
     * Which field holds the swirls a living thing is giving off from the potions it is under.
     *
     * <p>They are not a particle anybody sends: the client draws them for itself from this list, one
     * colour per visible effect, which is why a mirrored player under a potion gave off nothing at
     * all however many particles the real one was surrounded by.</p>
     */
    static OptionalInt effectParticles(final Logger logger) {
        return numberOf(logger, "net.minecraft.world.entity.LivingEntity", "DATA_EFFECT_PARTICLES",
            "PARTICLES");
    }

    private static OptionalInt numberOf(final Logger logger, final String className,
                                        final String fieldName, final String serializerName) {
        return READ.computeIfAbsent(className + '#' + fieldName,
            ignored -> read(logger, className, fieldName, serializerName));
    }

    private static OptionalInt read(final Logger logger, final String className, final String fieldName,
                                    final String serializerName) {
        try {
            final Field field = Class.forName(className).getDeclaredField(fieldName);
            field.setAccessible(true);
            final Object accessor = field.get(null);
            if (accessor == null || !Class.forName(ACCESSOR).isInstance(accessor)) {
                logger.warning(fieldName + " on " + className + " is not an entity data accessor on "
                    + "this server, so nothing that needs it will be drawn");
                return OptionalInt.empty();
            }
            if (!isSerializer(accessor, serializerName)) {
                // The number is there and is for something else. Sending it would be the protocol
                // error this whole approach exists to avoid
                logger.warning(fieldName + " on " + className + " no longer holds a " + serializerName
                    + " on this server, so nothing that needs it will be drawn");
                return OptionalInt.empty();
            }
            final Method id = accessor.getClass().getMethod("id");
            final int number = (int) id.invoke(accessor);
            logger.info("Read " + fieldName + " for " + className + " at metadata index " + number
                + " off the server's own classes");
            return OptionalInt.of(number);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError cannotRead) {
            // Expected on a server this was not written against. What needs the number is drawn
            // without it, or not drawn
            logger.info("Could not read " + fieldName + " off " + className + " (" + cannotRead
                + "); falling back to what this server is seen to send");
            return OptionalInt.empty();
        }
    }

    private static boolean isSerializer(final Object accessor, final String serializerName)
        throws ReflectiveOperationException {
        final Object serializer = accessor.getClass().getMethod("serializer").invoke(accessor);
        final Field expected = Class.forName(SERIALIZERS).getDeclaredField(serializerName);
        expected.setAccessible(true);
        return serializer == expected.get(null);
    }
}

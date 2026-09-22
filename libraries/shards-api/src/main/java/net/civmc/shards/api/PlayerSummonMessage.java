package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Asks whichever shard has this player to send them somewhere.
 *
 * <p>The half of teleporting that cannot be done by the shard the command was typed on. Sending
 * <em>yourself</em> to somebody far away is a handover, which is a thing this server can do to a
 * player standing in front of it; sending <em>somebody else</em> who is not here is not - they are
 * being played by another server, which alone can read them, write them back and let them go.</p>
 *
 * <p>Announced to every shard rather than addressed to one, in the same way and for the same reason
 * as the rest of the mirror: which shard somebody is on is known only at the moment it is asked, and
 * the answer can change between the asking and the sending. Every shard hears this and all but one
 * find that they do not have this player, which costs them a map lookup. The one that does acts.</p>
 *
 * <p>No answer comes back. Whether it worked is visible where it matters - the player moves - and a
 * summons that finds nobody is the same as one that arrives a moment after they logged out, which is
 * not a fault worth a reply channel.</p>
 *
 * @param playerUuid who is to be sent, by id rather than by name: the name was resolved where it was
 *     typed, and resolving it again on the far side could find somebody else
 * @param to where to put them, which may be on the shard that acts on this - in which case it is an
 *     ordinary teleport there and nothing crosses a border at all
 * @param follow somebody to step onto when they arrive, or null. For "bring him to me", where the
 *     exact spot is wherever that person is standing by the time the summoned player lands, which is
 *     not something the coordinates in {@code to} can keep up with
 */
public record PlayerSummonMessage(UUID messageId, String serverName, UUID playerUuid, PlayerLocation to,
                                  UUID follow, String followName, String askedBy,
                                  long createdAtEpochMillis) {

    public PlayerSummonMessage {
        Objects.requireNonNull(messageId, "messageId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(to, "to");
        followName = followName == null ? "" : followName;
        askedBy = askedBy == null ? "" : askedBy;
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static PlayerSummonMessage create(final String serverName, final UUID playerUuid,
                                             final PlayerLocation to, final UUID follow,
                                             final String followName, final String askedBy) {
        return new PlayerSummonMessage(UUID.randomUUID(), serverName, playerUuid, to, follow, followName,
            askedBy, System.currentTimeMillis());
    }
}

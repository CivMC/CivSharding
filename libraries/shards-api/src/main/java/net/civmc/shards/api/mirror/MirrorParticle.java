package net.civmc.shards.api.mirror;

import java.util.Objects;

/**
 * One burst of particles a shard has just shown its own players, for the shards that can see that
 * spot.
 *
 * <p>Almost everything a place does that is neither a block nor a mob is a particle: the dust off a
 * pick, a splash potion's cloud, an explosion, the crit on a hit, the smoke from a torch being put
 * out. None of it crossed a border, so the ground beyond a seam was a photograph - correct in every
 * block and completely inert.</p>
 *
 * <p>The particle itself travels as the game's own description of it, which is the only faithful way
 * to carry one: a particle is a type <em>and</em> its data, and the data is different for every type -
 * a block's texture, a dust's colour, a vibration's destination. Rather than carry the handful that
 * could be picked apart by hand, the whole thing is written out in the form the game writes it in and
 * read back on the far side, where a version that cannot read one draws nothing rather than drawing
 * the wrong thing.</p>
 *
 * @param particle the particle and its data, Base64 of the game's own encoding of it
 * @param count how many, which for several kinds means "one, aimed" rather than a number
 * @param speed what the game calls max speed, whose meaning also depends on the kind
 * @param longDistance whether it is shown from far off, as an explosion is
 */
public record MirrorParticle(String particle, double x, double y, double z, float offsetX, float offsetY,
                             float offsetZ, int count, float speed, boolean longDistance) {

    public MirrorParticle {
        Objects.requireNonNull(particle, "particle");
    }
}

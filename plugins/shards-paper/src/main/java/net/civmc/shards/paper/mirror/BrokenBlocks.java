package net.civmc.shards.paper.mirror;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.SoundGroup;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * The burst of dust and the crack of a block coming apart, for a block that came apart on another
 * shard.
 *
 * <p>A block breaking is two separate things: the block becoming air, and the shower of its own
 * texture with the sound that goes with it. Only the first of those is a block change, so only the
 * first crossed a border - a neighbour mining a wall along a seam had it vanish a block at a time in
 * perfect silence, which reads less like somebody digging than like the world being edited.</p>
 *
 * <p>Made here rather than carried. What the game sends for this is a level event, which is a
 * separate packet from the block change and would need its own announcement, its own fanout and its
 * own rate limit; and everything it carries - where, and which block - is in the block change that
 * has already arrived. So the receiver makes it out of what it already knows: the block that was
 * there is the one it was drawing until this moment.</p>
 *
 * <p>The cost of making it rather than carrying it is that this cannot tell a block that was mined
 * from one that was washed away or pushed by a piston. Both show the burst. That is a small
 * over-reporting of something cosmetic, and the alternative is a second channel for effects.</p>
 */
final class BrokenBlocks {

    // A little short of the sixty-four the game makes, which are spread through the whole cube of the
    // block; these are a shard's worth of scenery and a border can lose a wall at once
    private static final int GRAINS = 24;
    // Half a block either way, so the dust fills where the block was rather than the point it stood at
    private static final double SPREAD = 0.25;

    private BrokenBlocks() {
    }

    /**
     * Whether a block going from {@code was} to {@code now} is one coming apart.
     *
     * <p>Something that was there and now is not. Liquids are left out because they do not shatter -
     * water that has drained away is not a block breaking, and the game shows nothing for it - and
     * anything that was already air cannot break.</p>
     */
    static boolean isABreak(final BlockData was, final BlockData now) {
        if (was == null || now == null || !now.getMaterial().isAir()) {
            return false;
        }
        final Material material = was.getMaterial();
        return !material.isAir() && material != Material.WATER && material != Material.LAVA;
    }

    /**
     * Shows one viewer a block breaking where the neighbour's block has just gone.
     *
     * <p>Per viewer rather than to the world, because this is about a block that does not exist here:
     * only the people being shown that shard's copy of the ground should see its dust.</p>
     */
    static void show(final Player viewer, final Location at, final BlockData was) {
        viewer.spawnParticle(Particle.BLOCK, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5,
            GRAINS, SPREAD, SPREAD, SPREAD, was);
        final SoundGroup sounds = was.getSoundGroup();
        final Sound breaking = sounds == null ? null : sounds.getBreakSound();
        if (breaking == null) {
            return;
        }
        // The volumes the game itself uses for a block being broken, which are the sound group's own
        // rather than anything chosen here
        viewer.playSound(at, breaking, SoundCategory.BLOCKS, (sounds.getVolume() + 1.0f) / 2.0f,
            sounds.getPitch() * 0.8f);
    }
}

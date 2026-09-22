package vg.civcraft.mc.civchat2.shards;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import vg.civcraft.mc.civchat2.CivChat2Manager;

/**
 * Local chat leaving this server for the shard next door.
 *
 * <p>On a sharded network a chat range is a distance and a shard border is not a wall to sound: two
 * players twenty blocks apart with a seam between them are twenty blocks apart, and before this they
 * heard nothing of each other at all. From in the game that is being ignored by somebody standing in
 * front of you.</p>
 *
 * <p>An interface of this plugin's own so that nothing outside this package mentions the shards
 * plugin. Where it is not installed there is no implementation to load and {@link #NOWHERE} is used,
 * which is what an unsharded server has always done.</p>
 */
public interface CrossShardChat {

    CrossShardChat NOWHERE = (sender, at, range, displayName, message) -> {
    };

    /**
     * @param range how far this carries, worked out here - height scaling included - so that every
     *     shard agrees about who was in earshot rather than each applying its own config
     */
    void speak(Player sender, Location at, int range, Component displayName, Component message);

    /**
     * The relay for this server, or {@link #NOWHERE} when there is none.
     *
     * <p>The implementation is only touched once the shards plugin is known to be present, because
     * naming a class from a plugin that is not installed is a linkage error rather than a null.</p>
     */
    static CrossShardChat find(CivChat2Manager manager) {
        if (Bukkit.getPluginManager().getPlugin("Shards") == null) {
            return NOWHERE;
        }
        try {
            return new ShardsRelay(manager);
        } catch (LinkageError | RuntimeException notThere) {
            Bukkit.getLogger().warning("Shards is installed but its chat relay could not be used, so local "
                + "chat stops at every border: " + notThere);
            return NOWHERE;
        }
    }
}

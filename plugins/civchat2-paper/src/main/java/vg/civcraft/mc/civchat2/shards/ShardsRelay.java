package vg.civcraft.mc.civchat2.shards;

import java.util.UUID;
import net.civmc.shards.api.chat.LocalChatRelay;
import net.civmc.shards.api.chat.LocalChatSpeech;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import vg.civcraft.mc.civchat2.CivChat2Manager;

/**
 * The only class here that names the shards plugin.
 *
 * <p>Split off so that {@link CrossShardChat#find} can decide whether to load it at all: a server
 * without shards has none of these types, and a field or a parameter mentioning one is enough to
 * fail.</p>
 *
 * <p>The speaker's name travels already drawn, because stars, custom names and hovers are built from
 * a Player and the listening shard has no such player. What was said travels undrawn, because how it
 * is coloured depends on how far away the listener is, which only the listening shard knows.</p>
 */
final class ShardsRelay implements CrossShardChat {

    private final LocalChatRelay relay;

    ShardsRelay(CivChat2Manager manager) {
        RegisteredServiceProvider<LocalChatRelay> registered =
            Bukkit.getServicesManager().getRegistration(LocalChatRelay.class);
        if (registered == null) {
            throw new IllegalStateException("Shards registered no " + LocalChatRelay.class.getSimpleName());
        }
        this.relay = registered.getProvider();
        this.relay.heard(speech -> deliver(manager, speech));
    }

    @Override
    public void speak(Player sender, Location at, int range, Component displayName, Component message) {
        this.relay.speak(new LocalChatSpeech(
            "", at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), range,
            sender.getUniqueId().toString(), sender.getName(),
            GsonComponentSerializer.gson().serialize(displayName),
            GsonComponentSerializer.gson().serialize(message)));
    }

    /**
     * Main thread: the relay hands these over on it, and delivering chat reads where every player is
     * standing.
     */
    private static void deliver(CivChat2Manager manager, LocalChatSpeech speech) {
        World world = Bukkit.getWorld(speech.world());
        if (world == null) {
            // A world this shard does not have. Shards are cut out of one map, so this is a
            // misconfiguration rather than something to handle
            return;
        }
        manager.receiveRelayedMessage(
            UUID.fromString(speech.senderUuid()), speech.senderName(),
            new Location(world, speech.x(), speech.y(), speech.z()), speech.range(),
            GsonComponentSerializer.gson().deserialize(speech.displayName()),
            GsonComponentSerializer.gson().deserialize(speech.message()));
    }
}

package net.civmc.shards.paper.chat;

import java.util.function.Consumer;
import net.civmc.shards.api.chat.LocalChatRelay;
import net.civmc.shards.api.chat.LocalChatSpeech;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * This shard's end of local chat that carries over a border.
 *
 * <p>The whole of what shards contribute to chat: a line goes out on the fanout, a line comes in off
 * it, and the plugin that owns chat does everything else. What earshot is, how a name is drawn, who
 * is ignoring whom and whether the message should have been sent at all are all settled before this
 * is called, on the shard the speaker is standing on.</p>
 *
 * <p>Nothing is sent when no other shard could hear it. Not an optimisation - a single-shard network
 * would otherwise publish every sentence anybody says to an exchange with nobody bound to it, which
 * is a thing that shows up in the broker's graphs and has to be explained.</p>
 */
public final class ShardLocalChat implements LocalChatRelay {

    private final JavaPlugin plugin;
    private final ShardsClient client;
    private final String serverName;
    private volatile Consumer<LocalChatSpeech> listener;

    public ShardLocalChat(final JavaPlugin plugin, final ShardsClient client, final String serverName) {
        this.plugin = plugin;
        this.client = client;
        this.serverName = serverName;
    }

    /**
     * Who said it is filled in here rather than by the caller. A fanout comes back to its sender, and
     * this name is how a shard knows not to say its own players' lines to them twice - which is not a
     * thing a chat plugin should have to know about.
     */
    @Override
    public void speak(final LocalChatSpeech speech) {
        this.client.publishLocalChat(new LocalChatSpeech(this.serverName, speech.world(), speech.x(),
            speech.y(), speech.z(), speech.range(), speech.senderUuid(), speech.senderName(),
            speech.displayName(), speech.message()));
    }

    @Override
    public void heard(final Consumer<LocalChatSpeech> listener) {
        this.listener = listener;
    }

    /**
     * What another shard has just announced. Called on a broker thread, so it is handed to the main
     * one: delivering chat means reading where every player is standing.
     */
    public void receive(final LocalChatSpeech speech) {
        final Consumer<LocalChatSpeech> hearing = this.listener;
        if (hearing == null) {
            // No chat plugin has asked to be told. Dropped rather than held: a line of chat kept
            // until somebody registers is a line delivered long after it was said
            return;
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> hearing.accept(speech));
    }
}

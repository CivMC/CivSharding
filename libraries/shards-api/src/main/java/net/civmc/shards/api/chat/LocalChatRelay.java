package net.civmc.shards.api.chat;

import java.util.function.Consumer;

/**
 * Carrying local chat across a shard border.
 *
 * <p>Registered by the shards plugin as a Bukkit service and looked up by whatever plugin owns
 * chat, so that the two know nothing of each other beyond this: the chat plugin decides what local
 * chat is - how far it carries, how it is written, who is ignoring whom - and this decides only how
 * it gets to the shard next door. Neither is in a position to do the other's half.</p>
 *
 * <p>A network that is not sharded has no implementation of this registered, and a chat plugin that
 * finds none simply does what it always did.</p>
 */
public interface LocalChatRelay {

    /**
     * Tells the other shards what was just said here. Returns immediately; nothing is waited for and
     * a message that does not arrive is a message nobody heard, which is what a border used to do to
     * all of them.
     */
    void speak(LocalChatSpeech speech);

    /**
     * Sets who is told about speech from the other shards. Called on the main thread.
     *
     * <p>One listener, replaced rather than added to: two things delivering the same message would
     * say it twice, and there is only ever one plugin that owns local chat.</p>
     */
    void heard(Consumer<LocalChatSpeech> listener);
}

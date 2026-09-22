package net.civmc.shards.api.chat;

/**
 * Something said in local chat, on its way to the shards next door.
 *
 * <p>Local chat is a distance from the speaker, and a shard border is not a wall to sound: two
 * players standing twenty blocks apart with a seam between them are twenty blocks apart. Without
 * this they were on different servers and heard nothing of each other, which from in the game is
 * being ignored by somebody standing in front of you.</p>
 *
 * <p><strong>Rendered by the speaker's shard, coloured by the listener's.</strong> The name is
 * carried already drawn - prefixes, stars, hovers and all - because only the shard the speaker is on
 * can build it, and none of that survives being reduced to a string. The message is carried
 * undrawn, because how it is coloured depends on how far away the listener is, which only the
 * listener's shard knows. Both travel as the JSON an Adventure component serializes to, which keeps
 * this record free of any chat library at all.</p>
 *
 * <p>The range travels too, and is the one the speaker's shard worked out - height scaling included.
 * A listener's shard could recompute it from its own config, and would then disagree with the
 * speaker's about who was in earshot whenever the two configs differ, which is not a thing anybody
 * should have to debug from in the game.</p>
 *
 * @param range how far this carries, in blocks, or 0 and below for everywhere
 * @param displayName the speaker's name as their own shard drew it, as component JSON
 * @param message what was said, undrawn, as component JSON
 */
public record LocalChatSpeech(String serverName, String world, double x, double y, double z,
                              int range, String senderUuid, String senderName,
                              String displayName, String message) {
}

package net.civmc.shards.paper;

import java.util.Optional;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.civmc.shards.api.ServerStartupRequest;
import net.civmc.shards.api.ServerStartupResponse;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.config.ShardsPaperConfig;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShardsPaperPlugin extends JavaPlugin {

    private static final long SHUTDOWN_DRAIN_SECONDS = 20L;
    private static final long SAVE_CHECK_TICKS = 20L;
    private static final int MAX_SAVES_PER_RUN = 4;
    // Half a second: often enough that the particles look continuous and that a neighbour going down
    // is noticed while the player is still stood at the border, rare enough to be nothing on a tick
    private static final long BORDER_VIEW_TICKS = 10L;
    private static final long UNOWNED_SWEEP_TICKS = 20L * 5L;
    // Once a second. A chunk that is already mirrored costs a lookup here, so this is about how long
    // after walking towards a border the far side fills in, not about how often work is done
    private static final long MIRROR_TICKS = 20L;
    private static final long MIRROR_REPORT_TICKS = 20L * 30L;
    // Long, because what is written is only a head start: everything saved is read again from its owner
    // the first time anybody looks at it, so a save that is an hour out of date costs nothing
    private static final long MIRROR_SAVE_TICKS = 20L * 60L * 5L;
    private static final long MIRROR_FORGET_TICKS = 20L * 60L * 5L;
    private static final long STARTUP_RETRY_MIN_TICKS = 20L * 5L;
    private static final long STARTUP_RETRY_MAX_TICKS = 20L * 60L;

    private ShardsPaperConfig config;
    private ShardsClient client;
    private final ShardBorder border = new ShardBorder();
    // Read from the login thread, written from whichever thread the startup answer arrives on
    private volatile boolean startupComplete;
    private long startupRetryTicks = STARTUP_RETRY_MIN_TICKS;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            this.config = ShardsPaperConfig.from(getConfig());
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            getLogger().log(Level.SEVERE, "Could not load config.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.client = new ShardsClient(this.config.connectionFactory(), this.config.serverName(), this, getLogger(),
            this::completeStartupHandshake);
        this.client.start();



        startUnownedEntityView();
        startEntitySweep();
        startPeriodicSave();
    }

    @Override
    public void onDisable() {
        // Kick first, so every quit handler runs and every save is sent while the plugin is still able
        // to send it. A player still online when the client closes has their data left owned by a
        // server that is about to stop existing
        for (final Player player : Bukkit.getOnlinePlayers()) {
            player.kick(Component.text("Server is shutting down"));
        }
        if (this.client != null) {
            this.client.close();
        }
    }


    /**
     * Stops this server showing, and stops it creating, entities on ground it does not own.
     *
     * <p>The world does not stop at a border, so this server populates the chunks past its edge with
     * a copy of nothing anybody else can see - a herd of cows standing where the neighbour has a
     * building. The first piece of showing what is really over there is to stop drawing what is
     * not.</p>
     */
    private void startUnownedEntityView() {
        if (!this.config.hideUnownedEntities()) {
            getLogger().warning("Not hiding entities on unowned ground: players will see this server's own "
                + "mobs and items standing past its border, which no other shard can see");
            return;
        }
        // Slow, because it only exists to catch entities that wandered out after they were already
        // being shown. Everything arriving is caught by the tracking event, which costs nothing
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (final Player player : Bukkit.getOnlinePlayers()) {
            }
        }, UNOWNED_SWEEP_TICKS, UNOWNED_SWEEP_TICKS);
    }


    /**
     * Stops the entities that drift over a border and have no move event to refuse.
     *
     * <p>Mobs and vehicles are answered by their own move events, exactly and for nothing. A dropped
     * item, an arrow, primed TNT, a falling block and an experience orb have no such event, so the
     * only way to catch one crossing is to look - every tick, at the border chunks that are
     * loaded.</p>
     */
    private void startEntitySweep() {
    }









    /**
     * Writes back the players who are due, once a second.
     *
     * <p>Every second rather than once an interval, writing only those actually due and a few at a
     * time. Reading a player costs tens of milliseconds, so writing everyone on one tick would be a
     * stall that arrives on a fixed cycle - which looks like the server hitching for no reason.</p>
     */
    private void startPeriodicSave() {
        if (this.config.saveIntervalSeconds() <= 0) {
            getLogger().warning("Periodic saving is off: anything since a player arrived is lost if this "
                + "server is killed rather than stopped");
            return;
        }
        final long dueAfterNanos = TimeUnit.SECONDS.toNanos(this.config.saveIntervalSeconds());
        getLogger().info("Writing players back every " + this.config.saveIntervalSeconds() + "s");
    }



    /**
     * The ground this server owns, for other plugins.
     *
     * <p>For anything that has to put something down somewhere of its own choosing - a rocket picking
     * a clear landing site, say. Searching outwards without consulting this walks over a border and
     * builds on a neighbour's ground, where this server's copy of the world is a year out of date and
     * nobody else can see what was built.</p>
     *
     * <p>A server with no areas owns everywhere as far as this is concerned; it is not a shard.</p>
     */
    public ShardBorder getShardBorder() {
        return this.border;
    }


    /**
     * Asks the proxy to drop whatever locks this server still holds, and to say which areas it owns.
     *
     * <p>Releasing is correct only while nobody is online: anything held under this server's name then
     * was left by the run before it, whereas the same call with players on would drop the locks of the
     * people currently being served. That is why this runs on the first connection after enable - and
     * why the retry below is safe, because a login is refused until this has succeeded.</p>
     */
    private void completeStartupHandshake() {
        this.client.startup(ServerStartupRequest.create(this.config.serverName()))
            .whenComplete(this::logStartupResult);
    }

    private void logStartupResult(final ServerStartupResponse response, final Throwable error) {
        if (error != null) {
            getLogger().log(Level.SEVERE, "Could not complete the startup handshake", error);
            retryStartupHandshake();
            return;
        }
        if (!response.success()) {
            getLogger().severe("Could not complete the startup handshake: " + response.failureMessage());
            retryStartupHandshake();
            return;
        }
        getLogger().info("Released " + response.releasedLockCount() + " stale player data locks for "
            + this.config.serverName());

        // The proxy is the only holder of the shard map, so the areas this server owns arrive with the
        // startup answer rather than being configured a second time here.
        // Set before logins are allowed, not after: the areas are also what says whether this is a
        // shard at all, and a login slipping through the gap between would be read as being on a
        // server that owns nothing - so it would join a real shard with nobody holding its data
        this.border.set(response.regions());
        this.startupComplete = true;
        if (response.regions().isEmpty()) {
            getLogger().info("This server owns no shard areas, so no border is enforced and no player "
                + "data is owned here: whoever is on this server keeps what is on its own disk, and "
                + "the copy the shards share is left alone");
        } else {
            getLogger().info("Enforcing " + response.regions().size() + " shard area(s)");
        }
    }

    /**
     * Asks again, rather than giving up for the lifetime of the server.
     *
     * <p>This used to be a log line and nothing else, which was the worse half of the failure: the
     * areas this server owns arrive with the same answer, so a handshake that never completed left
     * {@link ShardBorder} empty - and an empty border owns everywhere, so the server carried on
     * serving players with no edge enforced and nothing saying so. Refusing logins until this
     * succeeds is what makes both the wait and the repeated release safe.</p>
     */
    private void retryStartupHandshake() {
        getLogger().warning("This server has no shard areas yet, so nobody may join it. Trying again in "
            + (this.startupRetryTicks / 20L) + "s");
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::completeStartupHandshake,
            this.startupRetryTicks);
        this.startupRetryTicks = Math.min(this.startupRetryTicks * 2L, STARTUP_RETRY_MAX_TICKS);
    }
}

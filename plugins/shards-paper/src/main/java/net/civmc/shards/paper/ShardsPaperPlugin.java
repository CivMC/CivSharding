package net.civmc.shards.paper;

import java.util.Optional;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.civmc.shards.api.ServerStartupRequest;
import net.civmc.shards.api.ServerStartupResponse;
import net.civmc.shards.paper.border.ArrivalCue;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.paper.border.BorderNotices;
import net.civmc.shards.paper.border.BorderEntitySweep;
import net.civmc.shards.paper.border.BorderOutlook;
import net.civmc.shards.paper.border.BorderRenderer;
import net.civmc.shards.paper.border.BorderView;
import net.civmc.shards.paper.border.GlassBorderRenderer;
import net.civmc.shards.paper.border.MarkerBorderRenderer;
import net.civmc.shards.paper.border.ParticleBorderRenderer;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.border.ShardBorderListener;
import net.civmc.shards.paper.border.ShardRespawnListener;
import net.civmc.shards.paper.border.TransferService;
import net.civmc.shards.paper.config.ShardsPaperConfig;
import net.civmc.shards.paper.mirror.BorderBandSync;
import net.civmc.shards.paper.mirror.BorderBandUpdates;
import net.civmc.shards.paper.mirror.MirrorMobPublisher;
import net.civmc.shards.paper.mirror.MirrorMobView;
import net.civmc.shards.paper.mirror.MirrorMobs;
import net.civmc.shards.paper.mirror.ChunkRevisions;
import net.civmc.shards.paper.mirror.EntityDataLayout;
import net.civmc.shards.paper.mirror.LearnedEntityDataLayout;
import net.civmc.shards.paper.mirror.ChunkStateProvider;
import net.civmc.shards.paper.mirror.MirrorEntities;
import net.civmc.shards.paper.mirror.MirrorEntityPublisher;
import net.civmc.shards.paper.mirror.MirrorEntityView;
import net.civmc.shards.paper.mirror.MirrorMetrics;
import net.civmc.shards.paper.mirror.MirrorPlayerPublisher;
import net.civmc.shards.paper.mirror.MirrorPlayerView;
import net.civmc.shards.paper.mirror.MirrorPlayers;
import net.civmc.shards.paper.mirror.MirrorRepairListener;
import net.civmc.shards.paper.mirror.MirrorSignPublisher;
import net.civmc.shards.paper.mirror.MirrorStore;
import net.civmc.shards.paper.mirror.MirrorUpdatePublisher;
import net.civmc.shards.paper.mirror.MirrorView;
import net.civmc.shards.paper.mirror.UnownedEntityView;
import net.civmc.shards.paper.mirror.UnownedGroundListener;
import net.civmc.shards.paper.mirror.UnownedTakingsListener;
import net.civmc.shards.paper.playerdata.OwnedPlayers;
import net.civmc.shards.paper.playerdata.PlayerDataListener;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import net.civmc.shards.paper.rabbitmq.ShardsServer;
import net.civmc.shards.paper.sky.SkyListener;
import net.civmc.shards.paper.sky.SkySync;
import net.civmc.shards.paper.snapshot.SnapshotVerifyCommand;
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
    private BorderView view;
    private ShardsClient client;
    private OwnedPlayers owned;
    private TransferService transfers;
    private ShardsServer mirrorServer;
    private MirrorView mirror;
    private BorderEntitySweep entitySweep;
    private EntityDataLayout entityDataLayout = EntityDataLayout.UNKNOWN;
    private MirrorStore mirrorStore;
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
        this.owned = new OwnedPlayers(this.client, getLogger(), this.config.serverName());

        final BorderNotices notices = new BorderNotices();
        final BorderOutlook outlook = new BorderOutlook(this.client, this.config.serverName(), getLogger());
        this.view = new BorderView(this.border, outlook, notices, renderer());
        this.transfers = new TransferService(this, this.client, this.owned, getLogger(),
            this.config.serverName(), this.config.failureMessage(), notices, this.view);


        final ArrivalCue arrivalCue = new ArrivalCue(this.config.arrivalTitle(), this.config.arrivalSubtitle());
        getServer().getPluginManager().registerEvents(
            new PlayerDataListener(this, this.client, this.config.serverName(), this.config.failureMessage(),
                this.owned, this.transfers, () -> this.startupComplete, this.border::isConfigured,
                arrivalCue), this);
        if (!arrivalCue.isConfigured()) {
            getLogger().info("No arrival title configured, so a crossing into this shard is unannounced");
        }
        getServer().getPluginManager().registerEvents(
            new ShardBorderListener(this, this.border, this.transfers, notices, outlook,
                // Read through the field rather than captured: the mirror is built after this, and
                // may never be built at all, in which case the far side is simply not known
                (world, x, y, z) -> this.mirror == null ? null : this.mirror.neighbourBlockAt(world, x, y, z),
                getLogger()), this);
        getServer().getPluginManager().registerEvents(
            new ShardRespawnListener(this, this.border, this.transfers, getLogger()), this);
        startUnownedEntityView();
        // Not inside the method above: that one is switched off by hide-unowned-entities, which is a
        // question about what a border looks like and what can be farmed near it. This is a question
        // about what a player walks away with, and is not the operator's to turn off
        getServer().getPluginManager().registerEvents(new UnownedTakingsListener(this.border), this);
        startEntitySweep();
        startPeriodicSave();
        startBorderView(this.view);
    }

    @Override
    public void onDisable() {
        // Kick first, so every quit handler runs and every save is sent while the plugin is still able
        // to send it. A player still online when the client closes has their data left owned by a
        // server that is about to stop existing
        for (final Player player : Bukkit.getOnlinePlayers()) {
            player.kick(Component.text("Server is shutting down"));
        }
        if (this.owned != null) {
            this.owned.drain(SHUTDOWN_DRAIN_SECONDS, TimeUnit.SECONDS);
        }
        if (this.view != null) {
            // Anything the border put in the world has to come back out of it. A display entity left
            // behind is invisible litter that nothing else will ever clean up
            this.view.close();
        }
        if (this.mirrorServer != null) {
            this.mirrorServer.close();
        }
        // After the mirror has stopped being updated and while the server is still whole. Written here
        // rather than only on the timer so an ordinary stop does not throw away everything since the
        // last one
        if (this.mirrorStore != null && this.mirror != null) {
            this.mirrorStore.save(this.mirror.toSave());
        }
        if (this.client != null) {
            this.client.close();
        }
    }

    /**
     * Whichever way this server has been told to draw its border.
     */
    private BorderRenderer renderer() {
        return switch (this.config.borderStyle()) {
            case PARTICLES -> new ParticleBorderRenderer();
            case GLASS -> new GlassBorderRenderer(this);
            case MARKERS -> new MarkerBorderRenderer(this);
        };
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
        final UnownedEntityView view = new UnownedEntityView(this, this.border);
        getServer().getPluginManager().registerEvents(view, this);
        getServer().getPluginManager().registerEvents(new UnownedGroundListener(this.border), this);
        // Slow, because it only exists to catch entities that wandered out after they were already
        // being shown. Everything arriving is caught by the tracking event, which costs nothing
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                view.sweep(player);
            }
        }, UNOWNED_SWEEP_TICKS, UNOWNED_SWEEP_TICKS);
    }

    /**
     * Keeps what the neighbours have said across a restart, and puts it back.
     *
     * <p>Nothing loaded is trusted: every restored chunk is marked to be read from its owner again the
     * first time anybody looks at it. What it buys is a border that is drawn immediately rather than
     * filling in, and - the reason it is worth having - a neighbour that is down showing the last
     * thing it said instead of this server's own empty copy of its land.</p>
     */
    private void startMirrorStore(final MirrorView mirror) {
        if (!this.config.saveMirror()) {
            getLogger().warning("Not saving the mirror: a restart will read every chunk along every "
                + "border again, and while a neighbour is down its ground will show as this server's "
                + "own untouched copy of it");
            return;
        }
        this.mirrorStore = new MirrorStore(getDataFolder().toPath().resolve("mirror.json.gz"), getLogger());
        mirror.restore(this.mirrorStore.load());
        final MirrorStore store = this.mirrorStore;
        // The copy is taken on the main thread and the writing is not. Serialising tens of thousands of
        // blocks is not something to do between ticks for a picture that is only a head start anyway
        getServer().getScheduler().runTaskTimer(this, () -> {
            final List<MirrorStore.Saved> chunks = mirror.toSave();
            getServer().getScheduler().runTaskAsynchronously(this, () -> store.save(chunks));
        }, MIRROR_SAVE_TICKS, MIRROR_SAVE_TICKS);
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
        final BorderEntitySweep sweep = new BorderEntitySweep(this.border, getLogger());
        this.entitySweep = sweep;
        getServer().getPluginManager().registerEvents(sweep, this);
        getServer().getScheduler().runTaskTimer(this, () -> sweep.sweep(getServer().getWorlds()), 1L, 1L);
    }




    /**
     * Starts drawing the frames and stands a neighbouring shard has.
     *
     * <p>Behind the packet guard like the rest of it, and behind the metadata reader as well: the item
     * in a frame is a numbered field, and the number is read off this server's own entities rather than
     * guessed. Without the reader a frame is still drawn - empty - which is why this does not refuse to
     * start without it.</p>
     */
    private MirrorEntities startEntityMirror() {
        try {
            if (this.entityDataLayout instanceof LearnedEntityDataLayout learned) {
                return new MirrorEntityView(learned);
            }
            return MirrorEntities.NONE;
        } catch (final RuntimeException | LinkageError exception) {
            getLogger().log(Level.SEVERE, "PacketEvents is installed but could not be used, so the "
                + "frames and stands on other shards will not be drawn", exception);
            return MirrorEntities.NONE;
        }
    }

    /**
     * Starts drawing what moves on a neighbouring shard.
     *
     * <p>Its own switch on the packet library, like the frames and the players, and its own quiet
     * fallback: a shard that cannot draw a neighbour's minecarts still draws their buildings, their
     * shops and the people standing in them.</p>
     */
    private MirrorMobs startMobMirror() {
        if (!this.config.mirrorMovingEntities()) {
            getLogger().info("Not drawing what moves on the neighbouring shards, and not telling them "
                + "what moves here: their pens will look empty and their rails unused");
            return MirrorMobs.NONE;
        }
        final Plugin packetEvents = getServer().getPluginManager().getPlugin("packetevents");
        if (packetEvents == null || !packetEvents.isEnabled()) {
            getLogger().info("PacketEvents is not installed, so the minecarts, animals and dropped "
                + "items on other shards will not be drawn. Their buildings still are");
            return MirrorMobs.NONE;
        }
        try {
            final MirrorMobView view = new MirrorMobView(
                this.entityDataLayout instanceof LearnedEntityDataLayout learned ? learned : null);
            getServer().getPluginManager().registerEvents(view, this);
            return view;
        } catch (final RuntimeException | LinkageError exception) {
            getLogger().log(Level.SEVERE, "PacketEvents is installed but could not be used, so what "
                + "moves on other shards will not be drawn. Everything else about the mirror is "
                + "unaffected", exception);
            return MirrorMobs.NONE;
        }
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
        getServer().getScheduler().runTaskTimer(this, () -> this.owned.checkpointDue(dueAfterNanos, MAX_SAVES_PER_RUN),
            SAVE_CHECK_TICKS, SAVE_CHECK_TICKS);
        getLogger().info("Writing players back every " + this.config.saveIntervalSeconds() + "s");
    }

    /**
     * Keeps what players can see of the border up to date.
     *
     * <p>On a timer rather than on movement, because a border can change while somebody stands still:
     * the shard beyond it going down turns a doorway into a wall without the player taking a step.
     * The particles need repainting as they expire anyway.</p>
     */
    private void startBorderView(final BorderView view) {
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                view.update(player);
            }
        }, BORDER_VIEW_TICKS, BORDER_VIEW_TICKS);
    }

    /**
     * Moving a player to another shard, for other plugins.
     *
     * <p>Every reason for leaving a shard goes through the same thing - walking over a border, a
     * rocket landing, an arrival - so that writing a player back, giving up ownership and moving them
     * has one implementation rather than one per reason.</p>
     *
     * <p>State travels exactly as it is. A caller that wants a player to arrive without something has
     * to take it off them first; that is a rule of whatever is moving them, not of the transfer.</p>
     *
     * @return empty until this plugin has enabled
     */
    public Optional<TransferService> getTransfers() {
        return Optional.ofNullable(this.transfers);
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
        // Only now is there a border to measure a chunk against, so every chunk loaded up to this
        // point had a load event that could say nothing about it - the spawn chunks among them. On the
        // main thread because that is the only place a world's loaded chunks can be asked for, and
        // this answer arrives on a broker thread
        if (this.entitySweep != null) {
            getServer().getScheduler().runTask(this,
                () -> this.entitySweep.watchWhatIsAlreadyLoaded(getServer().getWorlds()));
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

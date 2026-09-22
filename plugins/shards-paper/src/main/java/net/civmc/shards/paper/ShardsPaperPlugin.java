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
import net.civmc.shards.paper.mirror.UnownedEntityView;
import net.civmc.shards.paper.mirror.UnownedGroundListener;
import net.civmc.shards.paper.mirror.UnownedTakingsListener;
import net.civmc.shards.paper.playerdata.OwnedPlayers;
import net.civmc.shards.paper.playerdata.PlayerDataListener;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
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
    private BorderEntitySweep entitySweep;
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
            new ShardRespawnListener(this, this.border, this.transfers, getLogger()), this);
        startUnownedEntityView();
        // Not inside the method above: that one is switched off by hide-unowned-entities, which is a
        // question about what a border looks like and what can be farmed near it. This is a question
        // about what a player walks away with, and is not the operator's to turn off
        getServer().getPluginManager().registerEvents(new UnownedTakingsListener(this.border), this);
        startEntitySweep();
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












}
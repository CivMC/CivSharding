package net.civmc.shards.velocity;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.civmc.shards.api.ShardServerId;
import net.civmc.shards.velocity.config.ShardsConfig;
import com.velocitypowered.api.command.CommandManager;
import net.civmc.shards.velocity.placement.ShardConnectionListener;
import net.civmc.shards.velocity.presence.NetworkListCommand;
import net.civmc.shards.velocity.presence.NetworkTabList;
import net.civmc.shards.velocity.placement.ShardPlacementService;
import net.civmc.shards.velocity.playerdata.InFlightTransfers;
import net.civmc.shards.velocity.playerdata.PlayerDataService;
import net.civmc.shards.velocity.playerdata.ShardLockExpiry;
import net.civmc.shards.velocity.rabbitmq.PlayerCheckpointHandler;
import net.civmc.shards.velocity.rabbitmq.BorderProbeHandler;
import net.civmc.shards.velocity.rabbitmq.PlayerClaimHandler;
import net.civmc.shards.velocity.rabbitmq.PlayerReleaseHandler;
import net.civmc.shards.velocity.rabbitmq.PlayerSaveHandler;
import net.civmc.shards.velocity.rabbitmq.PlayerTransferHandler;
import net.civmc.shards.velocity.rabbitmq.ServerStartupHandler;
import net.civmc.shards.velocity.rabbitmq.NightSkipHandler;
import net.civmc.shards.velocity.rabbitmq.ShardsRequestConsumer;
import net.civmc.shards.velocity.rabbitmq.SkyStateHandler;
import net.civmc.shards.velocity.sky.SkyService;
import org.slf4j.Logger;

@Plugin(id = "shards", name = "Shards", version = "1.0.0", authors = {"Fier"})
public final class ShardsVelocityPlugin {

    // Slow on purpose: it costs a pass over every pair of players, and nothing it fixes is urgent -
    // the events do the urgent half
    private static final long TAB_LIST_SYNC_SECONDS = 10L;

    // Rows for parcels that have already been landed, kept a while so a launch that went wrong is
    // still there to be looked at. Only ever landed ones are forgotten - see CargoService#pruneLanded
    private static final Duration CARGO_RETENTION = Duration.ofDays(7L);
    private static final long CARGO_PRUNE_HOURS = 6L;

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private final Injector injector;
    private ShardPlacementService shardPlacementService;
    private PlayerDataService playerDataService;
    private ShardsRequestConsumer requestConsumer;

    @Inject
    public ShardsVelocityPlugin(final ProxyServer proxyServer, final Logger logger,
                                @DataDirectory final Path dataDirectory, final Injector injector) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.injector = injector;
    }

    @Subscribe
    public void onProxyInitialization(final ProxyInitializeEvent event) {
        final ShardsConfig shardsConfig = ShardsConfig.load(this.dataDirectory);
        logServerIds(shardsConfig);

        // Child of Velocity's injector for this plugin, which already provides ProxyServer, PluginContainer, Logger
        final Injector shardsInjector = this.injector.createChildInjector(new ShardsModule(shardsConfig));

        this.proxyServer.getEventManager().register(this, shardsInjector.getInstance(ShardConnectionListener.class));

        this.shardPlacementService = shardsInjector.getInstance(ShardPlacementService.class);
        this.playerDataService = shardsInjector.getInstance(PlayerDataService.class);

        // One clock and one weather for the network, so a crossing does not take a player from noon
        // into a thunderstorm while the ground stays continuous
        final SkyService skyService = SkyService.fromWallClock();

        // Shared by the two handlers that between them make a crossing tellable from a login: the
        // transfer writes the record and the claim that follows reads it
        final InFlightTransfers inFlightTransfers = new InFlightTransfers();
        if (!this.requestConsumer.start()) {
            this.logger.warn("Shards could not start its request consumer; no server can reach its player data");
        }
    }

    /**
     * The id a server owns player data under is derived from its name rather than configured, so an
     * operator reading owning_server_uuid out of the database has nothing on hand to map it back to a
     * server. Writing the mapping out once at startup gives them that.
     */
    private void logServerIds(final ShardsConfig shardsConfig) {
    }

    /**
     * Shard lookups for other plugins. Empty until this plugin has handled ProxyInitializeEvent.
     */
    public Optional<ShardPlacementService> getPlacement() {
        return Optional.ofNullable(this.shardPlacementService);
    }

    /**
     * Single-owner player data access for other plugins. Empty until this plugin has handled
     * ProxyInitializeEvent.
     */
    public Optional<PlayerDataService> getPlayerData() {
        return Optional.ofNullable(this.playerDataService);
    }
}

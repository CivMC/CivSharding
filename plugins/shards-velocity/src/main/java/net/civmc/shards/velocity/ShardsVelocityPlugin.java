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
import net.civmc.shards.velocity.cargo.CargoService;
import net.civmc.shards.velocity.rabbitmq.CargoFetchHandler;
import net.civmc.shards.velocity.rabbitmq.CargoLandedHandler;
import net.civmc.shards.velocity.rabbitmq.CargoReserveHandler;
import net.civmc.shards.velocity.rabbitmq.CargoSendHandler;
import net.civmc.shards.velocity.rabbitmq.CargoStatusHandler;
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
        final CargoService cargoService = shardsInjector.getInstance(CargoService.class);

        // One clock and one weather for the network, so a crossing does not take a player from noon
        // into a thunderstorm while the ground stays continuous
        final SkyService skyService = SkyService.fromWallClock();

        // Shared by the two handlers that between them make a crossing tellable from a login: the
        // transfer writes the record and the claim that follows reads it
        final InFlightTransfers inFlightTransfers = new InFlightTransfers();
        this.requestConsumer = new ShardsRequestConsumer(shardsConfig.rabbitmq().connectionFactory(),
            List.of(
                new ServerStartupHandler(this.playerDataService, this.shardPlacementService, this.logger),
                new PlayerClaimHandler(this.playerDataService, this.shardPlacementService, inFlightTransfers,
                    this.logger),
                new PlayerSaveHandler(this.playerDataService, this.logger),
                new PlayerCheckpointHandler(this.playerDataService, this.logger),
                new PlayerReleaseHandler(this.playerDataService, this.logger),
                new PlayerTransferHandler(this.playerDataService, this.shardPlacementService,
                    inFlightTransfers, this.proxyServer, this.logger),
                new CargoSendHandler(cargoService, this.shardPlacementService, this.proxyServer, this.logger),
                new CargoFetchHandler(cargoService, this.logger),
                new CargoReserveHandler(cargoService, this.logger),
                new CargoLandedHandler(cargoService, this.logger),
                new CargoStatusHandler(cargoService, this.logger),
                new BorderProbeHandler(this.shardPlacementService, this.proxyServer, this.logger),
                new SkyStateHandler(skyService),
                new NightSkipHandler(skyService, this.logger)),
            this.proxyServer, this, this.logger);
        if (!this.requestConsumer.start()) {
            this.logger.warn("Shards could not start its request consumer; no server can reach its player data");
        }
        startLockExpiry(shardsConfig);
        startCargoPruning(cargoService);
        registerNetworkList(shardsConfig);
        startNetworkTabList(shardsConfig);
    }

    /**
     * Forgets the rows of parcels that were landed long enough ago to be of no further interest.
     *
     * <p>Slow, and deliberately not a startup job: nothing depends on it having run, and a parcel
     * still waiting is never touched by it however old it is.</p>
     */
    private void startCargoPruning(final CargoService cargoService) {
        this.proxyServer.getScheduler().buildTask(this, () -> {
            try {
                final int pruned = cargoService.pruneLanded(CARGO_RETENTION);
                if (pruned > 0) {
                    this.logger.info("Forgot {} landed cargo rows", pruned);
                }
            } catch (final RuntimeException exception) {
                this.logger.warn("Could not prune landed cargo", exception);
            }
        }).delay(CARGO_PRUNE_HOURS, TimeUnit.HOURS).repeat(CARGO_PRUNE_HOURS, TimeUnit.HOURS).schedule();
    }

    /**
     * Shows the whole network in everybody's tab list, not just the shard they are standing on.
     *
     * <p>Kept up to date by events and by a slow sweep together: the events put an arrival or a
     * departure right at once, and the sweep refreshes the pings - which would otherwise be whatever
     * they were at the moment somebody connected - and quietly repairs anything an event missed.</p>
     */
    private void startNetworkTabList(final ShardsConfig shardsConfig) {
        if (!shardsConfig.networkTabList()) {
            return;
        }
        final NetworkTabList tabList = new NetworkTabList(this.proxyServer);
        this.proxyServer.getEventManager().register(this, tabList);
        this.proxyServer.getScheduler().buildTask(this, tabList::sync)
            .repeat(TAB_LIST_SYNC_SECONDS, TimeUnit.SECONDS)
            .schedule();
    }

    /**
     * Takes {@code /list} off the shards and answers it here.
     *
     * <p>A shard's own list knows only the people on it, so a network spread evenly across shards
     * reads as several half-empty servers - the opposite of what the borders are for. The proxy
     * already knows every player and where each one is, so this needs nothing asked and nothing kept
     * in sync.</p>
     */
    private void registerNetworkList(final ShardsConfig shardsConfig) {
        if (!shardsConfig.networkList()) {
            return;
        }
        final CommandManager commandManager = this.proxyServer.getCommandManager();
        commandManager.register(commandManager.metaBuilder("list").plugin(this).build(),
            new NetworkListCommand(this.proxyServer, dataOwningServers(shardsConfig)));
    }

    /**
     * Watches for a shard that has died still holding its players.
     *
     * <p>On the proxy rather than in the shards, because the shard this is about is the one that is
     * not running. It is also the only side that can tell "not answering" from "gone": it pings them
     * and it knows who is connected to each.</p>
     */
    private void startLockExpiry(final ShardsConfig shardsConfig) {
        if (shardsConfig.lockExpirySeconds() <= 0) {
            this.logger.warn("Lock expiry is off: a shard that dies keeps its players unclaimable until it "
                + "starts again");
            return;
        }
        final ShardLockExpiry expiry = new ShardLockExpiry(this.proxyServer, this.playerDataService,
            dataOwningServers(shardsConfig), TimeUnit.SECONDS.toMillis(shardsConfig.lockExpirySeconds()),
            this.logger);
        // Checked several times within the window rather than once at the end of it, so the moment a
        // shard is declared dead does not depend on where its death fell in the cycle
        final long checkSeconds = Math.max(5L, shardsConfig.lockExpirySeconds() / 4L);
        this.proxyServer.getScheduler().buildTask(this, expiry::check)
            .repeat(checkSeconds, TimeUnit.SECONDS)
            .schedule();
        this.logger.info("Dropping the locks of a shard that has not answered for {}s",
            shardsConfig.lockExpirySeconds());
    }

    /**
     * Every server that owns player data under a name of its own, which is exactly the shards.
     *
     * <p>The holding server used to be counted here as well, on the reading that it held a player
     * while they stood on it. It does not: a server outside the shard map is refused a lock, so it
     * can never be holding one for the expiry to drop or for an operator to trace. Listing it would
     * be pinging a server about locks it is not allowed to take.</p>
     *
     * <p>Named for what it means rather than kept as a synonym for the shard names, because the two
     * are the same set only for as long as owning ground and owning players stay the same thing.</p>
     */
    private static List<String> dataOwningServers(final ShardsConfig shardsConfig) {
        return List.copyOf(new LinkedHashSet<>(shardsConfig.shards().keySet()));
    }

    @Subscribe
    public void onProxyShutdown(final ProxyShutdownEvent event) {
        // The database pool closes itself through the hook DatabaseModule registers when it opens it
        if (this.requestConsumer != null) {
            this.requestConsumer.close();
        }
    }

    /**
     * The id a server owns player data under is derived from its name rather than configured, so an
     * operator reading owning_server_uuid out of the database has nothing on hand to map it back to a
     * server. Writing the mapping out once at startup gives them that.
     */
    private void logServerIds(final ShardsConfig shardsConfig) {
        for (final String serverName : dataOwningServers(shardsConfig)) {
            this.logger.info("Server {} owns player data as {}", serverName, ShardServerId.of(serverName));
        }
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

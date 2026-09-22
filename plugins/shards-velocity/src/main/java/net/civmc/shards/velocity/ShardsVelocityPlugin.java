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
import net.civmc.shards.velocity.placement.ShardPlacementService;
import net.civmc.shards.velocity.rabbitmq.ShardsRequestConsumer;
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
    private ShardsRequestConsumer requestConsumer;

    @Inject
    public ShardsVelocityPlugin(final ProxyServer proxyServer, final Logger logger,
                                @DataDirectory final Path dataDirectory, final Injector injector) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.injector = injector;
    }





}
package org.icbca.gateway;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.icbca.gateway.admin.AdminSessionStore;
import org.icbca.gateway.admin.AdminUsageQuery;
import org.icbca.gateway.auth.ApiKeyStore;
import org.icbca.gateway.auth.SqliteApiKeyStore;
import org.icbca.gateway.config.GatewayConfig;
import org.icbca.gateway.db.SqliteDatabase;
import org.icbca.gateway.handler.AdminApiHandler;
import org.icbca.gateway.handler.CorsHandler;
import org.icbca.gateway.handler.GatewayInboundHandler;
import org.icbca.gateway.handler.PathWhitelistHandler;
import org.icbca.gateway.handler.StaticAdminHandler;
import org.icbca.gateway.handler.UsageQueryHandler;
import org.icbca.gateway.inspect.InspectorPipeline;
import org.icbca.gateway.proxy.UpstreamClient;
import org.icbca.gateway.usage.LatencyRecorder;
import org.icbca.gateway.usage.LatencyRecorder;
import org.icbca.gateway.usage.NoopLatencyRecorder;
import org.icbca.gateway.usage.UsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty HTTP server for the vLLM gateway.
 */
public final class GatewayServer {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    private final GatewayConfig config;
    private final InspectorPipeline inspectorPipeline;
    private final ApiKeyStore apiKeyStore;
    private final UsageRecorder usageRecorder;
    private final LatencyRecorder latencyRecorder;
    private final SqliteDatabase sqliteDb;
    private final AdminSessionStore adminSessions;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public GatewayServer(GatewayConfig config, InspectorPipeline inspectorPipeline,
                         ApiKeyStore apiKeyStore, UsageRecorder usageRecorder,
                         LatencyRecorder latencyRecorder, SqliteDatabase sqliteDb,
                         AdminSessionStore adminSessions) {
        this.config = config;
        this.inspectorPipeline = inspectorPipeline;
        this.apiKeyStore = apiKeyStore;
        this.usageRecorder = usageRecorder;
        this.latencyRecorder = latencyRecorder != null ? latencyRecorder : NoopLatencyRecorder.INSTANCE;
        this.sqliteDb = sqliteDb;
        this.adminSessions = adminSessions != null ? adminSessions : new AdminSessionStore();
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        final UpstreamClient upstreamClient = new UpstreamClient(config, usageRecorder, latencyRecorder);

        final SqliteApiKeyStore sqliteKeys = apiKeyStore instanceof SqliteApiKeyStore
                ? (SqliteApiKeyStore) apiKeyStore : null;
        final AdminUsageQuery usageQuery = sqliteDb != null ? new AdminUsageQuery(sqliteDb) : null;

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(config.getMaxContentLength()))
                                .addLast(new CorsHandler())
                                .addLast(new AdminApiHandler(config, adminSessions, sqliteKeys, usageQuery))
                                .addLast(new StaticAdminHandler())
                                .addLast(new PathWhitelistHandler(config))
                                .addLast(new UsageQueryHandler(apiKeyStore, usageRecorder))
                                .addLast(new GatewayInboundHandler(upstreamClient, inspectorPipeline));
                    }
                });

        serverChannel = bootstrap.bind(config.getPort()).sync().channel();
        log.info("Gateway listening on port {}, upstream {}:{}, whitelist={}, authRequired={}, admin=/admin/",
                config.getPort(), config.getVllmHost(), config.getVllmPort(),
                config.getPathWhitelist(), apiKeyStore.isAuthRequired());
    }

    public void awaitTermination() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.closeFuture().sync();
        }
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}

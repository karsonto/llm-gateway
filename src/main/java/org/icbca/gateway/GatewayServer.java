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
import org.icbca.gateway.config.GatewayConfig;
import org.icbca.gateway.handler.GatewayInboundHandler;
import org.icbca.gateway.handler.PathWhitelistHandler;
import org.icbca.gateway.inspect.InspectorPipeline;
import org.icbca.gateway.proxy.UpstreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty HTTP server for the vLLM gateway.
 */
public final class GatewayServer {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    private final GatewayConfig config;
    private final InspectorPipeline inspectorPipeline;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public GatewayServer(GatewayConfig config, InspectorPipeline inspectorPipeline) {
        this.config = config;
        this.inspectorPipeline = inspectorPipeline;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        final UpstreamClient upstreamClient = new UpstreamClient(config);

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
                                .addLast(new PathWhitelistHandler(config))
                                .addLast(new GatewayInboundHandler(upstreamClient, inspectorPipeline));
                    }
                });

        serverChannel = bootstrap.bind(config.getPort()).sync().channel();
        log.info("Gateway listening on port {}, upstream {}:{}, whitelist={}",
                config.getPort(), config.getVllmHost(), config.getVllmPort(), config.getPathWhitelist());
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

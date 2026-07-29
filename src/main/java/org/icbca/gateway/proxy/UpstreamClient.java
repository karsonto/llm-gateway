package org.icbca.gateway.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import org.icbca.gateway.config.GatewayConfig;
import org.icbca.gateway.handler.UpstreamHandler;

/**
 * Opens a per-request short-lived connection to vLLM.
 */
public final class UpstreamClient {

    private final GatewayConfig config;

    public UpstreamClient(GatewayConfig config) {
        this.config = config;
    }

    public ChannelFuture connect(ChannelHandlerContext inboundCtx, String requestId, boolean expectStream) {
        final Channel inbound = inboundCtx.channel();
        EventLoopGroup group = inbound.eventLoop();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpClientCodec())
                                .addLast(new UpstreamHandler(inbound, requestId, expectStream));
                    }
                });
        ChannelFuture future = bootstrap.connect(config.getVllmHost(), config.getVllmPort());
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture f) {
                if (!f.isSuccess()) {
                    // caller handles failure; ensure inbound still open for error response
                }
            }
        });
        return future;
    }

    public String getVllmHostHeader() {
        return config.getVllmHost() + ":" + config.getVllmPort();
    }
}

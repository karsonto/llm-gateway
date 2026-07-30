package org.icbca.gateway.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.icbca.gateway.auth.ApiKeyStore;
import org.icbca.gateway.auth.AuthInspector;
import org.icbca.gateway.inspect.ChatRequestContext;
import org.icbca.gateway.inspect.InspectionResult;
import org.icbca.gateway.inspect.InspectorPipeline;
import org.icbca.gateway.parse.RequestBodyParser;
import org.icbca.gateway.parse.RequestBodyRewriter;
import org.icbca.gateway.proxy.UpstreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the request, runs the inspector chain, then proxies to vLLM.
 * <p>
 * Note for future remote guardrails: keep MVP inspectors local/non-blocking.
 * Heavy / remote checks must offload off the EventLoop and resume forwarding asynchronously.
 */
public final class GatewayInboundHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(GatewayInboundHandler.class);

    private final UpstreamClient upstreamClient;
    private final InspectorPipeline inspectorPipeline;

    public GatewayInboundHandler(UpstreamClient upstreamClient, InspectorPipeline inspectorPipeline) {
        this.upstreamClient = upstreamClient;
        this.inspectorPipeline = inspectorPipeline;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = PathWhitelistHandler.extractPath(request.uri());
        ChatRequestContext chatCtx = RequestBodyParser.parse(request, path);

        InspectionResult result = inspectorPipeline.inspect(chatCtx);
        if (!result.isAllowed()) {
            log.info("requestId={} denied by inspector: {} {}",
                    chatCtx.getRequestId(), result.getCode(), result.getMessage());
            writeJsonError(ctx, HttpResponseStatus.valueOf(result.getHttpStatus()),
                    result.getCode(), result.getMessage());
            return;
        }

        final FullHttpRequest retained = request.retainedDuplicate();
        final boolean expectStream = chatCtx.isStream();
        final String requestId = chatCtx.getRequestId();
        final String apiKey = stringAttr(chatCtx, AuthInspector.ATTR_API_KEY, ApiKeyStore.ANONYMOUS_KEY);
        final String apiKeyName = stringAttr(chatCtx, AuthInspector.ATTR_API_KEY_NAME, apiKey);
        final String model = chatCtx.getModel();

        ChannelFuture connectFuture = upstreamClient.connect(
                ctx, requestId, expectStream, apiKey, apiKeyName, model);
        connectFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (!future.isSuccess()) {
                    log.warn("requestId={} upstream connect failed: {}",
                            requestId, future.cause() != null ? future.cause().getMessage() : "unknown");
                    ReferenceCountUtil.release(retained);
                    if (ctx.channel().isActive()) {
                        writeJsonError(ctx, HttpResponseStatus.BAD_GATEWAY,
                                "upstream_unavailable", "Failed to connect to vLLM");
                    }
                    return;
                }
                Channel upstream = future.channel();
                bindLifecycle(ctx.channel(), upstream);

                FullHttpRequest outbound = buildOutboundRequest(retained, upstreamClient.getVllmHostHeader());
                ReferenceCountUtil.release(retained);
                upstream.writeAndFlush(outbound).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture writeFuture) {
                        if (!writeFuture.isSuccess()) {
                            log.warn("requestId={} upstream write failed: {}",
                                    requestId,
                                    writeFuture.cause() != null ? writeFuture.cause().getMessage() : "unknown");
                            closeQuietly(ctx.channel());
                            closeQuietly(upstream);
                        }
                    }
                });
            }
        });
    }

    private static String stringAttr(ChatRequestContext ctx, String attr, String defaultValue) {
        Object v = ctx.getAttributes().get(attr);
        if (v == null) {
            return defaultValue;
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? defaultValue : s;
    }

    private static FullHttpRequest buildOutboundRequest(FullHttpRequest inbound, String hostHeader) {
        ByteBuf original = inbound.content();
        ByteBuf rewritten = RequestBodyRewriter.ensureStreamUsage(original);
        ByteBuf content = rewritten != null ? rewritten : original.retainedDuplicate();

        DefaultFullHttpRequest outbound = new DefaultFullHttpRequest(
                inbound.protocolVersion(),
                inbound.method() == null ? HttpMethod.GET : inbound.method(),
                inbound.uri(),
                content);
        HttpHeaders headers = outbound.headers();
        headers.set(inbound.headers());
        headers.set(HttpHeaderNames.HOST, hostHeader);
        headers.remove(HttpHeaderNames.ACCEPT_ENCODING);
        headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        HttpUtil.setContentLength(outbound, content.readableBytes());
        return outbound;
    }

    private static void bindLifecycle(final Channel inbound, final Channel upstream) {
        inbound.closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                closeQuietly(upstream);
            }
        });
        upstream.closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                closeQuietly(inbound);
            }
        });
    }

    private static void writeJsonError(ChannelHandlerContext ctx, HttpResponseStatus status,
                                       String code, String message) {
        String body = "{\"error\":{\"code\":\"" + escapeJson(code == null ? "denied" : code)
                + "\",\"message\":\"" + escapeJson(message == null ? "" : message) + "\"}}";
        ByteBuf content = Unpooled.copiedBuffer(body, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        HttpUtil.setContentLength(response, content.readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void closeQuietly(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("inbound error: {}", cause.getMessage());
        ctx.close();
    }
}

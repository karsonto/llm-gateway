package org.icbca.gateway.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.icbca.gateway.parse.SseTokenParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relays upstream (vLLM) responses to the client and feeds content to {@link SseTokenParser}.
 * Does not aggregate upstream responses so SSE stays streaming.
 */
public final class UpstreamHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(UpstreamHandler.class);

    private final Channel inbound;
    private final String requestId;
    private final boolean expectStream;
    private final SseTokenParser sseParser;
    private final ByteBuf nonStreamBuffer;
    private Channel upstream;
    private boolean eventStream;
    private boolean closed;
    private boolean completed;

    public UpstreamHandler(Channel inbound, String requestId, boolean expectStream) {
        this.inbound = inbound;
        this.requestId = requestId;
        this.expectStream = expectStream;
        this.sseParser = new SseTokenParser(requestId);
        this.nonStreamBuffer = Unpooled.buffer();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.upstream = ctx.channel();
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof HttpObject)) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (!inbound.isActive()) {
            ReferenceCountUtil.release(msg);
            closeQuietly(ctx.channel());
            return;
        }

        if (msg instanceof HttpResponse) {
            HttpResponse upstreamResp = (HttpResponse) msg;
            String contentType = upstreamResp.headers().get(HttpHeaderNames.CONTENT_TYPE);
            eventStream = contentType != null && contentType.toLowerCase().contains("text/event-stream");

            DefaultHttpResponse clientResp = new DefaultHttpResponse(
                    upstreamResp.protocolVersion(), upstreamResp.status());
            clientResp.headers().set(upstreamResp.headers());
            HttpUtil.setKeepAlive(clientResp, false);

            writeToInbound(clientResp, false);
            return;
        }

        if (msg instanceof HttpContent) {
            HttpContent content = (HttpContent) msg;
            ByteBuf buf = content.content();
            boolean last = content instanceof LastHttpContent;

            if (eventStream || expectStream) {
                if (buf.isReadable()) {
                    sseParser.feedUtf8(buf);
                }
            } else if (buf.isReadable()) {
                nonStreamBuffer.writeBytes(buf, buf.readerIndex(), buf.readableBytes());
            }

            Object outbound;
            if (buf.isReadable()) {
                ByteBuf copy = buf.retainedDuplicate();
                outbound = last ? new DefaultLastHttpContent(copy) : new DefaultHttpContent(copy);
            } else if (last) {
                outbound = LastHttpContent.EMPTY_LAST_CONTENT.retain();
            } else {
                ReferenceCountUtil.release(msg);
                return;
            }

            if (last && !(eventStream || expectStream)) {
                sseParser.finishNonStream(nonStreamBuffer);
            }
            writeToInbound(outbound, last);
            ReferenceCountUtil.release(msg);
        }
    }

    private void writeToInbound(final Object msg, final boolean last) {
        if (!inbound.isActive()) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (inbound.eventLoop().inEventLoop()) {
            inbound.writeAndFlush(msg).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                    if (!future.isSuccess()) {
                        log.warn("requestId={} write to client failed: {}",
                                requestId, future.cause() != null ? future.cause().getMessage() : "unknown");
                        finishAndClose();
                        return;
                    }
                    if (last) {
                        finishAndClose();
                    }
                }
            });
        } else {
            inbound.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    writeToInbound(msg, last);
                }
            });
        }
    }

    private void finishAndClose() {
        if (!completed) {
            completed = true;
            sseParser.onComplete();
        }
        closeQuietly(upstream != null ? upstream : null);
        closeQuietly(inbound);
        cleanup();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!completed) {
            completed = true;
            sseParser.onComplete();
        }
        cleanup();
        closeQuietly(inbound);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("requestId={} upstream error: {}", requestId, cause.getMessage());
        finishAndClose();
    }

    private void cleanup() {
        if (closed) {
            return;
        }
        closed = true;
        sseParser.release();
        if (nonStreamBuffer.refCnt() > 0) {
            nonStreamBuffer.release();
        }
    }

    private static void closeQuietly(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.close();
        }
    }
}

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
import org.icbca.gateway.parse.TokenTimingStats;
import org.icbca.gateway.proxy.UpstreamAttributes;
import org.icbca.gateway.usage.LatencyRecorder;
import org.icbca.gateway.usage.LatencySample;
import org.icbca.gateway.usage.TokenUsage;
import org.icbca.gateway.usage.UsageRecorder;
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
    private final String apiKey;
    private final String apiKeyName;
    private final String model;
    private final UsageRecorder usageRecorder;
    private final LatencyRecorder latencyRecorder;
    private final SseTokenParser sseParser;
    private final ByteBuf nonStreamBuffer;
    private Channel upstream;
    private boolean eventStream;
    private boolean closed;
    private boolean completed;
    private boolean usageRecorded;
    private boolean latencyRecorded;
    private long ttftMs = -1L;

    public UpstreamHandler(Channel inbound, String requestId, boolean expectStream,
                           String apiKey, String apiKeyName, String model,
                           UsageRecorder usageRecorder, LatencyRecorder latencyRecorder) {
        this.inbound = inbound;
        this.requestId = requestId;
        this.expectStream = expectStream;
        this.apiKey = apiKey;
        this.apiKeyName = apiKeyName;
        this.model = model;
        this.usageRecorder = usageRecorder;
        this.latencyRecorder = latencyRecorder;
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

            markTtftIfNeeded();

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

            if (buf.isReadable()) {
                markTtftIfNeeded();
            }

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
            recordLatencyOnce();
            recordUsageOnce();
        }
        closeQuietly(upstream != null ? upstream : null);
        closeQuietly(inbound);
        cleanup();
    }

    private void recordUsageOnce() {
        if (usageRecorded || usageRecorder == null) {
            return;
        }
        usageRecorded = true;
        TokenUsage usage = sseParser.getUsage();
        if (usage == null) {
            log.warn("requestId={} no usage from upstream (apiKey={}, model={}, stream={})",
                    requestId, apiKey, model, expectStream || eventStream);
        }
        usageRecorder.record(apiKey, apiKeyName, model, usage);
    }

    private void markTtftIfNeeded() {
        if (ttftMs >= 0) {
            return;
        }
        Long startNanos = upstream != null
                ? upstream.attr(UpstreamAttributes.REQUEST_START_NANOS).get() : null;
        if (startNanos == null) {
            return;
        }
        ttftMs = Math.max(0L, (System.nanoTime() - startNanos.longValue()) / 1_000_000L);
    }

    private void recordLatencyOnce() {
        if (latencyRecorded || latencyRecorder == null) {
            return;
        }
        Long startNanos = upstream != null
                ? upstream.attr(UpstreamAttributes.REQUEST_START_NANOS).get() : null;
        if (startNanos == null) {
            return;
        }
        latencyRecorded = true;
        long latencyMs = Math.max(0L, (System.nanoTime() - startNanos.longValue()) / 1_000_000L);

        TokenTimingStats timing = sseParser.snapshotTiming();
        TokenUsage usage = sseParser.getUsage();
        long promptTokens = usage == null ? 0L : usage.getPromptTokens();
        long completionTokens = usage == null ? 0L : usage.getCompletionTokens();

        long ttftStrictMs = -1L;
        if (timing.hasFirstToken()) {
            ttftStrictMs = Math.max(0L,
                    (timing.getFirstTokenNanos() - startNanos.longValue()) / 1_000_000L);
        } else if (ttftMs >= 0) {
            ttftStrictMs = ttftMs;
        }

        long tpotMs = -1L;
        long outputTpsMilli = -1L;
        if (timing.hasFirstToken()
                && timing.getLastTokenNanos() >= timing.getFirstTokenNanos()
                && completionTokens > 1) {
            long genMs = Math.max(1L,
                    (timing.getLastTokenNanos() - timing.getFirstTokenNanos()) / 1_000_000L);
            tpotMs = genMs / Math.max(1L, completionTokens - 1);
            double tps = (completionTokens * 1000.0) / (double) genMs;
            outputTpsMilli = Math.max(0L, Math.round(tps * 1000.0));
        } else if (timing.hasFirstToken()
                && timing.getLastTokenNanos() > timing.getFirstTokenNanos()
                && completionTokens == 1) {
            outputTpsMilli = 0L;
        }

        long itlMs = -1L;
        if (timing.getIntervalCount() > 0) {
            itlMs = Math.max(0L,
                    (timing.getIntervalSumNanos() / timing.getIntervalCount()) / 1_000_000L);
        }

        latencyRecorder.record(model, new LatencySample(
                latencyMs, ttftStrictMs, tpotMs, itlMs, outputTpsMilli,
                promptTokens, completionTokens));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!completed) {
            completed = true;
            sseParser.onComplete();
            recordLatencyOnce();
            recordUsageOnce();
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

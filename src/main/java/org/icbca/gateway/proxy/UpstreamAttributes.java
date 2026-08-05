package org.icbca.gateway.proxy;

import io.netty.util.AttributeKey;

/**
 * Channel attributes for upstream proxy timing.
 */
public final class UpstreamAttributes {

    public static final AttributeKey<Long> REQUEST_START_NANOS =
            AttributeKey.valueOf("requestStartNanos");

    private UpstreamAttributes() {
    }
}

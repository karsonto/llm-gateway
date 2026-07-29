package org.icbca.gateway.inspect;

/**
 * Pluggable check run before forwarding to vLLM.
 * MVP inspectors must stay non-blocking on the EventLoop.
 * Remote / heavy guardrails should offload to a business thread pool and resume via async relay
 * (not covered by this synchronous SPI in MVP).
 */
public interface ChatRequestInspector {

    InspectionResult inspect(ChatRequestContext ctx);
}

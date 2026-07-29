package org.icbca.gateway;

import org.icbca.gateway.config.GatewayConfig;
import org.icbca.gateway.inspect.ChatRequestInspector;
import org.icbca.gateway.inspect.InspectorPipeline;
import org.icbca.gateway.inspect.LoggingInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point: load config, register default inspectors, start Netty gateway.
 * To add guardrails later: implement ChatRequestInspector and append to the list below.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        GatewayConfig config = GatewayConfig.load();
        log.info("Loaded {}", config);

        List<ChatRequestInspector> inspectors = new ArrayList<ChatRequestInspector>();
        inspectors.add(new LoggingInspector());
        // Future guardrails: inspectors.add(new XxxGuardrailInspector(...));
        InspectorPipeline pipeline = new InspectorPipeline(inspectors);

        final GatewayServer server = new GatewayServer(config, pipeline);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                log.info("Shutting down gateway...");
                server.stop();
            }
        }));

        server.start();
        server.awaitTermination();
    }
}

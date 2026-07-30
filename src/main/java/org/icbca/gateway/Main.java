package org.icbca.gateway;

import org.icbca.gateway.auth.ApiKeyStore;
import org.icbca.gateway.auth.AuthInspector;
import org.icbca.gateway.auth.InMemoryApiKeyStore;
import org.icbca.gateway.config.GatewayConfig;
import org.icbca.gateway.inspect.ChatRequestInspector;
import org.icbca.gateway.inspect.InspectorPipeline;
import org.icbca.gateway.inspect.LoggingInspector;
import org.icbca.gateway.usage.InMemoryUsageRecorder;
import org.icbca.gateway.usage.UsageRecorder;
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

        ApiKeyStore apiKeyStore = new InMemoryApiKeyStore(config);
        UsageRecorder usageRecorder = new InMemoryUsageRecorder();
        log.info("API key auth {}", apiKeyStore.isAuthRequired() ? "enabled" : "open (no keys configured)");

        List<ChatRequestInspector> inspectors = new ArrayList<ChatRequestInspector>();
        inspectors.add(new AuthInspector(apiKeyStore));
        inspectors.add(new LoggingInspector());
        // Future guardrails: inspectors.add(new XxxGuardrailInspector(...));
        InspectorPipeline pipeline = new InspectorPipeline(inspectors);

        final GatewayServer server = new GatewayServer(config, pipeline, apiKeyStore, usageRecorder);
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

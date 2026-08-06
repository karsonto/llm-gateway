package org.icbca.gateway;

import org.icbca.gateway.admin.AdminSessionStore;
import org.icbca.gateway.auth.ApiKeyStore;
import org.icbca.gateway.auth.AuthInspector;
import org.icbca.gateway.auth.InMemoryApiKeyStore;
import org.icbca.gateway.auth.MonthlyQuotaInspector;
import org.icbca.gateway.auth.SqliteApiKeyStore;
import org.icbca.gateway.config.GatewayConfig;
import org.icbca.gateway.db.SqliteDatabase;
import org.icbca.gateway.inspect.ChatRequestInspector;
import org.icbca.gateway.inspect.ClassificationInspector;
import org.icbca.gateway.inspect.InspectorPipeline;
import org.icbca.gateway.inspect.LoggingInspector;
import org.icbca.gateway.inspect.UserPromptCsvCollector;
import org.icbca.gateway.usage.InMemoryUsageRecorder;
import org.icbca.gateway.usage.LatencyRecorder;
import org.icbca.gateway.usage.NoopLatencyRecorder;
import org.icbca.gateway.usage.SqliteLatencyRecorder;
import org.icbca.gateway.usage.SqliteUsageRecorder;
import org.icbca.gateway.usage.UsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point: load config, register default inspectors, start Netty gateway.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        GatewayConfig config = GatewayConfig.load();
        log.info("Loaded {}", config);

        final SqliteDatabase sqliteDb;
        final ApiKeyStore apiKeyStore;
        final UsageRecorder usageRecorder;
        final LatencyRecorder latencyRecorder;
        final AdminSessionStore adminSessions = new AdminSessionStore();

        if (config.isSqliteEnabled()) {
            sqliteDb = SqliteDatabase.open(config.getSqlitePath());
            apiKeyStore = new SqliteApiKeyStore(sqliteDb);
            usageRecorder = new SqliteUsageRecorder(sqliteDb, apiKeyStore);
            latencyRecorder = new SqliteLatencyRecorder(sqliteDb);
            log.info("Storage: SQLite at {} (gateway.api.keys ignored)", config.getSqlitePath());
        } else {
            sqliteDb = null;
            apiKeyStore = new InMemoryApiKeyStore(config);
            usageRecorder = new InMemoryUsageRecorder(apiKeyStore);
            latencyRecorder = NoopLatencyRecorder.INSTANCE;
            log.info("Storage: in-memory (admin console requires SQLite)");
        }

        log.info("API key auth {}", apiKeyStore.isAuthRequired() ? "enabled" : "open (no keys configured)");

        UserPromptCsvCollector csvCollector = null;
        if (config.isClassificationCollectEnabled()) {
            csvCollector = new UserPromptCsvCollector(config.getClassificationCollectCsv());
            log.info("User prompt CSV collect: {}", config.getClassificationCollectCsv());
        }

        List<ChatRequestInspector> inspectors = new ArrayList<ChatRequestInspector>();
        inspectors.add(new AuthInspector(apiKeyStore));
        if (config.isSqliteEnabled()) {
            inspectors.add(new MonthlyQuotaInspector(apiKeyStore, usageRecorder));
        }
        inspectors.add(new ClassificationInspector(csvCollector));
        inspectors.add(new LoggingInspector());
        InspectorPipeline pipeline = new InspectorPipeline(inspectors);

        final GatewayServer server = new GatewayServer(
                config, pipeline, apiKeyStore, usageRecorder, latencyRecorder, sqliteDb, adminSessions);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                log.info("Shutting down gateway...");
                server.stop();
                if (sqliteDb != null) {
                    sqliteDb.close();
                }
            }
        }));

        server.start();
        server.awaitTermination();
    }
}

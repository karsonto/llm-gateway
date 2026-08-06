package org.icbca.gateway.auth;

import org.icbca.gateway.inspect.ChatRequestContext;
import org.icbca.gateway.inspect.ChatRequestInspector;
import org.icbca.gateway.inspect.InspectionResult;
import org.icbca.gateway.usage.UsageRecorder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Soft monthly total_tokens quota check after auth. Limit {@code <= 0} means unlimited.
 */
public final class MonthlyQuotaInspector implements ChatRequestInspector {

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ApiKeyStore apiKeyStore;
    private final UsageRecorder usageRecorder;
    private final ZoneId zoneId;

    public MonthlyQuotaInspector(ApiKeyStore apiKeyStore, UsageRecorder usageRecorder) {
        this(apiKeyStore, usageRecorder, ZoneId.systemDefault());
    }

    public MonthlyQuotaInspector(ApiKeyStore apiKeyStore, UsageRecorder usageRecorder,
                                 ZoneId zoneId) {
        this.apiKeyStore = apiKeyStore;
        this.usageRecorder = usageRecorder;
        this.zoneId = zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    @Override
    public InspectionResult inspect(ChatRequestContext ctx) {
        if (apiKeyStore == null || usageRecorder == null) {
            return InspectionResult.allow();
        }
        if (!apiKeyStore.isAuthRequired()) {
            return InspectionResult.allow();
        }

        Object raw = ctx.getAttributes().get(AuthInspector.ATTR_API_KEY);
        String apiKey = raw == null ? null : String.valueOf(raw);
        if (apiKey == null || apiKey.isEmpty() || ApiKeyStore.ANONYMOUS_KEY.equals(apiKey)) {
            return InspectionResult.allow();
        }

        ApiKeyInfo info = apiKeyStore.find(apiKey);
        if (info == null) {
            return InspectionResult.allow();
        }
        long limit = info.getMonthlyTokenLimit();
        if (limit <= 0L) {
            return InspectionResult.allow();
        }

        String yearMonth = LocalDate.now(zoneId).format(YEAR_MONTH);
        long used = usageRecorder.sumTotalTokensForMonth(apiKey, yearMonth);
        if (used >= limit) {
            return InspectionResult.deny(429, "monthly_token_limit_exceeded",
                    "Monthly token limit exceeded: used=" + used + ", limit=" + limit);
        }
        return InspectionResult.allow();
    }
}

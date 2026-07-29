package org.icbca.gateway.inspect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordered chain of {@link ChatRequestInspector}s. First DENY short-circuits.
 */
public final class InspectorPipeline {

    private final List<ChatRequestInspector> inspectors;

    public InspectorPipeline(List<ChatRequestInspector> inspectors) {
        this.inspectors = Collections.unmodifiableList(new ArrayList<ChatRequestInspector>(inspectors));
    }

    public InspectionResult inspect(ChatRequestContext ctx) {
        for (ChatRequestInspector inspector : inspectors) {
            InspectionResult result = inspector.inspect(ctx);
            if (result == null) {
                continue;
            }
            if (!result.isAllowed()) {
                return result;
            }
        }
        return InspectionResult.allow();
    }

    public List<ChatRequestInspector> getInspectors() {
        return inspectors;
    }
}

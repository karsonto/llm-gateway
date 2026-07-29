package org.icbca.gateway.inspect;

/**
 * Result of a {@link ChatRequestInspector}.
 * Future extensions may add response headers or a sanitized request body for rewrite-before-forward;
 * rewrite is not implemented in this MVP.
 */
public final class InspectionResult {

    private final boolean allowed;
    private final int httpStatus;
    private final String code;
    private final String message;

    private InspectionResult(boolean allowed, int httpStatus, String code, String message) {
        this.allowed = allowed;
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public static InspectionResult allow() {
        return new InspectionResult(true, 200, null, null);
    }

    public static InspectionResult deny(int httpStatus, String code, String message) {
        return new InspectionResult(false, httpStatus, code, message);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}

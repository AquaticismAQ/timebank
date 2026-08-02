package cn.timebank.common.web;

/**
 * Per-request identity available to any layer without threading a
 * parameter through every call — {@code requestId} for the envelope and
 * audit rows, {@code ip}/{@code userAgent} for audit rows only (never
 * logged verbatim per contract §9). Populated by {@link RequestIdFilter}
 * for the lifetime of one request and cleared in its {@code finally} block.
 *
 * A plain {@link ThreadLocal} is correct here even under virtual threads:
 * each request runs on its own virtual thread for its whole lifecycle, so
 * there is no reuse across concurrent requests the way there would be with
 * a pooled-thread executor holding onto stale context.
 */
public final class RequestContext {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private final String requestId;
    private final String ipAddress;
    private final String userAgent;
    private String principalId;

    private RequestContext(String requestId, String ipAddress, String userAgent) {
        this.requestId = requestId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    static void set(String requestId, String ipAddress, String userAgent) {
        CURRENT.set(new RequestContext(requestId, ipAddress, userAgent));
    }

    static void clear() {
        CURRENT.remove();
    }

    /** Never null during request handling; returns a placeholder outside it (e.g. in a unit test). */
    public static RequestContext current() {
        RequestContext ctx = CURRENT.get();
        return ctx != null ? ctx : new RequestContext("no-request", null, null);
    }

    public String requestId() {
        return requestId;
    }

    public String ipAddress() {
        return ipAddress;
    }

    public String userAgent() {
        return userAgent;
    }

    public String principalId() {
        return principalId;
    }

    /** Set once security (B1) resolves the authenticated principal for this request. */
    public void setPrincipalId(String principalId) {
        this.principalId = principalId;
    }
}

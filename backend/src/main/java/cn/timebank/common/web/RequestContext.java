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

    /**
     * The three values an audit row needs, copied out so they can cross a
     * thread boundary (issue 149).
     *
     * <p>Deliberately not the {@code RequestContext} itself: that object is
     * mutable ({@link #setPrincipalId}) and belongs to the request's own
     * thread. A record of three strings cannot be written back into by the
     * worker that borrows it.</p>
     */
    public record Snapshot(String requestId, String ipAddress, String userAgent) {
    }

    /** Taken on the request thread, while there still is one. */
    public static Snapshot snapshot() {
        RequestContext ctx = current();
        return new Snapshot(ctx.requestId, ctx.ipAddress, ctx.userAgent);
    }

    /**
     * Runs {@code body} on this thread with {@code snapshot}'s identity in
     * place, so audit rows written from a background job name the person who
     * asked for it rather than {@code no-request} and a null IP.
     *
     * <p>The {@code finally} is the load-bearing part. Unlike a request's own
     * virtual thread, a job executor's threads are pooled and reused, so a
     * context left behind here would be inherited by whatever ran next — the
     * precise failure this class's javadoc says a ThreadLocal is safe from
     * <em>because</em> requests do not share threads.</p>
     */
    public static void runWithin(Snapshot snapshot, Runnable body) {
        set(snapshot.requestId(), snapshot.ipAddress(), snapshot.userAgent());
        try {
            body.run();
        } finally {
            clear();
        }
    }

    /** Set once security (B1) resolves the authenticated principal for this request. */
    public void setPrincipalId(String principalId) {
        this.principalId = principalId;
    }
}

package cn.timebank.audit.application;

import cn.timebank.audit.application.dto.AuditLogDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * B5.12's read side of the operation audit log — deliberately a separate
 * interface from {@link AuditService} (the writer), so no query method can
 * ever be mistaken for part of the write path invariant 10 depends on.
 */
public interface AuditLogQueryService {

    /**
     * Issue 098 widened this: {@code search} and {@code action} are new, and
     * {@code pageable}'s sort is now <em>honoured</em> rather than overridden by
     * a hardcoded {@code order by created_at desc} — ascending order was
     * unreachable, while the controller validated the parameter and made it look
     * supported.
     *
     * @param search matches the actor's display name, the action, the resource
     *               id or the request id. Case-insensitive, substring.
     */
    Page<AuditLogDto> search(String search, String action, String resourceType, String resourceId, String actorId,
                              Instant createdFrom, Instant createdTo, Pageable pageable);

    /**
     * The distinct values present in the log, for the filter dropdowns.
     *
     * <p>Served rather than hardcoded on the client because action codes accrue
     * as features are added — {@code humanizeAuditAction} exists in the frontend
     * precisely because a fixed per-code table drifts out of sync with the
     * backend. A dropdown built from a literal list would quietly stop offering
     * whatever was added last.</p>
     */
    Facets facets();

    /**
     * Every row recorded against the given resources, <b>oldest first</b> — the
     * whole history of a few specific things rather than a page of everything.
     *
     * <p>Unpaged on purpose. A caller asks this to assemble one resource's
     * story, and a story with page 2 missing is not a story; the bound is the
     * number of events that resource has had, which is tens, not the size of the
     * log. Compare {@link #search}, which pages because it walks the whole
     * table.</p>
     *
     * <p>An empty {@code resourceIds} returns an empty list without touching the
     * database — {@code in ()} is not valid SQL, and "no resources" has an
     * obvious answer.</p>
     */
    List<AuditLogDto> entriesFor(String resourceType, Collection<String> resourceIds);

    /** Sorted, distinct, and drawn from the rows themselves — never a literal list. */
    record Facets(List<String> actions, List<String> resourceTypes) {
    }
}

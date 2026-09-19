package cn.timebank.dataexchange.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Carries the resolved {@link ExportScope} from the request thread to the job
 * thread, through {@code job.params_json} (§2.6).
 *
 * <p><b>Scope is decided at request time and stored; the runner never re-derives
 * it.</b> The runner has no {@code Authentication} — it runs later, on another
 * thread — and even if it could rebuild one, a teacher's grade assignments might
 * have changed in between, so re-deriving could produce a file nobody asked for.
 * Storing the decision also makes the job self-describing: the audit entry and
 * the job row agree, so an export is reconstructable from the log alone.</p>
 *
 * <p>Reusing {@code params_json} rather than adding four nullable columns to
 * {@code job}: the column exists for exactly this and is otherwise unused, and
 * four columns only one job kind populates is a worse schema. What goes in is the
 * scope's <em>criteria</em> — a few opaque ids — so the {@code VARCHAR(2000)}
 * budget does not depend on how many students the school has.</p>
 */
@Component
class ExportJobParameters {

    private final ObjectMapper objectMapper;

    ExportJobParameters(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    String write(ExportScope scope) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("selectedYearIndex", scope.selectedYearIndex());
            payload.put("currentYearIndex", scope.currentYearIndex());
            payload.put("reviewerId", scope.reviewerId());
            // The year's real first and last day, carried rather than recomputed
            // in the job: the boundary is configurable and a stored year keeps the
            // dates it was created with, so recomputing on the job thread could
            // widen or narrow the file relative to what was authorised.
            payload.put("yearFrom", scope.yearFrom() == null ? null : scope.yearFrom().toString());
            payload.put("yearTo", scope.yearTo() == null ? null : scope.yearTo().toString());
            ExportScope.Restriction restriction = scope.restriction();
            if (restriction != null) {
                // `restricted` is written explicitly rather than inferred from the
                // presence of the fields below. A restriction can legitimately
                // consist of bound grades alone, and "no keys present" must not be
                // readable as "no restriction" — that would turn a scoped export
                // into a school-wide one on a serialization detail.
                payload.put("restricted", true);
                putIfNotNull(payload, "boundGradeIds", restriction.boundGradeIds());
                putIfNotNull(payload, "boundClassIds", restriction.boundClassIds());
                payload.put("gradeFilter", restriction.gradeFilter());
                payload.put("classFilter", restriction.classFilter());
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize export parameters", e);
        }
    }

    ExportScope read(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            // A job written before filters existed, or one hand-inserted by a test.
            // Unrestricted with no year is the pre-filter behaviour, and the only
            // defensible reading of "no parameters were recorded".
            return new ExportScope(null, null, java.time.LocalDate.now().getYear(), null, null, null);
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(paramsJson, new TypeReference<>() {
            });
            Integer selectedYear = asInteger(payload.get("selectedYearIndex"));
            Integer currentYear = asInteger(payload.get("currentYearIndex"));
            String reviewerId = (String) payload.get("reviewerId");

            ExportScope.Restriction restriction = null;
            if (Boolean.TRUE.equals(payload.get("restricted"))) {
                restriction = new ExportScope.Restriction(
                        asIdSet(payload.get("boundGradeIds")),
                        asIdSet(payload.get("boundClassIds")),
                        (String) payload.get("gradeFilter"),
                        (String) payload.get("classFilter"));
            }
            return new ExportScope(restriction, selectedYear,
                    currentYear == null ? java.time.LocalDate.now().getYear() : currentYear, reviewerId,
                    asDate(payload.get("yearFrom")), asDate(payload.get("yearTo")));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read export parameters: " + paramsJson, e);
        }
    }

    private static void putIfNotNull(Map<String, Object> payload, String key, Set<String> value) {
        if (value != null) {
            payload.put(key, List.copyOf(value));
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> asIdSet(Object value) {
        return value == null ? null : new LinkedHashSet<>((List<String>) value);
    }

    /** A job written before the window was recorded has no dates; null is then the honest answer. */
    private static java.time.LocalDate asDate(Object value) {
        return value == null ? null : java.time.LocalDate.parse(value.toString());
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
    }
}

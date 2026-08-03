package cn.timebank.common.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * A JSON array of opaque strings — used wherever an entity carries a plain
 * list of other resources' ids with no FK of its own (`service_record
 * .evidence_file_ids`, `project.eligible_grade_ids`, `event_proposal
 * .co_organizer_student_ids`). Lives in `common` (dependency-free, per
 * Docs/implementation/00-Status.md §11's module-boundary discipline) rather
 * than any one module's `domain` package, since B4 made it the second and
 * third consumer of what B3 first wrote as a `servicerecord`-local type.
 *
 * A private static {@link ObjectMapper} rather than an injected one: JPA
 * attribute converters are instantiated by the persistence provider, not
 * Spring, and this conversion needs no custom module configuration.
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize string-list column", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(dbData, MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("Could not deserialize string-list column", e);
        }
    }
}

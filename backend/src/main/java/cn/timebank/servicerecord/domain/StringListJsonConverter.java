package cn.timebank.servicerecord.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * {@code evidence_file_ids} is a plain JSON array of opaque file ids — no
 * FK, since the `file` module doesn't exist yet (B3.4 is a deliberate
 * follow-up). A private static {@link ObjectMapper} rather than an
 * injected one: JPA attribute converters are instantiated by the
 * persistence provider, not Spring, and this conversion needs no custom
 * module configuration.
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
            throw new IllegalStateException("Could not serialize evidenceFileIds", e);
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
            throw new IllegalStateException("Could not deserialize evidenceFileIds", e);
        }
    }
}

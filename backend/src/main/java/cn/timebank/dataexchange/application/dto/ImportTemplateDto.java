package cn.timebank.dataexchange.application.dto;

/**
 * A blank import template. {@code fileName} is built from the rule set's own
 * type name, never from the request path, so a value the client sent cannot
 * reach a response header.
 */
public record ImportTemplateDto(String fileName, String csv) {
}

package cn.timebank.dataexchange.application.dto;

import java.util.List;

/**
 * One entry of `GET /exports/types` (`11-DataImportExportOptimization.md` §2.6.1).
 *
 * <p>Carries i18n <em>keys</em>, not rendered text. The server owns which filters
 * a type has and which types exist; the client owns how the words look, and
 * already holds both bundles. Sending rendered strings would put the copy behind
 * a deployment and lose the client's ability to switch language without a
 * refetch.</p>
 *
 * @param key           stable request key, also the {@code exports.<key>} permission suffix
 * @param titleKey      i18n key for the card title
 * @param descriptionKey i18n key for the card's explanatory paragraph — a short paragraph, not a label, so a client must give it room rather than truncating to one line (§2.8)
 * @param filters       the filter fields this export accepts, e.g. {@code academicYearId}
 * @param available     whether a query exists yet; false means draw the card and say so, rather than letting the caller find out from a failed job
 */
public record ExportTypeDto(String key, String titleKey, String descriptionKey,
                             List<String> filters, boolean available) {
}

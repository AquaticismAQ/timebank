package cn.timebank.organization.application.dto;

/**
 * {@code name} is the caller's language already resolved (every picker reads it);
 * {@code nameZh}/{@code nameEn} are the raw stored values, added for the admin
 * management screen, which has to show and keep *both* regardless of the
 * language the administrator happens to be using (issue 078).
 */
public record ServiceCategoryDto(String id, String code, String name, String nameZh, String nameEn, boolean active) {
}

package cn.timebank.organization.application;

import cn.timebank.organization.application.dto.SchoolInfoDto;

/**
 * Contract §4.16. Also the source of the school's default language for
 * B2.5's resolution chain (`Accept-Language` → `preferredLanguage` →
 * school default) — every other module that needs a language fallback
 * calls {@link #getDefaultLanguage()} rather than hardcoding one.
 */
public interface SchoolInfoService {

    SchoolInfoDto getInfo();

    String getDefaultLanguage();
}

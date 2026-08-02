package cn.timebank.organization.application;

import cn.timebank.common.i18n.LanguageResolver;
import org.springframework.stereotype.Service;

@Service
class RequestLanguageServiceImpl implements RequestLanguageService {

    private final LanguageResolver languageResolver;
    private final SchoolInfoService schoolInfoService;

    RequestLanguageServiceImpl(LanguageResolver languageResolver, SchoolInfoService schoolInfoService) {
        this.languageResolver = languageResolver;
        this.schoolInfoService = schoolInfoService;
    }

    @Override
    public String resolve(String acceptLanguageHeader, String preferredLanguageOrNull) {
        return languageResolver.resolve(acceptLanguageHeader, preferredLanguageOrNull, schoolInfoService.getDefaultLanguage());
    }
}

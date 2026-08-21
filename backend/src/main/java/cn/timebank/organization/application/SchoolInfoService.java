package cn.timebank.organization.application;

import cn.timebank.organization.application.dto.SchoolInfoDto;
import cn.timebank.organization.application.dto.SchoolInfoRequests.UpdateSchoolInfoRequest;

/**
 * Contract §4.16. Also the source of the school's default language for
 * B2.5's resolution chain (`Accept-Language` → `preferredLanguage` →
 * school default) — every other module that needs a language fallback
 * calls {@link #getDefaultLanguage()} rather than hardcoding one.
 */
public interface SchoolInfoService {

    SchoolInfoDto getInfo();

    String getDefaultLanguage();

    /**
     * {@code isSysadmin} is resolved by the caller (the controller, from the
     * real {@code Authentication}) and passed in rather than checked via
     * {@code @PreAuthorize} — a plain permission check can't distinguish
     * {@code sysadmin} from {@code admin} now that the two roles hold
     * identical grants (`V21`'s merge), and this must stay narrower than
     * {@code admin}. Same pattern as {@code ProjectController.isAdmin(auth)}
     * being passed into {@code ProjectService.createDraft}, kept here so the
     * authorization rule itself stays unit/integration-testable without a
     * real HTTP round-trip.
     */
    SchoolInfoDto update(UpdateSchoolInfoRequest request, boolean isSysadmin, String actorId);
}

package cn.timebank.organization.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.organization.application.SchoolInfoService;
import cn.timebank.organization.application.dto.SchoolInfoDto;
import cn.timebank.organization.application.dto.SchoolInfoRequests.UpdateSchoolInfoRequest;
import cn.timebank.security.application.CookieSessionUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Contract §4.16. */
@RestController
public class SchoolInfoController {

    private final SchoolInfoService schoolInfoService;

    public SchoolInfoController(SchoolInfoService schoolInfoService) {
        this.schoolInfoService = schoolInfoService;
    }

    @GetMapping("/api/v1/school/info")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<SchoolInfoDto> getInfo() {
        return ApiResponse.of(schoolInfoService.getInfo());
    }

    /**
     * {@code sysadmin} only, deliberately not {@code admin} even though the
     * two roles hold identical permission grants since `V21`'s merge — a
     * plain `settings.manage` `@PreAuthorize` check would let `admin`
     * through too, so the role code is checked directly (in the service —
     * see its javadoc) instead of introducing a new permission just to
     * separate the two.
     */
    @PatchMapping("/api/v1/school/info")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<SchoolInfoDto> updateInfo(@Valid @RequestBody UpdateSchoolInfoRequest request, Authentication auth) {
        CookieSessionUserDetails principal = (CookieSessionUserDetails) auth.getPrincipal();
        return ApiResponse.of(schoolInfoService.update(request, principal.roleCodes().contains("sysadmin"), principal.id()));
    }
}

package cn.timebank.organization.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.organization.application.SchoolInfoService;
import cn.timebank.organization.application.dto.SchoolIdentityDto;
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
     * The school's name, unauthenticated — the login page shows whose system
     * this is, and cannot call {@code /school/info}, which is
     * {@code isAuthenticated()} and so returned 401 to every visitor who had not
     * logged in yet. The name was simply blank there.
     *
     * <p><b>Why a second endpoint rather than opening the first.</b>
     * {@code SchoolInfoDto} also carries {@code contact} — a member of staff by
     * name — and {@code studentDevCenter}, a room. Neither belongs on a page
     * anyone on the internet can load, and §4.3's data-minimisation commitment
     * is the reason. {@link SchoolIdentityDto} cannot hold them, so widening
     * this later takes a deliberate edit rather than a field being added to a
     * shared record.</p>
     */
    @GetMapping("/api/v1/school/identity")
    @PreAuthorize("permitAll()")
    public ApiResponse<SchoolIdentityDto> getIdentity() {
        return ApiResponse.of(schoolInfoService.getIdentity());
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

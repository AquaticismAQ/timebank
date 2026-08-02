package cn.timebank.organization.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.organization.application.SchoolInfoService;
import cn.timebank.organization.application.dto.SchoolInfoDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
}

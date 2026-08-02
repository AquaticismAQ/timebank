package cn.timebank.iam.web;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.iam.application.LoginLogService;
import cn.timebank.iam.application.dto.LoginLogDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** Contract §4.15: `GET /login-logs`, behind `securityLogs.read` (B1.10). */
@RestController
public class LoginLogController {

    private static final Set<String> SORTABLE = Set.of("id", "createdAt");

    private final LoginLogService loginLogService;

    public LoginLogController(LoginLogService loginLogService) {
        this.loginLogService = loginLogService;
    }

    @GetMapping("/api/v1/login-logs")
    @PreAuthorize("hasAuthority('securityLogs.read')")
    public ApiResponse<List<LoginLogDto>> list(PageQuery query) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<LoginLogDto> page = loginLogService.list(pageable);
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }
}

package cn.timebank.iam.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.MeResponse;
import cn.timebank.iam.application.dto.UpdateProfileRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Contract §4.1, §2.2: `/me`, `/me/profile`. */
@RestController
public class MeController {

    private final UserService userService;

    public MeController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/api/v1/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<MeResponse> me(Authentication auth) {
        String userId = ((CookieSessionUserDetails) auth.getPrincipal()).id();
        return ApiResponse.of(userService.buildMeResponse(userId));
    }

    @PatchMapping("/api/v1/me/profile")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<UserDto> updateProfile(@Valid @RequestBody UpdateProfileRequest request, Authentication auth) {
        String userId = ((CookieSessionUserDetails) auth.getPrincipal()).id();
        return ApiResponse.of(userService.updateProfile(userId, request));
    }
}

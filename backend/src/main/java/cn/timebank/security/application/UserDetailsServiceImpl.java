package cn.timebank.security.application;

import cn.timebank.iam.application.UserService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * The one place {@code security} depends on {@code iam} for user data —
 * everything else in the login flow goes through Spring Security's
 * standard {@code DaoAuthenticationProvider}, which calls this.
 */
@Service
class UserDetailsServiceImpl implements UserDetailsService {

    private final UserService userService;

    UserDetailsServiceImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userService.findByUsernameForAuth(username)
                .map(CookieSessionUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + username));
    }
}

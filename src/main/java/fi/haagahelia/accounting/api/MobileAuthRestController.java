package fi.haagahelia.accounting.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fi.haagahelia.accounting.api.dto.AuthResponse;
import fi.haagahelia.accounting.service.MobileRefreshTokenService;
import fi.haagahelia.accounting.service.UserDetailsServiceImpl;

@RestController
@RequestMapping("/api/auth/mobile")
public class MobileAuthRestController {

    private final MobileRefreshTokenService tokens;
    private final UserDetailsServiceImpl users;
    private final HttpSessionSecurityContextRepository contexts =
            new HttpSessionSecurityContextRepository();

    public MobileAuthRestController(MobileRefreshTokenService tokens, UserDetailsServiceImpl users) {
        this.tokens = tokens;
        this.users = users;
    }

    @PostMapping("/token")
    public ResponseEntity<TokenBody> issue(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new TokenBody(tokens.issue(auth.getName())));
    }

    @PostMapping("/restore")
    public ResponseEntity<AuthResponse> restore(@RequestBody TokenBody body,
            HttpServletRequest request, HttpServletResponse response) {
        var user = users.loadUserByUsername(tokens.resolveUsername(body.refreshToken()));
        HttpSession previous = request.getSession(false);
        if (previous != null) previous.invalidate();

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);

        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AuthResponse(user.getUsername()));
    }

    @PostMapping("/revoke")
    public ResponseEntity<Void> revoke(@RequestBody TokenBody body) {
        tokens.revoke(body.refreshToken());
        return ResponseEntity.noContent().build();
    }

    public record TokenBody(String refreshToken) {
    }
}

package fi.haagahelia.accounting.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.model.MobileRefreshToken;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.MobileRefreshTokenRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@Service
public class MobileRefreshTokenService {

    private final SecureRandom random = new SecureRandom();
    private final MobileRefreshTokenRepository tokens;
    private final UserRepository users;

    public MobileRefreshTokenService(MobileRefreshTokenRepository tokens, UserRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Transactional
    public String issue(String username) {
        User user = users.findByUsername(username);
        if (user == null) throw invalidToken();
        Instant now = Instant.now();
        tokens.deleteExpired(now);

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new MobileRefreshToken(hash(token), user.getId(), now.plus(Duration.ofDays(30))));
        return token;
    }

    @Transactional(readOnly = true)
    public String resolveUsername(String token) {
        if (!validFormat(token)) throw invalidToken();
        return tokens.findById(hash(token))
                .filter(stored -> stored.getExpiresAt().isAfter(Instant.now()))
                .flatMap(stored -> users.findById(stored.getUserId()))
                .map(User::getUsername)
                .orElseThrow(MobileRefreshTokenService::invalidToken);
    }

    @Transactional
    public void revoke(String token) {
        if (validFormat(token)) tokens.revoke(hash(token));
    }

    private static boolean validFormat(String token) {
        return token != null && token.length() == 43 && token.matches("[A-Za-z0-9_-]+");
    }

    private static ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired mobile token");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

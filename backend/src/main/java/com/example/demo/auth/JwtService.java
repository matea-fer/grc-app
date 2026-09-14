package com.example.demo.auth;

import com.example.demo.exception.UnauthorizedException;
import com.example.demo.model.Role;
import com.example.demo.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Izdavanje i citanje JWT-a. Kriptografiju radi jjwt - ovdje je samo odluka sto
 * ulazi u token i koliko dugo vrijedi.
 *
 * U tokenu su korisnik, uloga i firma, pa zahtjev ne mora dirati bazu da bi znao
 * tko salje. Cijena je da promjena uloge ili firme pocinje vrijediti tek nakon
 * sljedece prijave - to je svjesna zamjena i zapisana je u ogranicenjima.
 *
 * Sve greske pri citanju (istekao, promijenjen potpis, besmislen niz) zavrsavaju
 * kao {@link UnauthorizedException} s istom porukom: klijentu nije korisno znati
 * KOJI dio tokena ne valja, a napadacu jest.
 */
@Component
public class JwtService {

    // HS256 trazi kljuc od najmanje 256 bitova; krace tajne jjwt odbija, pa se to
    // provjeri odmah pri pokretanju umjesto na prvoj prijavi.
    private static final int MIN_SECRET_LENGTH = 32;

    private final SecretKey key;
    private final Duration expiry;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiry-hours}") long expiryHours) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT_SECRET mora imati najmanje " + MIN_SECRET_LENGTH + " znakova (HS256 trazi 256 bitova).");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiry = Duration.ofHours(expiryHours);
    }

    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("role", user.getRole().name())
                .claim("companyId", user.getCompanyId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(key)
                .compact();
    }

    /**
     * @throws UnauthorizedException ako token nedostaje, nije potpisan ovim kljucem,
     *                               je istekao ili mu sadrzaj ne odgovara ocekivanom obliku
     */
    public AuthenticatedUser parse(String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Potrebna je prijava.");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new AuthenticatedUser(
                    claims.get("uid", Long.class),
                    claims.getSubject(),
                    Role.valueOf(claims.get("role", String.class)),
                    claims.get("companyId", Long.class));
        } catch (JwtException | IllegalArgumentException ex) {
            // ista poruka za sve razloge - vidi javadoc razreda
            throw new UnauthorizedException("Prijava je istekla ili nije valjana.");
        }
    }

    /** @return token iz zaglavlja {@code Authorization: Bearer <token>}, ili null ako ga nema */
    public static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }
}

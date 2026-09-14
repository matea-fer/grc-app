package com.example.demo.config;

import com.example.demo.model.Role;
import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Stvara pocetni ADMIN racun ako u bazi jos nema nijednog korisnika.
 *
 * Namjerno seeda SAMO njega: ostali korisnici se dodaju kroz {@code /api/users}
 * (ili ekran "Korisnici"), pa se ne pojavljuju racuni koje nitko nije trazio.
 * Bez ovog jednog racuna aplikacija bi bila zakljucana sama pred sobom - nema
 * prijave bez korisnika, ni korisnika bez prijave.
 *
 * Uvjet je "tablica je prazna", a ne "nema korisnika 'admin'": ako je ADMIN
 * namjerno obrisan ili preimenovan, ne vraca ga natrag na svakom pokretanju.
 */
@Component
public class DataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String ADMIN_USERNAME = "admin";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;

    public DataSeeder(UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${app.seed.admin-password}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }

        userRepository.save(new User(ADMIN_USERNAME, passwordEncoder.encode(adminPassword), Role.ADMIN, null));
        log.warn("Baza nije imala nijednog korisnika - stvoren pocetni ADMIN racun \"{}\". "
                + "Lozinka je iz SEED_ADMIN_PASSWORD; promijeni je prije bilo kakve stvarne upotrebe.",
                ADMIN_USERNAME);
    }
}

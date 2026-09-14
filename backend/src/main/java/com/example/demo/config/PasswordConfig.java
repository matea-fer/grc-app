package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt za lozinke. Dolazi iz spring-security-crypto, koji je samostalan jar bez
 * autokonfiguracije - zato se bean deklarira rucno.
 *
 * BCrypt sam soli svaki hash, pa dva korisnika s istom lozinkom nemaju isti zapis
 * u bazi i usporedba ide iskljucivo kroz {@code matches}, nikad usporedbom nizova.
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

package com.example.demo.service;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.auth.JwtService;
import com.example.demo.dto.CurrentUserResponse;
import com.example.demo.dto.LoginRequest;
import com.example.demo.dto.LoginResponse;
import com.example.demo.exception.UnauthorizedException;
import com.example.demo.model.Company;
import com.example.demo.model.User;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Prijava i podatak o prijavljenom korisniku.
 *
 * Nepoznato korisnicko ime i kriva lozinka vracaju ISTU poruku i isti status.
 * Da se razlikuju, popis postojecih korisnickih imena bi se dao izvuci pogadanjem -
 * isti razlog zbog kojeg tudi redak vraca 404 umjesto 403.
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String REJECTED = "Neispravno korisnicko ime ili lozinka.";

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthContext authContext;
    private final LogService logService;

    public AuthService(UserRepository userRepository,
                       CompanyRepository companyRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuthContext authContext,
                       LogService logService) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authContext = authContext;
        this.logService = logService;
    }

    public LoginResponse login(LoginRequest request) {
        Optional<User> found = userRepository.findByUsername(request.username());

        // Nepoznat korisnik: nema firme na koju bi se zapis vezao, pa ide bez nje.
        if (found.isEmpty()) {
            logService.record("LOGIN_FAILED", "Nepoznato korisnicko ime \"" + request.username() + "\"",
                    null, request.username());
            throw new UnauthorizedException(REJECTED);
        }

        User user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            logService.record("LOGIN_FAILED", "Neispravna lozinka za \"" + user.getUsername() + "\"",
                    user.getCompanyId(), user.getUsername());
            throw new UnauthorizedException(REJECTED);
        }

        // Firma arhivirana nakon sto je racun stvoren: racun je ispravan, ali nema u sto
        // pustiti. Provjera je NAMJERNO tek nakon lozinke - da se iz odgovora ne moze
        // saznati koje firme postoje ni koji korisnik kojoj pripada bez znanja lozinke.
        // Poruka je zato drugacija od one za krivu lozinku: tko je dosao dovde, vec je
        // dokazao da je vlasnik racuna, pa mu se smije reci sto se zapravo dogodilo.
        if (isArchived(user.getCompanyId())) {
            logService.record("LOGIN_REJECTED_ARCHIVED",
                    "Prijava korisnika \"" + user.getUsername() + "\" odbijena - firma je arhivirana",
                    user.getCompanyId(), user.getUsername());
            throw new UnauthorizedException("Firma je arhivirana, prijava nije moguća.");
        }

        String token = jwtService.issue(user);
        log.info("Login ok username={} role={}", user.getUsername(), user.getRole());
        logService.record("LOGIN_SUCCESS", "Prijava korisnika \"" + user.getUsername() + "\"",
                user.getCompanyId(), user.getUsername());

        return new LoginResponse(token, user.getUsername(), user.getRole(), user.getCompanyId(),
                companyName(user.getCompanyId()));
    }

    /**
     * Provjera da token iz preglednika jos vrijedi; filter ga je vec potvrdio prije ovoga.
     *
     * Uz to i da firma iz tokena jos radi. Bez ove provjere bi korisnik cija je firma
     * arhivirana nakon prijave ostao "prijavljen" do isteka tokena, a svaki bi mu ekran
     * vracao gresku - stanje u kojem aplikacija tvrdi da je sve u redu, a nista ne radi.
     * Ovako preglednik dobije 401 i odjavi ga.
     */
    public CurrentUserResponse me() {
        AuthenticatedUser user = authContext.require();
        if (isArchived(user.companyId())) {
            throw new UnauthorizedException("Firma je arhivirana, prijava nije moguća.");
        }
        return new CurrentUserResponse(user.username(), user.role(), user.companyId(),
                companyName(user.companyId()));
    }

    /** Je li firma arhivirana (ili je uopce nema). Globalni ADMIN nema firmu, pa nikad nije. */
    private boolean isArchived(Long companyId) {
        return companyId != null && !companyRepository.existsByIdAndDeletedAtIsNull(companyId);
    }

    /**
     * Naziv firme za prikaz. Namjerno gleda i arhivirane ({@code findById}, ne inacica s
     * {@code DeletedAtIsNull}): ovdje se ne odlucuje smije li se nesto, nego se samo ispisuje
     * naziv vec poznate firme.
     */
    private String companyName(Long companyId) {
        if (companyId == null) {
            return null;
        }
        return companyRepository.findById(companyId).map(Company::getName).orElse(null);
    }
}

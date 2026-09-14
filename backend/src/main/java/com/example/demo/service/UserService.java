package com.example.demo.service;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CreateUserRequest;
import com.example.demo.dto.UserResponse;
import com.example.demo.exception.DuplicateUsernameException;
import com.example.demo.exception.ForbiddenException;
import com.example.demo.exception.InvalidUserException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Company;
import com.example.demo.model.Role;
import com.example.demo.model.User;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Korisnici. Resurs je zatvoren za obicnog korisnika, a otvoren za dvije uloge s
 * RAZLICITIM dosegom:
 *
 * <ul>
 *   <li>{@code ADMIN} (globalni) - vidi i mijenja sve racune, u svim firmama, i
 *       jedini smije stvoriti novog globalnog administratora.</li>
 *   <li>{@code TENANT_ADMIN} - vidi i mijenja iskljucivo racune SVOJE firme, i
 *       u njoj smije stvarati obicne korisnike i druge administratore te firme.</li>
 * </ul>
 *
 * Doseg se svugdje racuna iz tokena ({@link AuthContext}), nikad iz tijela zahtjeva -
 * inace bi admin firme mogao poslati tudi {@code companyId} i time izaci iz svoje firme.
 *
 * Tudi racun se administratoru firme predstavlja kao 404, a ne 403 - isto pravilo
 * kao kod tudeg obrasca: da se ni ne otkrije da postoji.
 *
 * Firma moze imati koliko god korisnika; ogranicenje ide samo u drugom smjeru, da
 * jedan korisnik ne pripada dvjema firmama (vidi {@link User}).
 */
@Service
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository repository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthContext authContext;
    private final LogService logService;

    public UserService(UserRepository repository,
                       CompanyRepository companyRepository,
                       PasswordEncoder passwordEncoder,
                       AuthContext authContext,
                       LogService logService) {
        this.repository = repository;
        this.companyRepository = companyRepository;
        this.passwordEncoder = passwordEncoder;
        this.authContext = authContext;
        this.logService = logService;
    }

    public List<UserResponse> getAll() {
        AuthenticatedUser current = authContext.requireUserManager();
        // nazivi firmi odjednom, da popis od N korisnika ne postane N upita
        Map<Long, String> names = companyRepository.findAll().stream()
                .collect(Collectors.toMap(Company::getId, Company::getName));
        return visibleTo(current).stream()
                .map(user -> UserResponse.from(user, names.get(user.getCompanyId())))
                .toList();
    }

    public UserResponse create(CreateUserRequest request) {
        AuthenticatedUser current = authContext.requireUserManager();
        String username = request.username().trim();
        if (repository.existsByUsername(username)) {
            throw new DuplicateUsernameException(username);
        }
        Long companyId = validatedCompany(current, request);

        User saved = repository.save(new User(username, passwordEncoder.encode(request.password()),
                request.role(), companyId));
        log.info("Created User id={} role={} companyId={} by={}",
                saved.getId(), saved.getRole(), companyId, current.username());
        logService.recordForCompany(companyId, "USER_CREATED",
                "Korisnik \"" + username + "\" (" + request.role() + ")");
        return UserResponse.from(saved, companyName(companyId));
    }

    public void delete(Long id) {
        AuthenticatedUser current = authContext.requireUserManager();
        User user = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        requireVisible(current, user);

        // Brisanje sebe bi zakljucalo sesiju koja je brisanje i pokrenula.
        if (user.getId().equals(current.userId())) {
            throw new InvalidUserException("Ne možete obrisati vlastiti račun.");
        }
        // Bez ijednog globalnog ADMIN-a nitko vise ne bi mogao dodati firmu.
        if (user.getRole() == Role.ADMIN && countAdmins() <= 1) {
            throw new InvalidUserException("Mora postojati barem jedan administrator.");
        }

        repository.delete(user);
        log.info("Deleted User id={} by={}", id, current.username());
        logService.recordForCompany(user.getCompanyId(), "USER_DELETED",
                "Korisnik \"" + user.getUsername() + "\" (" + user.getRole() + ")");
    }

    /**
     * Racuni koje pozivatelj smije vidjeti.
     *
     * Administrator firme dobiva samo svoju firmu; globalni administratori tako
     * otpadaju sami od sebe, jer oni {@code companyId} nemaju.
     */
    private List<User> visibleTo(AuthenticatedUser current) {
        if (current.isAdmin()) {
            return repository.findAll();
        }
        return repository.findByCompanyId(current.companyId());
    }

    /**
     * Racun mora biti u dosegu pozivatelja, inace se ponasa kao da ne postoji.
     *
     * 404, a ne 403: administratoru firme se ne smije potvrditi da racun u drugoj
     * firmi postoji - isto pravilo kao kod tudeg obrasca ili zapisa.
     */
    private void requireVisible(AuthenticatedUser current, User user) {
        if (current.isAdmin()) {
            return;
        }
        if (!current.companyId().equals(user.getCompanyId())) {
            throw new ResourceNotFoundException("User", user.getId());
        }
    }

    /**
     * Firma koja se sprema uz novog korisnika, uz provjeru da je pozivatelj uopce
     * smije dodijeliti.
     *
     * @return firma novog korisnika (null samo za globalnog ADMIN-a)
     */
    private Long validatedCompany(AuthenticatedUser current, CreateUserRequest request) {
        if (!current.isAdmin()) {
            return companyForTenantAdmin(current, request);
        }

        // Uloga i firma moraju se slagati: korisnik bez firme ne bi mogao vidjeti nista,
        // a globalni ADMIN s firmom bi imao dva izvora tenanta (token i zaglavlje) koja
        // si proturjece.
        if (request.role() == Role.ADMIN) {
            if (request.companyId() != null) {
                throw new InvalidUserException("Globalni administrator ne pripada firmi.");
            }
            return null;
        }
        Long companyId = request.companyId();
        if (companyId == null) {
            throw new InvalidUserException("Korisnik mora pripadati firmi.");
        }
        // arhivirana firma se ovdje ponasa kao da je nema: racun u njoj se ne bi mogao ni
        // prijaviti, pa bi ga stvoriti znacilo samo tiho napraviti neupotrebljiv racun
        if (!companyRepository.existsByIdAndDeletedAtIsNull(companyId)) {
            throw new ResourceNotFoundException("Company", companyId);
        }
        return companyId;
    }

    /**
     * Administrator firme dodaje iskljucivo u SVOJU firmu i iskljucivo uloge koje ne
     * prelaze njegovu vlastitu.
     *
     * Firma se uzima iz tokena, a ne iz tijela: poslani {@code companyId} se prihvaca
     * samo ako se s njom poklapa (forma ga za ovu ulogu i ne salje), a tudi se odbija.
     * Da se tijelu vjerovalo, jedan zahtjev rucno sastavljen mimo sucelja bio bi dovoljan
     * da admin jedne firme otvori racun u drugoj.
     */
    private Long companyForTenantAdmin(AuthenticatedUser current, CreateUserRequest request) {
        if (request.role() == Role.ADMIN) {
            throw new ForbiddenException("Administrator firme ne može stvoriti globalnog administratora.");
        }
        Long requested = request.companyId();
        if (requested != null && !requested.equals(current.companyId())) {
            throw new ForbiddenException("Administrator firme može dodavati korisnike samo u svoju firmu.");
        }
        return current.companyId();
    }

    private long countAdmins() {
        return repository.findAll().stream().filter(user -> user.getRole() == Role.ADMIN).count();
    }

    /**
     * Naziv firme za prikaz. Namjerno gleda i arhivirane: korisnici arhivirane firme se i
     * dalje vide na popisu (postoje sve do praznjenja), pa bi im inace stupac "Firma" ostao
     * prazan - a prazno polje izgleda kao greska, ne kao "firma je arhivirana".
     */
    private String companyName(Long companyId) {
        if (companyId == null) {
            return null;
        }
        return companyRepository.findById(companyId).map(Company::getName).orElse(null);
    }
}

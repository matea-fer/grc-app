package com.example.demo.repository;

import com.example.demo.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Firme.
 *
 * Naslijedene metode ({@code findAll}, {@code findById}, {@code existsById}) vide I
 * arhivirane firme, i to je namjerno - ne zovu se svugdje s istom namjerom:
 *
 *  - gdje se odlucuje SMIJE LI SE nesto s firmom (prikaz na popisu, prolaz zahtjeva,
 *    dodavanje korisnika) ide inacica s {@code DeletedAtIsNull},
 *  - gdje se samo cita NAZIV vec postojeceg podatka (npr. firma korisnika u popisu, ili
 *    firma iz prijave) ide naslijedena metoda, jer bi inace naziv arhivirane firme nestao
 *    i redak bi ostao bez ijedne oznake kojoj firmi pripada.
 */
public interface CompanyRepository extends JpaRepository<Company, Long> {

    /** Aktivne firme - sve osim arhiviranih. */
    List<Company> findByDeletedAtIsNull();

    /** Arhivirane firme, najnovije arhivirane prvo. */
    List<Company> findByDeletedAtIsNotNullOrderByDeletedAtDesc();

    /** Firma koja jos nije arhivirana; prazno i kad firme nema i kad je arhivirana. */
    Optional<Company> findByIdAndDeletedAtIsNull(Long id);

    /** Postoji li i je li aktivna - za provjere gdje sam podatak o firmi ne treba. */
    boolean existsByIdAndDeletedAtIsNull(Long id);
}

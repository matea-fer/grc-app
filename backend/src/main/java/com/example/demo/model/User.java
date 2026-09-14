package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Korisnik koji se prijavljuje u aplikaciju.
 *
 * Tablica se zove {@code app_user} jer je {@code user} rezervirana rijec u Postgresu.
 *
 * Veza prema firmi je vise-na-jedan i namjerno je ogranicena samo u jednom smjeru:
 * firma moze imati koliko god korisnika, ali korisnik ne moze preskakati izmedu firmi.
 * Zato je {@code companyId} obicno polje, a ne popis - da izolacija po firmi ostane
 * ista provjera kao dosad.
 *
 * Lozinka se cuva iskljucivo kao BCrypt hash; cisti tekst ne ulazi ni u jedan stupac
 * ni u jedan DTO koji izlazi iz aplikacije.
 */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // null samo za ADMIN-a - on nije vezan uz firmu; za USER-a je obavezan
    @Column(name = "company_id")
    private Long companyId;

    public User() {
    }

    public User(String username, String passwordHash, Role role, Long companyId) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.companyId = companyId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }
}

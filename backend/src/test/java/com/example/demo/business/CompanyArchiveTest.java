package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CompanyResponse;
import com.example.demo.dto.CreateCompanyRequest;
import com.example.demo.exception.InvalidCompanyException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Company;
import com.example.demo.model.Role;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.service.CompanyPurgeService;
import com.example.demo.service.CompanyService;
import com.example.demo.service.LogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - brisanje firme je dvokoracno i prvi je korak povratan.
 *
 * Ono sto se ovdje stvarno provjerava nije da metode rade, nego da se ta DVA koraka ne mogu
 * stopiti u jedan: aktivna se firma ne moze isprazniti, a arhivirana se ne moze vratiti u
 * upotrebu slucajno. Sve ostalo (koliko se cega obrisalo) je posao {@code CompanyPurgeService},
 * koji je ovdje mock.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class CompanyArchiveTest {

    private static final Long COMPANY = 7L;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private LogService logService;

    @Mock
    private CompanyPurgeService purgeService;

    private final AuthContext authContext = new AuthContext();

    @BeforeEach
    void loggedInAsAdmin() {
        authContext.set(new AuthenticatedUser(1L, "admin", Role.ADMIN, null));
    }

    @AfterEach
    void clearContext() {
        authContext.clear();
    }

    private CompanyService service() {
        return new CompanyService(companyRepository, authContext, logService, purgeService);
    }

    private static Company active() {
        Company company = new Company("Acme");
        company.setId(COMPANY);
        return company;
    }

    private static Company archived() {
        Company company = active();
        company.setDeletedAt(Instant.parse("2026-08-10T09:00:00Z"));
        return company;
    }

    @Test
    @DisplayName("brisanje firme je samo arhiviranje - redak ostaje, dobiva vrijeme")
    void deleteOnlyArchives() {
        Company company = active();
        when(companyRepository.findByIdAndDeletedAtIsNull(COMPANY)).thenReturn(Optional.of(company));

        service().delete(COMPANY);

        assertThat(company.getDeletedAt()).isNotNull();
        verify(companyRepository).save(company);
        // ovo je cijela poanta: sam redak firme se NE brise
        verify(companyRepository, never()).delete(any());
        verify(purgeService, never()).purge(any());
    }

    @Test
    @DisplayName("vec arhivirana firma se ne moze arhivirati drugi put - 404, kao da je nema")
    void alreadyArchivedCannotBeArchivedAgain() {
        // arhivirana firma kroz findByIdAndDeletedAtIsNull ne izlazi
        when(companyRepository.findByIdAndDeletedAtIsNull(COMPANY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(COMPANY))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("vracanje brise vrijeme arhiviranja i firma se ponovno pojavljuje na popisu")
    void restoreClearsTheTimestamp() {
        Company company = archived();
        when(companyRepository.findById(COMPANY)).thenReturn(Optional.of(company));
        when(companyRepository.save(company)).thenReturn(company);

        CompanyResponse restored = service().restore(COMPANY);

        assertThat(company.getDeletedAt()).isNull();
        assertThat(restored.deletedAt()).isNull();
    }

    @Test
    @DisplayName("aktivna firma se ne moze vratiti iz arhive - nije u njoj")
    void activeCompanyCannotBeRestored() {
        when(companyRepository.findById(COMPANY)).thenReturn(Optional.of(active()));

        assertThatThrownBy(() -> service().restore(COMPANY))
                .isInstanceOf(InvalidCompanyException.class);
    }

    @Test
    @DisplayName("AKTIVNA firma se ne moze trajno isprazniti - mora se prvo arhivirati")
    void activeCompanyCannotBePurged() {
        when(companyRepository.findById(COMPANY)).thenReturn(Optional.of(active()));

        assertThatThrownBy(() -> service().purge(COMPANY))
                .isInstanceOf(InvalidCompanyException.class);

        // nista se nije ni pocelo brisati
        verify(purgeService, never()).purge(any());
        verify(companyRepository, never()).delete(any());
    }

    @Test
    @DisplayName("praznjenje arhivirane firme uklanja podatke pa i sam redak firme")
    void purgeRemovesDataAndTheCompanyRow() {
        Company company = archived();
        when(companyRepository.findById(COMPANY)).thenReturn(Optional.of(company));
        when(purgeService.purge(COMPANY))
                .thenReturn(new CompanyPurgeService.PurgeSummary(2, 5, 1, 1, 3, 9, 40));

        service().purge(COMPANY);

        verify(purgeService).purge(COMPANY);
        verify(companyRepository).delete(company);
    }

    @Test
    @DisplayName("popis aktivnih ne sadrzi arhivirane, i obrnuto - to su dva odvojena upita")
    void activeAndArchivedAreSeparateLists() {
        when(companyRepository.findByDeletedAtIsNull()).thenReturn(List.of(active()));
        when(companyRepository.findByDeletedAtIsNotNullOrderByDeletedAtDesc())
                .thenReturn(List.of(archived()));

        assertThat(service().getAll()).extracting(CompanyResponse::deletedAt).containsOnlyNulls();
        assertThat(service().getArchived()).extracting(CompanyResponse::deletedAt).doesNotContainNull();
    }

    @Test
    @DisplayName("arhivirana firma se ne preimenuje - naziv je ono po cemu se prepoznaje u arhivi")
    void archivedCompanyCannotBeRenamed() {
        when(companyRepository.findByIdAndDeletedAtIsNull(COMPANY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().update(COMPANY, new CreateCompanyRequest("Novo ime")))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

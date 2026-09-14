package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CreateCompanyRequest;
import com.example.demo.model.Company;
import com.example.demo.model.LogEntry;
import com.example.demo.model.Role;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.LogEntryRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.CompanyPurgeService;
import com.example.demo.service.CompanyService;
import com.example.demo.service.LogService;
import com.example.demo.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - svaka zabiljezena akcija mora nositi TKO i NAD CIJOM FIRMOM.
 *
 * Drugi dio je zamka koju je lako previdjeti: CRUD firmi se izvrsava izvan tenant
 * konteksta, pa bi zapis o stvaranju firme dobio {@code companyId = null} i ne bi se
 * pojavio ni u cijem dnevniku. Zato se vezuje uz firmu koju dira.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class AuditIdentityTest {

    @Mock
    private LogEntryRepository logRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CompanyPurgeService purgeService;

    private final TenantContext tenantContext = new TenantContext();
    private final AuthContext authContext = new AuthContext();

    @AfterEach
    void clearContexts() {
        tenantContext.clear();
        authContext.clear();
    }

    private LogService logService() {
        return new LogService(logRepository, tenantContext, authContext);
    }

    private CompanyService companyService() {
        return new CompanyService(companyRepository, authContext, logService(), purgeService);
    }

    private LogEntry captureSaved() {
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("zapis o izmjeni nosi i korisnika i firmu")
    void entryCarriesBothUserAndCompany() {
        tenantContext.set(7L);
        authContext.set(new AuthenticatedUser(2L, "ana", Role.USER, 7L));

        logService().record("RECORD_UPDATED", "Zapis id=5 u obrascu id=2");

        LogEntry saved = captureSaved();
        assertThat(saved.getUsername()).isEqualTo("ana");
        assertThat(saved.getCompanyId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("stvaranje firme se biljezi u dnevnik TE firme, iako se izvrsava izvan tenant konteksta")
    void companyCreationIsRecordedAgainstTheNewCompany() {
        authContext.set(new AuthenticatedUser(1L, "admin", Role.ADMIN, null));
        // tenantContext je namjerno prazan - ADMIN nije "u" firmi dok je stvara
        when(companyRepository.save(any())).thenAnswer(invocation -> {
            Company company = invocation.getArgument(0);
            company.setId(9L);
            return company;
        });

        companyService().create(new CreateCompanyRequest("Nova"));

        LogEntry saved = captureSaved();
        assertThat(saved.getAction()).isEqualTo("COMPANY_CREATED");
        assertThat(saved.getUsername()).isEqualTo("admin");
        // bez eksplicitnog vezanja ovdje bi stajao null i zapis bi bio nevidljiv
        assertThat(saved.getCompanyId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("arhiviranje firme se biljezi u NJEZIN dnevnik, a korisnici ostaju")
    void companyArchivingKeepsUsersAndIsRecorded() {
        authContext.set(new AuthenticatedUser(1L, "admin", Role.ADMIN, null));
        Company company = new Company("Acme");
        company.setId(7L);
        when(companyRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(company));

        companyService().delete(7L);

        // korisnici arhivirane firme se NE brisu - bez njih vracanje ne bi vratilo firmu
        verify(userRepository, never()).deleteAll(any());
        assertThat(company.getDeletedAt()).isNotNull();
        LogEntry saved = captureSaved();
        assertThat(saved.getAction()).isEqualTo("COMPANY_ARCHIVED");
        assertThat(saved.getCompanyId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("trajno praznjenje se biljezi BEZ firme - firme vise nema")
    void companyPurgeIsRecordedWithoutCompany() {
        authContext.set(new AuthenticatedUser(1L, "admin", Role.ADMIN, null));
        Company company = new Company("Acme");
        company.setId(7L);
        company.setDeletedAt(Instant.now());
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
        when(purgeService.purge(7L))
                .thenReturn(new CompanyPurgeService.PurgeSummary(1, 2, 3, 0, 1, 7, 12));

        companyService().purge(7L);

        LogEntry saved = captureSaved();
        assertThat(saved.getAction()).isEqualTo("COMPANY_PURGED");
        // vezan uz firmu, zapis bi bio jedini redak koji pokazuje na nepostojeci id
        assertThat(saved.getCompanyId()).isNull();
        assertThat(saved.getDetail()).contains("Acme").contains("12 zapis(a) dnevnika zadrzano");
    }
}

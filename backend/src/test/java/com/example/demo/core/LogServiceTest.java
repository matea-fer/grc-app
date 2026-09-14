package com.example.demo.core;

import com.example.demo.auth.AuthContext;
import com.example.demo.model.LogEntry;
import com.example.demo.repository.LogEntryRepository;
import com.example.demo.service.LogService;
import com.example.demo.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/**
 * CORE test - LogService: upisuje tko (AuthContext) i nad cijom firmom (TenantContext),
 * i kao best-effort audit se ne rusi ako upis zakaze.
 */
@Tag("core")
@ExtendWith(MockitoExtension.class)
class LogServiceTest {

    @Mock
    private LogEntryRepository repository;

    @Mock
    private TenantContext tenantContext;

    @Mock
    private AuthContext authContext;

    @InjectMocks
    private LogService service;

    private LogEntry captureSaved() {
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("record sprema zapis s firmom i korisnikom iz konteksta")
    void recordSavesEntryWithTenantAndUserFromContext() {
        when(tenantContext.get()).thenReturn(3L);
        when(authContext.username()).thenReturn("ana");

        service.record("TEMPLATE_CREATED", "Obrazac \"Anketa\" (id=5)");

        LogEntry saved = captureSaved();
        assertThat(saved.getCompanyId()).isEqualTo(3L);
        assertThat(saved.getUsername()).isEqualTo("ana");
        assertThat(saved.getAction()).isEqualTo("TEMPLATE_CREATED");
        assertThat(saved.getDetail()).isEqualTo("Obrazac \"Anketa\" (id=5)");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("recordForCompany vezuje zapis uz zadanu firmu, a korisnika i dalje uzima iz konteksta")
    void recordForCompanyUsesExplicitCompany() {
        when(authContext.username()).thenReturn("admin");

        service.recordForCompany(9L, "COMPANY_CREATED", "Firma \"Nova\" (id=9)");

        LogEntry saved = captureSaved();
        // firma dolazi iz argumenta, ne iz konteksta - CRUD firmi se izvrsava izvan njega
        assertThat(saved.getCompanyId()).isEqualTo(9L);
        assertThat(saved.getUsername()).isEqualTo("admin");
    }

    @Test
    @DisplayName("record s eksplicitnim korisnikom radi i kad konteksta uopce nema - prijava")
    void recordWithExplicitUserWorksWithoutContext() {
        service.record("LOGIN_FAILED", "Nepoznato korisnicko ime \"tko\"", null, "tko");

        LogEntry saved = captureSaved();
        assertThat(saved.getCompanyId()).isNull();
        assertThat(saved.getUsername()).isEqualTo("tko");
        assertThat(saved.getAction()).isEqualTo("LOGIN_FAILED");
    }

    @Test
    @DisplayName("record ne baca ako upis zakaže - audit ne smije oboriti akciju")
    void recordSwallowsRepositoryFailure() {
        when(tenantContext.get()).thenReturn(3L);
        when(repository.save(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("baza pukla"));

        assertThatCode(() -> service.record("COLUMN_ADDED", "Stupac \"grad\"")).doesNotThrowAnyException();
    }
}

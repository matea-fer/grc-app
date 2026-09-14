package com.example.demo.core;

import com.example.demo.dto.LogEntryResponse;
import com.example.demo.dto.PageResponse;
import com.example.demo.model.LogEntry;
import com.example.demo.repository.LogEntryRepository;
import com.example.demo.service.LogQueryService;
import com.example.demo.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CORE test - citanje dnevnika: samo svoja firma, po stranicama, od odabranog datuma. */
@Tag("core")
@ExtendWith(MockitoExtension.class)
class LogQueryServiceTest {

    @Mock
    private LogEntryRepository repository;

    @Mock
    private TenantContext tenantContext;

    @InjectMocks
    private LogQueryService service;

    /** Sto repozitorij vrati - jedna stranica sa zadanim zapisima i ukupnim brojem. */
    private void repositoryReturns(List<LogEntry> entries, long total) {
        Page<LogEntry> page = new PageImpl<>(entries, PageRequest.of(0, LogQueryService.PAGE_SIZE), total);
        when(repository.search(eq(3L), any(), any(), any())).thenReturn(page);
    }

    private Instant capturedFrom() {
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).search(eq(3L), captor.capture(), any(), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("dohvaca dnevnik firme iz konteksta i mapira ga u odgovor")
    void returnsEntriesForCurrentCompany() {
        when(tenantContext.require()).thenReturn(3L);
        repositoryReturns(List.of(new LogEntry(3L, "ana", "RECORD_CREATED", "Zapis id=1", Instant.now())), 1);

        PageResponse<LogEntryResponse> page = service.getForCurrentCompany(0, null, null);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().username()).isEqualTo("ana");
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("nikad ne vraca cijeli dnevnik - upit ide po stranici zadane velicine")
    void queryIsPaged() {
        when(tenantContext.require()).thenReturn(3L);
        repositoryReturns(List.of(), 0);

        service.getForCurrentCompany(2, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(eq(3L), any(), any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(LogQueryService.PAGE_SIZE);
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
    }

    /**
     * Bez datuma se gleda sve. EPOCH je ovdje "od pocatka vremena" - namjerno, da postoji
     * samo JEDAN oblik upita umjesto dva koja se moraju drzati usklađena.
     */
    @Test
    @DisplayName("bez odabranog datuma se gleda cijeli dnevnik")
    void withoutDateReadsEverything() {
        when(tenantContext.require()).thenReturn(3L);
        repositoryReturns(List.of(), 0);

        service.getForCurrentCompany(0, null, null);

        assertThat(capturedFrom()).isEqualTo(Instant.EPOCH);
    }

    /**
     * Zamka zbog koje ovaj test postoji: {@code created_at} je trenutak (UTC), a korisnik bira
     * LOKALNI datum. Da se granica racuna kao ponoc po UTC-u, odabir 1.8. bi ljeti povukao i
     * zapise od 31.7. poslije 22 sata - dnevnik bi vukao dan koji nitko nije trazio.
     */
    @Test
    @DisplayName("odabrani datum pocinje u nasoj vremenskoj zoni, ne u UTC-u")
    void dateStartsInLocalZone() {
        when(tenantContext.require()).thenReturn(3L);
        repositoryReturns(List.of(), 0);

        service.getForCurrentCompany(0, LocalDate.of(2026, 8, 1), null);

        assertThat(capturedFrom())
                .isEqualTo(LocalDate.of(2026, 8, 1).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /**
     * Filtar po vrsti akcije ide na server. Da ostane u pregledniku, sa stranicenjem bi
     * prosijao samo trenutnu stranicu - i tvrdio da starijih prijava nema.
     */
    @Test
    @DisplayName("odabrana vrsta akcije putuje u upit, a prazan izbornik znaci sve")
    void actionFilterGoesToQuery() {
        when(tenantContext.require()).thenReturn(3L);
        repositoryReturns(List.of(), 0);

        service.getForCurrentCompany(0, null, "RECORD_LOCKED");
        verify(repository).search(eq(3L), any(), eq("RECORD_LOCKED"), any());

        service.getForCurrentCompany(0, null, "   ");
        verify(repository).search(eq(3L), any(), isNull(), any());
    }

    @Test
    @DisplayName("negativna stranica se cita kao prva, umjesto da upit padne")
    void negativePageFallsBackToFirst() {
        when(tenantContext.require()).thenReturn(3L);
        repositoryReturns(List.of(), 0);

        assertThat(service.getForCurrentCompany(-3, null, null).page()).isZero();
    }
}

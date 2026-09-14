import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { LogEntry } from '../log/log.model';
import { LogService } from '../log/log.service';
import { TenantService } from '../tenant/tenant.service';

// Oznaka akcije je strojno čitljiva (COLUMN_ADDED); ovdje dobiva ljudski naziv.
// Nepoznata oznaka se prikaže kakva jest - bolje sirovo nego prazno.
const ACTION_LABELS: Record<string, string> = {
  LOGIN_SUCCESS: 'Prijava',
  LOGIN_FAILED: 'Neuspjela prijava',
  USER_CREATED: 'Korisnik dodan',
  USER_DELETED: 'Korisnik obrisan',
  COMPANY_CREATED: 'Firma stvorena',
  COMPANY_RENAMED: 'Firma preimenovana',
  COMPANY_DELETED: 'Firma obrisana',
  TEMPLATE_CREATED: 'Obrazac stvoren',
  TEMPLATE_RENAMED: 'Obrazac preimenovan',
  TEMPLATE_DELETED: 'Obrazac obrisan',
  CODEBOOK_CREATED: 'Šifrarnik stvoren',
  CODEBOOK_RENAMED: 'Šifrarnik preimenovan',
  CODEBOOK_DELETED: 'Šifrarnik obrisan',
  CODEBOOK_ITEMS_SAVED: 'Stavke šifrarnika spremljene',
  COLUMN_ADDED: 'Stupac dodan',
  COLUMN_UPDATED: 'Stupac izmijenjen',
  COLUMN_REMOVED: 'Stupac uklonjen',
  RECORD_CREATED: 'Zapis dodan',
  RECORD_UPDATED: 'Zapis izmijenjen',
  RECORD_DELETED: 'Zapis obrisan',
  RECORD_LOCKED: 'Zapis zaključan',
  RECORD_UNLOCKED: 'Zapis otključan',
  ATTACHMENT_ADDED: 'Datoteka priložena',
  ATTACHMENT_REMOVED: 'Datoteka uklonjena'
};

/** Mora se slagati s `LogQueryService.PAGE_SIZE`; ovdje služi samo za ispis raspona. */
const PAGE_SIZE = 50;

/**
 * Dnevnik akcija aktivne firme - samo čitanje, najnovije prvo.
 *
 * Bilježe se izmjene i prijave, nikad čitanja: čitanja bi brojčano zdrobila sve
 * ostalo i ekran bi prestao biti upotrebljiv.
 *
 * Od 12.08.2026. se lista po stranicama i bira datum OD kojeg se gleda, pa dnevnik ima
 * odgovor i na "što se događalo prošli mjesec". Filtriranje radi baza: da ostane u
 * pregledniku, prosijalo bi samo trenutnu stranicu i tvrdilo da starijeg nema.
 */
@Component({
  selector: 'app-audit-log',
  imports: [FormsModule],
  templateUrl: './audit-log.html',
  styleUrl: './audit-log.css'
})
export class AuditLog {
  private readonly logService = inject(LogService);
  private readonly tenantService = inject(TenantService);

  protected readonly activeCompanyId = this.tenantService.activeCompanyId;

  protected readonly entries = signal<LogEntry[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly actionFilter = signal<string>('');
  /** Datum OD kojeg se gleda, kao `yyyy-MM-dd`; prazno znači „sve od početka". */
  protected readonly fromDate = signal<string>('');

  protected readonly page = signal(0);
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(0);

  /**
   * Ponuda za filtar je STALAN popis poznatih akcija, a ne ono što je viđeno u podacima.
   *
   * Dok se vraćao cijeli dnevnik, popis se dao izvesti iz njega. Sa stranicama bi se izvodio
   * iz 50 zapisa koji su trenutno na ekranu - pa bi izbornik nudio samo ono što se ionako već
   * vidi, a nestajalo bi listanjem.
   */
  protected readonly knownActions = Object.keys(ACTION_LABELS).sort((a, b) =>
    ACTION_LABELS[a].localeCompare(ACTION_LABELS[b], 'hr')
  );

  protected readonly rangeFrom = computed(() =>
    this.totalElements() === 0 ? 0 : this.page() * PAGE_SIZE + 1
  );
  protected readonly rangeTo = computed(() =>
    Math.min((this.page() + 1) * PAGE_SIZE, this.totalElements())
  );

  constructor() {
    effect(() => {
      const companyId = this.activeCompanyId();
      /*
       * `load` ide kroz untracked jer čita stranicu, datum i akciju - a efekt pamti sve što
       * pročita. Bez ovoga bi promjena datuma pokrenula efekt, pa bi se dnevnik dohvaćao
       * dvaput za jedan klik (isti kvar kao na ekranu „Podaci", gdje je poništavao i odabir
       * stranice).
       */
      untracked(() => {
        if (companyId === null) {
          this.entries.set([]);
        } else {
          this.load();
        }
      });
    });
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.logService.getLogs(this.page(), this.fromDate() || null, this.actionFilter() || null).subscribe({
      next: (result) => {
        this.entries.set(result.content);
        this.totalElements.set(result.totalElements);
        this.totalPages.set(result.totalPages);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Dohvaćanje dnevnika nije uspjelo.');
        this.loading.set(false);
      }
    });
  }

  /**
   * Promjena filtra vraća na prvu stranicu - inače korisnik koji je na stranici 7 suzi
   * pretragu na tri zapisa gleda praznu stranicu i zaključi da filtar ne radi.
   */
  protected onFilterChange(): void {
    this.page.set(0);
    this.load();
  }

  protected goToPage(page: number): void {
    if (page < 0 || page >= this.totalPages() || page === this.page()) {
      return;
    }
    this.page.set(page);
    this.load();
  }

  protected actionLabel(action: string): string {
    return ACTION_LABELS[action] ?? action;
  }

  protected formatTime(createdAt: string): string {
    return new Date(createdAt).toLocaleString('hr-HR');
  }
}

import { Component, computed, effect, inject, input, output, signal } from '@angular/core';

import { ColumnDefinitionService } from '../column-definition/column-definition.service';
import { RelatedGroup, Survey } from '../survey/survey.model';
import { SurveyService } from '../survey/survey.service';
import { SchemaField, cellText, toSchemaField } from './field';

/**
 * Dijalog „Povezani zapisi": sve što pokazuje NA ovaj zapis, iz bilo kojeg obrasca.
 *
 * Čita vezu UNATRAG. Smjer naprijed već pokazuje sama ćelija referentnog stupca („ovaj rizik
 * pripada procesu Nabava"), pa bi ondje gumb bio suvišan. Obrnuto pitanje — „što sve visi o
 * ovom procesu" — se drukčije ne može dobiti, jer vezu drži samo dijete.
 *
 * Ništa se ne konfigurira. Koji stupci na ovaj obrazac pokazuju već piše u shemama, pa server
 * sam nađe skupine; gumb u shemi nosi samo natpis.
 *
 * Dohvat ide u dva koraka i oba su lijena. Na otvaranje se pita samo TKO pokazuje i koliko ih
 * je (jedan lagan poziv), a sami zapisi tek kad se skupina odabere. Bez toga bi klik povukao
 * zapise svih obrazaca koji na njega pokazuju, a gleda se najčešće jedan.
 */
@Component({
  selector: 'app-related-records-dialog',
  template: `
    <div class="modal-backdrop" (click)="closed.emit()">
      <div class="survey-form-card modal-dialog related-dialog" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h3>{{ title() }}</h3>
          <button type="button" class="icon-button" title="Zatvori" (click)="closed.emit()">✕</button>
        </div>

        @if (error(); as message) {
          <p class="error dialog-error">{{ message }}</p>
        } @else if (loadingGroups()) {
          <p class="field-hint">Učitavanje…</p>
        } @else if (groups().length === 0) {
          <p class="field-hint">Na ovaj zapis ne pokazuje nijedan zapis.</p>
        } @else {
          <!-- Dva smjera stoje odvojeno jer su dvije različite tvrdnje: „ovaj zapis pripada
               onome" i „ono pripada ovom zapisu". Spojeni u jedan red kartica ne bi se
               razlikovali, a čitaju se suprotno. -->
          @if (outgoing().length > 0) {
            <p class="related-heading">Pokazuje na</p>
            <div class="related-tabs">
              @for (group of outgoing(); track group.columnKey) {
                <button
                  type="button"
                  class="related-tab"
                  [class.related-tab-active]="isActive(group)"
                  (click)="selectGroup(group)"
                >
                  {{ group.columnLabel }}
                  <span class="related-tab-column">{{ group.templateName }}</span>
                  <span class="related-count">{{ group.total }}</span>
                </button>
              }
            </div>
          }

          @if (incoming().length > 0) {
            <p class="related-heading">Pokazuju na njega</p>
            <div class="related-tabs">
              @for (group of incoming(); track group.templateId + '|' + group.columnKey) {
                <button
                  type="button"
                  class="related-tab"
                  [class.related-tab-active]="isActive(group)"
                  (click)="selectGroup(group)"
                >
                  {{ group.templateName }}
                  <!-- isti obrazac može pokazivati ovamo kroz dva stupca (npr. Vlasnik i
                       Zamjenik), pa bi se bez stupca dvije kartice zvale isto -->
                  <span class="related-tab-column">{{ group.columnLabel }}</span>
                  <span class="related-count">{{ group.total }}</span>
                </button>
              }
            </div>
          }

          @if (loadingRecords()) {
            <p class="field-hint">Učitavanje…</p>
          } @else {
            <!-- Bez ovoga se ne da naslutiti da redak uopće nešto radi na klik - ista lekcija
                 kao sa strelicama na zaglavljima tablice. -->
            <p class="field-hint related-hint">Klik na redak otvara taj zapis u tablici.</p>
            <div class="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th class="related-id">#</th>
                    @for (field of shownFields(); track field.key) {
                      <th>{{ field.label }}</th>
                    }
                  </tr>
                </thead>
                <tbody>
                  @for (record of records(); track record.id) {
                    <tr
                      class="related-row"
                      [title]="'Otvori zapis #' + record.id + ' u tablici'"
                      (click)="open(record)"
                    >
                      <td class="related-id"><span class="related-open">#{{ record.id }} ↗</span></td>
                      @for (field of shownFields(); track field.key) {
                        <td>{{ cell(field, record) }}</td>
                      }
                    </tr>
                  }
                </tbody>
              </table>
            </div>

            @if (totalElements() > 0) {
              <div class="pager">
                <span class="pager-range">{{ rangeFrom() }}–{{ rangeTo() }} od {{ totalElements() }}</span>
                @if (totalPages() > 1) {
                  <button
                    type="button"
                    class="pager-button"
                    [disabled]="page() === 0"
                    (click)="goToPage(page() - 1)"
                  >‹</button>
                  <span class="pager-range">{{ page() + 1 }} / {{ totalPages() }}</span>
                  <button
                    type="button"
                    class="pager-button"
                    [disabled]="page() >= totalPages() - 1"
                    (click)="goToPage(page() + 1)"
                  >›</button>
                }
              </div>
            }
          }
        }

        <div class="form-actions">
          <button type="button" class="secondary" (click)="closed.emit()">Zatvori</button>
        </div>
      </div>
    </div>
  `,
  styles: `
    .related-dialog {
      max-width: 64rem;
      width: 100%;
    }

    .related-dialog .table-scroll {
      max-height: 22rem;
      overflow-y: auto;
    }

    .related-heading {
      margin: 0.25rem 0 0.35rem;
      color: var(--boja-tekst-prigusen);
      font-size: 0.85em;
      text-transform: uppercase;
      letter-spacing: 0.03em;
    }

    .related-tabs {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      margin-bottom: 0.75rem;
    }

    .related-tab {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      padding: 0.35rem 0.7rem;
      border: 1px solid var(--boja-rub-polja);
      border-radius: var(--radijus-pilula);
      background: var(--boja-povrsina);
      cursor: pointer;
    }

    .related-tab-active {
      border-color: var(--boja-akcent);
      background: var(--boja-akcent-tiha);
    }

    /* Iz kojeg stupca veza dolazi je pomoćni podatak - isti obrazac može pokazivati ovamo
       kroz dva različita stupca, pa se bez njega ne bi znalo koja je kartica koja. */
    .related-tab-column {
      color: var(--boja-tekst-prigusen);
      font-size: 0.85em;
    }

    .related-count {
      padding: 0 0.4rem;
      border-radius: var(--radijus-pilula);
      background: var(--boja-znacka);
      font-size: 0.85em;
    }

    .related-id {
      width: 4rem;
      color: var(--boja-tekst-prigusen);
    }

    .related-hint {
      margin: 0 0 0.35rem;
    }

    /* Cijeli redak je meta za klik - isto pravilo kao u dijalogu odabira zapisa. */
    .related-row {
      cursor: pointer;
    }

    .related-row:hover {
      background: var(--boja-akcent-tiha);
    }

    .related-open {
      color: var(--boja-akcent);
      text-decoration: underline;
      white-space: nowrap;
    }
  `
})
export class RelatedRecordsDialog {
  private readonly surveyService = inject(SurveyService);
  private readonly columnService = inject(ColumnDefinitionService);

  /** Obrazac zapisa nad kojim je gumb pritisnut. */
  readonly templateId = input.required<number>();
  readonly recordId = input.required<number>();
  readonly title = input('Povezani zapisi');

  readonly closed = output<void>();

  /**
   * Traži se prikaz jednog zapisa u samoj tablici.
   *
   * Dijalog ga zna pokazati, ali ne zna nad njim ništa napraviti - urediti, obrisati,
   * zaključiti ili dodati prilog može samo redak tablice. Zapis se pritom nije dao ni naći:
   * njegov broj nije stupac sheme, pa se po njemu ne da ni filtrirati.
   *
   * Šalje se i obrazac, jer povezani zapis najčešće pripada DRUGOM obrascu od onog koji je
   * na ekranu - tko to prima mora prvo prebaciti odabir.
   */
  readonly opened = output<{ templateId: number; recordId: number }>();

  /**
   * Koliko stupaca povezanog obrasca stane u dijalog.
   *
   * Dijalog je pregled, a ne drugi ekran s podacima: tko treba sve stupce, otvorit će taj
   * obrazac. Ograničenje čuva čitljivost - obrazac s tridesetak stupaca inače daje tablicu
   * koja se vodoravno pomiče više nego što se čita.
   */
  private static readonly MAX_COLUMNS = 5;

  protected readonly groups = signal<RelatedGroup[]>([]);
  protected readonly activeGroup = signal<RelatedGroup | null>(null);
  protected readonly records = signal<Survey[]>([]);
  protected readonly fields = signal<SchemaField[]>([]);
  protected readonly page = signal(0);
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly size = signal(0);
  protected readonly loadingGroups = signal(true);
  protected readonly loadingRecords = signal(false);
  protected readonly error = signal<string | null>(null);

  /**
   * Stupci koji se prikazuju: prvih nekoliko onih koji uopće nose vrijednost.
   *
   * Gumb i datoteka otpadaju jer u zapisu ne drže ništa - bili bi prazan stupac. Stupac koji
   * drži samu vezu natrag se izostavlja: u svakom retku piše isto, pa ne razlikuje ništa.
   */
  protected readonly shownFields = computed(() => {
    const group = this.activeGroup();
    // Stupac koji drži samu vezu natrag se izostavlja: u svakom retku piše isto, pa ne
    // razlikuje ništa. Vrijedi samo za dolazni smjer - kod izlaznog taj stupac pripada
    // NAŠEM obrascu, a ne onom koji se prikazuje.
    const linkKey = group?.direction === 'INCOMING' ? group.columnKey : null;
    return this.fields()
      .filter((field) => field.type !== 'button' && field.type !== 'file' && field.key !== linkKey)
      .slice(0, RelatedRecordsDialog.MAX_COLUMNS);
  });

  protected readonly outgoing = computed(() => this.groups().filter((g) => g.direction === 'OUTGOING'));
  protected readonly incoming = computed(() => this.groups().filter((g) => g.direction === 'INCOMING'));

  constructor() {
    effect(() => this.loadGroups(this.templateId(), this.recordId()));
  }

  protected isActive(group: RelatedGroup): boolean {
    const active = this.activeGroup();
    return (
      active !== null &&
      active.direction === group.direction &&
      active.templateId === group.templateId &&
      active.columnKey === group.columnKey
    );
  }

  protected rangeFrom(): number {
    return this.totalElements() === 0 ? 0 : this.page() * this.size() + 1;
  }

  protected rangeTo(): number {
    return Math.min((this.page() + 1) * this.size(), this.totalElements());
  }

  protected goToPage(page: number): void {
    const group = this.activeGroup();
    if (group === null) {
      return;
    }
    this.page.set(Math.max(page, 0));
    this.loadRecords(group, this.page());
  }

  /** Prelazak na drugu skupinu kreće od prve stranice - stranice se ne dijele među obrascima. */
  protected selectGroup(group: RelatedGroup): void {
    if (this.isActive(group)) {
      return;
    }
    this.activeGroup.set(group);
    this.page.set(0);
    this.loadSchema(group.templateId);
    this.loadRecords(group, 0);
  }

  /**
   * Otvori zapis u tablici.
   *
   * Obrazac se uzima iz samog ZAPISA, a ne iz odabrane skupine: to je isti broj, ali zapis o
   * sebi govori izravno, a skupina tek posredno (kod izlaznog smjera drži ciljani obrazac,
   * kod dolaznog obrazac djeteta).
   */
  protected open(record: Survey): void {
    this.opened.emit({ templateId: record.templateId, recordId: record.id });
  }

  /** Vrijednost u čitljivom obliku - isto pravilo kao u tablici zapisa. */
  protected cell(field: SchemaField, record: Survey): string {
    return cellText(field, record.data?.[field.key]);
  }

  private loadGroups(templateId: number, recordId: number): void {
    this.loadingGroups.set(true);
    this.surveyService.getRelated(templateId, recordId).subscribe({
      next: (groups) => {
        this.groups.set(groups);
        this.error.set(null);
        this.loadingGroups.set(false);
        // prva skupina se otvara sama: dijalog s karticama i praznom tablicom ispod traži
        // jedan klik koji ništa ne odlučuje
        if (groups.length > 0) {
          this.selectGroup(groups[0]);
        }
      },
      error: () => {
        this.error.set('Dohvaćanje povezanih zapisa nije uspjelo.');
        this.loadingGroups.set(false);
      }
    });
  }

  private loadSchema(templateId: number): void {
    this.columnService.getForTemplate(templateId).subscribe({
      // shema koja ne stigne nije razlog da se dijalog ne otvori: brojevi zapisa se i dalje
      // vide, a poruka o grešci pripada dohvatu samih zapisa
      next: (columns) => this.fields.set(columns.map(toSchemaField)),
      error: () => this.fields.set([])
    });
  }

  /**
   * Zapisi odabrane skupine.
   *
   * Dva smjera se traže na dva načina, i to nije nedosljednost nego razlika u pitanju.
   * Dolazni smjer je FILTAR („pokaži zapise čiji stupac X pokazuje na zapis 12") - isti onaj
   * koji korisnik dobije kad vezu odabere rukom. Izlazni smjer nije pitanje o sadržaju nego
   * popis: id-evi već stoje u podacima našeg retka, pa se traže baš oni.
   */
  private loadRecords(group: RelatedGroup, page: number): void {
    this.loadingRecords.set(true);
    const query =
      group.direction === 'OUTGOING'
        ? { ids: group.recordIds }
        : { filters: [{ column: group.columnKey, value: String(this.recordId()) }] };

    this.surveyService
      .getSurveys(group.templateId, { page, sortBy: null, sortDir: 'asc', ...query })
      .subscribe({
        next: (result) => {
          this.records.set(result.content);
          this.totalElements.set(result.totalElements);
          this.totalPages.set(result.totalPages);
          this.size.set(result.size);
          this.error.set(null);
          this.loadingRecords.set(false);
        },
        error: () => {
          this.error.set('Dohvaćanje povezanih zapisa nije uspjelo.');
          this.loadingRecords.set(false);
        }
      });
  }
}

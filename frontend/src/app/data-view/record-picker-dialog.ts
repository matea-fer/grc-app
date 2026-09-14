import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ColumnDefinitionService } from '../column-definition/column-definition.service';
import { RecordOption } from '../template/template.model';
import { TemplateService } from '../template/template.service';
import { ReferenceLabelService } from './reference-label.service';
import { SchemaField, cellText, toSchemaField } from './field';

/**
 * Dijalog „Odabir zapisa": bira se zapis DRUGOG obrasca na koji veza pokazuje.
 *
 * Zašto dijalog s tražilicom, a ne padajući izbornik. Šifrarnik ima desetak vrijednosti i
 * stane u izbornik; obrazac ima tisuće zapisa. Izbornik s tisuću stavki se ne da pregledati,
 * a i da se da - povukao bi sve zapise samo da bi se odabrao jedan.
 *
 * Pretraga ide na SERVER. Traži se po nazivu i po broju zapisa, jer korisnik zna ili jedno
 * ili drugo; upit se šalje 300 ms nakon zadnje tipke, kao i drugdje u aplikaciji.
 *
 * Odabrani zapis se odmah upisuje u međuspremnik naziva (`remember`), da ćelija ne mora
 * poslati novi zahtjev za nešto što je upravo pročitala.
 */
@Component({
  selector: 'app-record-picker-dialog',
  imports: [FormsModule],
  template: `
    <div class="modal-backdrop" (click)="closed.emit()">
      <div class="survey-form-card modal-dialog picker-dialog" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h3>{{ title() }}</h3>
          <button type="button" class="icon-button" title="Zatvori" (click)="closed.emit()">✕</button>
        </div>

        <!-- Radnje stoje GORE DESNO, iznad popisa: popis se pomiče unutar dijaloga, pa bi
             gumb ispod njega putovao s njim i nestajao iz vida. -->
        <div class="picker-actions">
          <button type="button" class="secondary" (click)="clear()">Očisti odabir</button>
          <button type="button" class="primary" (click)="confirm()">Gotovo</button>
        </div>

        <input
          type="search"
          class="picker-search"
          placeholder="Pretraži po nazivu ili broju zapisa…"
          [ngModel]="search()"
          (ngModelChange)="onSearch($event)"
        />

        @if (error(); as message) {
          <p class="error dialog-error">{{ message }}</p>
        } @else if (loading()) {
          <p class="field-hint">Učitavanje…</p>
        } @else {
          <div class="table-scroll picker-list">
            <table>
              <thead>
                <tr>
                  <th class="picker-pick"></th>
                  <th class="picker-id">#</th>
                  @for (field of shownFields(); track field.key) {
                    <!-- stupac koji se upisuje u ćeliju je obojen: u tablici zapisa poslije
                         stoji baš on, pa se u dijalogu mora znati koji je -->
                    <th [class.picker-display]="isDisplay(field)" [title]="columnTitle(field)">
                      {{ field.label }}
                    </th>
                  } @empty {
                    <!-- Dok shema ne stigne (ili ako ne stigne) ostaje sam naziv, onako kako je
                         dijalog izgledao i prije. Bez ovoga bi se vidjeli samo brojevi zapisa,
                         po kojima se ne bira ništa. -->
                    <th class="picker-display">Naziv</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (option of options(); track option.id) {
                  <tr [class.picker-row-chosen]="isChosen(option.id)" (click)="toggle(option)">
                    <td class="picker-pick">
                      <input
                        [type]="multiple() ? 'checkbox' : 'radio'"
                        name="record-picker"
                        [checked]="isChosen(option.id)"
                        (click)="$event.stopPropagation()"
                        (change)="toggle(option)"
                      />
                    </td>
                    <td class="picker-id">{{ option.id }}</td>
                    @for (field of shownFields(); track field.key) {
                      <td [class.picker-display]="isDisplay(field)">{{ cell(field, option) }}</td>
                    } @empty {
                      <td class="picker-display">{{ option.label }}</td>
                    }
                  </tr>
                } @empty {
                  <tr>
                    <td [attr.colspan]="(shownFields().length || 1) + 2" class="field-hint">
                      @if (search().trim() === '') {
                        Ovaj obrazac još nema zapisa.
                      } @else {
                        Nema zapisa za zadani upit.
                      }
                    </td>
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

      </div>
    </div>
  `,
  styles: `
    .picker-dialog {
      max-width: 44rem;
      width: 100%;
    }

    .picker-dialog .picker-list {
      max-height: 20rem;
      overflow-y: auto;
    }

    /* Cijeli redak je meta za klik - pogoditi mali radio gumb je nepotrebno precizan posao. */
    .picker-dialog tbody tr {
      cursor: pointer;
    }

    .picker-row-chosen {
      background: var(--boja-akcent-tiha);
    }

    .picker-pick {
      width: 2rem;
    }

    /* Broj zapisa je pomoćni podatak, pa je uži i tiši od naziva. */
    .picker-id {
      width: 4rem;
      color: var(--boja-tekst-prigusen);
    }

    /* Stupac koji ide u ćeliju. Ostatak retka je tu da se zapis prepozna, ali odabirom se
       sprema veza koja se POSLIJE prikazuje ovim stupcem - pa mora biti očit već ovdje. */
    .picker-display {
      color: var(--boja-akcent);
      font-weight: 600;
      background: var(--boja-akcent-tiha);
    }
  `
})
export class RecordPickerDialog {
  /**
   * Koliko se stupaca ciljanog obrasca pokazuje. Cijeli obrazac ih zna imati tridesetak, a
   * dijalog služi prepoznavanju zapisa, ne čitanju do kraja.
   */
  private static readonly MAX_COLUMNS = 6;

  private readonly templateService = inject(TemplateService);
  private readonly columnDefinitionService = inject(ColumnDefinitionService);
  private readonly labelService = inject(ReferenceLabelService);

  readonly templateId = input.required<number>();
  readonly displayKey = input.required<string>();
  readonly multiple = input(false);
  /** Trenutno odabrani zapisi - dijalog ih pokazuje označene i vraća izmijenjene. */
  readonly selected = input<number[]>([]);
  readonly title = input('Odabir zapisa');

  readonly confirmed = output<number[]>();
  readonly closed = output<void>();

  protected readonly search = signal('');
  protected readonly page = signal(0);
  protected readonly options = signal<RecordOption[]>([]);
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly size = signal(0);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** Radna kopija odabira: potvrđuje se tek na „Gotovo", pa se zatvaranjem ništa ne mijenja. */
  private readonly chosen = signal<number[]>([]);

  /** Stupci ciljanog obrasca - dohvaćaju se zasebno, jer ponuda nosi vrijednosti, ne shemu. */
  private readonly fields = signal<SchemaField[]>([]);

  /**
   * Stupci koji se stvarno prikazuju.
   *
   * Gumb i prilog otpadaju - u zapisu ne drže ništa. Stupac za naziv je zajamčen: kad bi
   * ispao izvan granice, dijalog bi skrivao baš ono što odabirom ulazi u ćeliju.
   */
  protected readonly shownFields = computed(() => {
    const usable = this.fields().filter((field) => field.type !== 'button' && field.type !== 'file');
    const shown = usable.slice(0, RecordPickerDialog.MAX_COLUMNS);
    const display = usable.find((field) => field.key === this.displayKey());
    if (display && !shown.includes(display)) {
      shown[shown.length - 1] = display;
    }
    return shown;
  });

  /** Tajmer odgode pretrage - upit se šalje tek kad korisnik prestane tipkati. */
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    // Početni odabir dolazi iz ulaza, pa se preuzima kad se dijalog otvori.
    effect(() => this.chosen.set([...this.selected()]));
    // Dohvat prati obrazac, stranicu i upit. Svi su signali, pa se ne mora ručno paziti
    // koji ga sve mora pokrenuti - to je i razlog zašto je ovdje efekt, a ne poziv u kodu.
    effect(() => this.load(this.templateId(), this.displayKey(), this.search(), this.page()));
    // Shema se dohvaća samo kad se obrazac promijeni, a ne uz svaku stranicu ili upit -
    // stupci se ne mijenjaju dok je dijalog otvoren.
    effect(() => this.loadFields(this.templateId()));
  }

  protected isDisplay(field: SchemaField): boolean {
    return field.key === this.displayKey();
  }

  protected columnTitle(field: SchemaField): string {
    return this.isDisplay(field) ? 'Ovaj stupac se prikazuje u ćeliji nakon odabira' : field.label;
  }

  protected cell(field: SchemaField, option: RecordOption): string {
    return cellText(field, option.data?.[field.key]);
  }

  private loadFields(templateId: number): void {
    this.columnDefinitionService.getForTemplate(templateId).subscribe({
      // Neuspjeh se ne prijavljuje kao greška dijaloga: bez stupaca se i dalje vidi broj i
      // naziv zapisa, dakle sve što je dijalog i prije pokazivao.
      next: (columns) => this.fields.set(columns.map(toSchemaField)),
      error: () => this.fields.set([])
    });
  }

  protected rangeFrom(): number {
    return this.totalElements() === 0 ? 0 : this.page() * this.size() + 1;
  }

  protected rangeTo(): number {
    return Math.min((this.page() + 1) * this.size(), this.totalElements());
  }

  protected isChosen(id: number): boolean {
    return this.chosen().includes(id);
  }

  /**
   * Uključi ili isključi zapis.
   *
   * Kod jednovrijednosne veze odabir zamjenjuje prethodni i dijalog se odmah zatvara: nema
   * se što više birati, a jedan klik manje je jedan klik manje.
   */
  protected toggle(option: RecordOption): void {
    this.labelService.remember(this.templateId(), this.displayKey(), option.id, option.label);
    if (!this.multiple()) {
      this.chosen.set([option.id]);
      this.confirm();
      return;
    }
    const current = this.chosen();
    this.chosen.set(
      current.includes(option.id) ? current.filter((id) => id !== option.id) : [...current, option.id]
    );
  }

  protected clear(): void {
    this.chosen.set([]);
  }

  protected confirm(): void {
    this.confirmed.emit(this.chosen());
  }

  protected goToPage(page: number): void {
    this.page.set(Math.max(page, 0));
  }

  /** Promjena upita vraća na prvu stranicu - inače bi ostala tražena stranica prošlog upita. */
  protected onSearch(text: string): void {
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
    this.searchTimer = setTimeout(() => {
      this.page.set(0);
      this.search.set(text);
    }, 300);
  }

  private load(templateId: number, displayKey: string, search: string, page: number): void {
    this.loading.set(true);
    this.templateService.options(templateId, displayKey, search, page).subscribe({
      next: (result) => {
        this.options.set(result.content);
        this.totalElements.set(result.totalElements);
        this.totalPages.set(result.totalPages);
        this.size.set(result.size);
        this.error.set(null);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Dohvaćanje zapisa nije uspjelo.');
        this.loading.set(false);
      }
    });
  }
}

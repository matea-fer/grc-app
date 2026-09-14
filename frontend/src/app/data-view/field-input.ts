import { Component, computed, inject, input, model, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { CodebookItem } from '../codebook/codebook.model';
import {
  FieldValue,
  RecordLink,
  SchemaField,
  asCodes,
  asIds,
  asLinks,
  isMultiValued,
  linkLabel
} from './field';
import { RecordLinksDialog } from './record-links-dialog';
import { RecordPickerDialog } from './record-picker-dialog';
import { ReferenceLabelService } from './reference-label.service';

/**
 * Jedna ponuđena vrijednost.
 *
 * `isNew` znači da je korisnik vrijednost upisao slobodno i da je u šifrarniku još nema -
 * ondje će je server napraviti pri spremanju zapisa. Do tada se prikazuje drukčije, da se
 * vidi razlika između onoga što je odabrano iz popisa i onoga što se tek dodaje.
 */
interface Choice {
  code: string;
  name: string;
  isNew: boolean;
}

/**
 * Jedno polje za unos, nacrtano prema tipu stupca.
 *
 * Postoji da izbor kontrole po tipu (tekst / broj / šifrarnik / datum / formula / gumb)
 * stoji na jednom mjestu: isti unos treba i dijalog za novi zapis, i dijalog za
 * uređivanje, i redak koji se dodaje izravno u tablici.
 *
 * Crta samo kontrolu, bez naziva polja - gdje naziv stoji (iznad polja u formi, u
 * zaglavlju stupca u tablici) odlučuje onaj tko komponentu koristi.
 *
 * Šifrarnički stupac ima tri načina odabira i sva tri nude ISTE stavke; razlikuju se
 * samo po tome koliko ih stane na ekran. Padajući izbornik je za kratke popise, potvrdni
 * okviri za one koje se želi vidjeti odjednom, a dijalog za duge - jedini ima tražilicu.
 */
@Component({
  selector: 'app-field-input',
  imports: [FormsModule, RecordLinksDialog, RecordPickerDialog],
  template: `
    @switch (control()) {
      @case ('number') {
        <input
          type="number"
          [step]="numberStep()"
          [attr.min]="field().options.min"
          [attr.max]="field().options.max"
          [ngModel]="value()"
          (ngModelChange)="value.set($event)"
        />
      }
      @case ('date') {
        <input type="date" [ngModel]="value()" (ngModelChange)="value.set($event)" />
      }
      @case ('datetime') {
        <input type="datetime-local" [ngModel]="value()" (ngModelChange)="value.set($event)" />
      }
      @case ('dropdown') {
        <select [ngModel]="value()" (ngModelChange)="value.set($event)">
          <option value="">-- odaberi --</option>
          @for (item of selectable(); track item.code) {
            <option [value]="item.code">{{ item.name }}</option>
          }
        </select>
      }
      @case ('free') {
        <!-- Upisuje se NAZIV, a ne šifra: šifru korisnik pri unosu zapisa uopće ne vidi.
             Popis ispod polja je datalist, a ne izbornik - upisati se smije i vrijednost
             koje u njemu nema, i upravo to je smisao ovog stupca. -->
        <input
          type="text"
          [attr.list]="listId()"
          placeholder="upiši ili odaberi"
          [ngModel]="freeText()"
          (ngModelChange)="value.set($event)"
        />
        <datalist [id]="listId()">
          @for (item of selectable(); track item.code) {
            <option [value]="item.name"></option>
          }
        </datalist>
      }
      @case ('checkbox') {
        <div class="choice-group">
          @for (choice of choices(); track choice.code) {
            <label class="choice">
              <input
                [type]="multiple() ? 'checkbox' : 'radio'"
                [name]="listId()"
                [checked]="isChosen(choice.code)"
                (change)="toggle(choice.code, $any($event.target).checked)"
              />
              <span [class.choice-new]="choice.isNew">{{ choice.name }}</span>
            </label>
          } @empty {
            <span class="field-hint">Šifrarnik nema ponuđenih vrijednosti.</span>
          }

          @if (allowsNew()) {
            <span class="new-value">
              <input
                type="text"
                placeholder="nova vrijednost…"
                [ngModel]="newValue()"
                (ngModelChange)="newValue.set($event)"
                (keyup.enter)="addNewValue()"
              />
              <button type="button" (click)="addNewValue()">Dodaj</button>
            </span>
          }
        </div>
      }
      @case ('popup') {
        <button type="button" class="picker-button" (click)="openPicker()">
          @if (chosen().length === 0) {
            <span class="field-hint">-- odaberi --</span>
          } @else {
            @for (item of chosen(); track item.code) {
              <span class="chip">{{ item.name }}</span>
            }
          }
        </button>

        @if (pickerOpen()) {
          <div class="modal-backdrop" (click)="closePicker()">
            <div class="survey-form-card modal-dialog picker-dialog" (click)="$event.stopPropagation()">
              <div class="modal-header">
                <h3>Odabir vrijednosti</h3>
                <button type="button" class="icon-button" title="Zatvori" (click)="closePicker()">✕</button>
              </div>

              <!-- Radnje stoje GORE DESNO, iznad popisa: popis se pomiče unutar dijaloga, pa
                   bi gumb ispod njega putovao s njim i nestajao iz vida. -->
              <div class="picker-actions">
                @if (multiple()) {
                  <button type="button" class="secondary" (click)="clearChoice()">Očisti odabir</button>
                }
                <button type="button" class="primary" (click)="closePicker()">Gotovo</button>
              </div>

              <input
                type="search"
                class="picker-search"
                placeholder="Pretraži…"
                [ngModel]="pickerSearch()"
                (ngModelChange)="pickerSearch.set($event)"
              />

              <div class="picker-list">
                @for (choice of pickerChoices(); track choice.code) {
                  <label class="choice">
                    <input
                      [type]="multiple() ? 'checkbox' : 'radio'"
                      [name]="listId() + '-popup'"
                      [checked]="isChosen(choice.code)"
                      (change)="toggle(choice.code, $any($event.target).checked)"
                    />
                    <span [class.choice-new]="choice.isNew">{{ choice.name }}</span>
                  </label>
                } @empty {
                  <span class="field-hint">Nema vrijednosti za zadani upit.</span>
                }
              </div>

              @if (allowsNew()) {
                <!-- Traženo se ovdje predlaže kao nova vrijednost: kad korisnik pretraži
                     šifrarnik i ne nađe svoje, upisao je upravo ono što želi dodati. -->
                <div class="new-value new-value-row">
                  <input
                    type="text"
                    placeholder="nova vrijednost…"
                    [ngModel]="newValue()"
                    (ngModelChange)="newValue.set($event)"
                    (keyup.enter)="addNewValue()"
                  />
                  <button type="button" (click)="addNewValue()">Dodaj</button>
                </div>
              }

            </div>
          </div>
        }
      }
      @case ('reference') {
        <!-- Veza se uvijek bira kroz dijalog: ciljani obrazac može imati tisuće zapisa, pa
             padajući izbornik nije opcija. Na gumbu stoje nazivi odabranih zapisa. -->
        <button type="button" class="picker-button" (click)="referenceOpen.set(true)">
          @if (referenceIds().length === 0) {
            <span class="field-hint">-- odaberi zapis --</span>
          } @else {
            @for (id of referenceIds(); track id) {
              <span class="chip">{{ referenceLabel(id) }}</span>
            }
          }
        </button>

        @if (referenceOpen() && field().options.targetTemplateId !== null) {
          <app-record-picker-dialog
            [templateId]="field().options.targetTemplateId!"
            [displayKey]="field().options.displayColumnKey ?? ''"
            [multiple]="multiple()"
            [selected]="referenceIds()"
            [title]="'Odabir zapisa: ' + field().label"
            (confirmed)="setReference($event)"
            (closed)="referenceOpen.set(false)"
          />
        }
      }
      @case ('link') {
        <!-- Isti dijalog kao u tablici, samo se promjena ovdje ne sprema nego čeka u obrascu -
             poveznica je obična vrijednost zapisa, pa ne traži da zapis već postoji (prilog
             traži). -->
        <button type="button" class="picker-button" (click)="linksOpen.set(true)">
          @if (links().length === 0) {
            <span class="field-hint">-- dodaj poveznicu --</span>
          } @else {
            @for (link of links(); track $index) {
              <span class="chip">{{ linkName(link) }}</span>
            }
          }
        </button>

        @if (linksOpen()) {
          <app-record-links-dialog
            [title]="field().options.buttonLabel || field().label"
            [links]="links()"
            (changed)="setLinks($event)"
            (closed)="linksOpen.set(false)"
          />
        }
      }
      @case ('formula') {
        <!-- vrijednost računa server pri spremanju; polje je ovdje samo da se vidi da
             stupac postoji i što u njemu piše -->
        <span class="computed-value">
          @if (value() === null || value() === '') {
            <span class="field-hint">računa se pri spremanju</span>
          } @else {
            {{ value() }}
          }
        </span>
      }
      @case ('button') {
        <button type="button" class="cell-button" (click)="pressed.set(true)">
          {{ field().options.buttonLabel || field().label }}
        </button>
        @if (pressed()) {
          <span class="field-hint">Ovaj gumb još nema pridruženu radnju.</span>
        }
      }
      @default {
        <input type="text" [ngModel]="value()" (ngModelChange)="value.set($event)" />
      }
    }
  `
})
export class FieldInput {
  private readonly labelService = inject(ReferenceLabelService);

  readonly field = input.required<SchemaField>();
  readonly value = model<FieldValue>(null);

  protected readonly pickerOpen = signal(false);
  protected readonly pickerSearch = signal('');
  protected readonly pressed = signal(false);

  /**
   * Koja se kontrola crta. Tip stupca sam po sebi ne odlučuje - šifrarnik ima tri
   * načina odabira, datum dva, a slobodan unos je opet nešto četvrto. Zato se izbor
   * računa jednom ovdje, umjesto da se isti uvjeti ponavljaju kroz predložak.
   */
  protected readonly control = computed<string>(() => {
    const field = this.field();
    switch (field.type) {
      case 'number':
        return 'number';
      case 'date':
        return field.options.dateMode === 'datetime' ? 'datetime' : 'date';
      case 'codebook': {
        // Način odabira odlučuje prvi; slobodan unos je DODATAK, a ne četvrti način -
        // i potvrdni okviri i dijalog ga nude kao polje uz popis.
        const mode = field.options.pickerMode ?? (field.options.multiple ? 'checkbox' : 'dropdown');
        if (mode === 'checkbox' || mode === 'popup') {
          return mode;
        }
        // Padajući izbornik sa slobodnim unosom nema smisla kao izbornik: kontrola u koju
        // se smije i upisati je polje s ponuđenim popisom ispod (datalist).
        return field.options.allowNewValues ? 'free' : 'dropdown';
      }
      case 'reference':
        return 'reference';
      case 'link':
        return 'link';
      case 'formula':
        return 'formula';
      case 'button':
        return 'button';
      default:
        return 'text';
    }
  });

  protected readonly allowsNew = computed(() => this.field().options.allowNewValues);

  /** Tekst upisan u polje za novu vrijednost, dok se ne potvrdi. */
  protected readonly newValue = signal('');

  protected readonly multiple = computed(() => isMultiValued(this.field()));

  /** Cijeli broj se ne unosi po desetinkama - korak to i govori kontroli. */
  protected readonly numberStep = computed(() => (this.field().options.numberMode === 'int' ? '1' : 'any'));

  /** Jedinstven u dokumentu, jer ga koriste datalist i grupe radio gumba. */
  protected readonly listId = computed(() => `field-${this.field().key.replace(/\W+/g, '-')}`);

  /**
   * Stavke koje se nude na izbor: aktivne, plus one koje su u ovom zapisu već upisane.
   *
   * Isključena stavka se namjerno ne nudi za nove unose, ali zapis koji je nosi mora se
   * moći otvoriti i spremiti bez tihe promjene vrijednosti - a padajući izbornik koji tu
   * šifru ne sadrži prikazao bi prazno i pri prvom spremanju je izbrisao.
   */
  protected readonly selectable = computed(() => {
    const current = asCodes(this.value());
    return this.field().items.filter((item) => item.active || current.includes(item.code));
  });

  /** Odabrane stavke, za prikaz na gumbu dijaloga. */
  protected readonly chosen = computed<CodebookItem[]>(() => {
    const codes = asCodes(this.value());
    return codes.map(
      (code) =>
        this.field().items.find((item) => item.code === code) ??
        // šifra bez stavke se i dalje pokazuje: u zapisu doista piše to
        ({ id: 0, code, name: code, active: false, sortOrder: 0 } satisfies CodebookItem)
    );
  });

  /**
   * Sve što se nudi na odabir: stavke šifrarnika plus vrijednosti koje je korisnik upisao
   * slobodno, a još nisu spremljene.
   *
   * Novoupisane moraju biti u popisu iako ih šifrarnik ne poznaje - inače bi se odabrale, a
   * odmah zatim nestale s ekrana, jer ih ne bi imalo što nacrtati kao označene.
   */
  protected readonly choices = computed<Choice[]>(() => {
    const items = this.field().items;
    const current = asCodes(this.value());
    const known = items
      .filter((item) => item.active || current.includes(item.code))
      .map((item) => ({ code: item.code, name: item.name, isNew: false }));
    const pending = current
      .filter((code) => !items.some((item) => item.code === code))
      .map((code) => ({ code, name: code, isNew: true }));
    return [...known, ...pending];
  });

  /** Popis u dijalogu, sužen tražilicom - dijalog i postoji zbog dugih šifrarnika. */
  protected readonly pickerChoices = computed(() => {
    const term = this.pickerSearch().trim().toLowerCase();
    const choices = this.choices();
    return term === '' ? choices : choices.filter((choice) => choice.name.toLowerCase().includes(term));
  });

  /**
   * Tekst u polju sa slobodnim unosom.
   *
   * U zapisu je ŠIFRA, a korisnik mora vidjeti naziv - inače bi mu se pri otvaranju
   * postojećeg zapisa u polju pojavilo "NOVA_FIRMA_D_O_O" umjesto onoga što je upisao.
   */
  protected readonly freeText = computed(() => {
    const value = this.value();
    if (typeof value !== 'string' || value === '') {
      return '';
    }
    return this.field().items.find((item) => item.code === value)?.name ?? value;
  });

  protected isChosen(code: string): boolean {
    return asCodes(this.value()).includes(code);
  }

  /**
   * Uključi ili isključi jednu šifru.
   *
   * Kod jednovrijednosnog stupca odabir zamjenjuje prethodni (radio gumbi to i rade sami),
   * kod višestrukog se popis dopunjuje. Redoslijed prati redoslijed u šifrarniku, a ne
   * redoslijed klikanja - inače bi isti odabir izgledao drukčije ovisno o tome kojim je
   * redom nastao.
   */
  protected toggle(code: string, checked: boolean): void {
    if (!this.multiple()) {
      this.value.set(checked ? code : '');
      return;
    }
    const current = asCodes(this.value());
    const next = checked
      ? (current.includes(code) ? current : [...current, code])
      : current.filter((chosen) => chosen !== code);
    this.value.set(this.inCodebookOrder(next));
  }

  /**
   * Odabrano poredano po redoslijedu iz šifrarnika, a novoupisano na kraju.
   *
   * Redoslijed prati šifrarnik, a ne redoslijed klikanja - inače bi isti odabir izgledao
   * drukčije ovisno o tome kojim je redom nastao. Vrijednosti kojih u šifrarniku još nema
   * se pritom NE smiju izgubiti: one su upravo ono što je korisnik dodao.
   */
  private inCodebookOrder(codes: string[]): string[] {
    const items = this.field().items;
    const known = items.filter((item) => codes.includes(item.code)).map((item) => item.code);
    const pending = codes.filter((code) => !items.some((item) => item.code === code));
    return [...known, ...pending];
  }

  /**
   * Potvrdi upisanu novu vrijednost.
   *
   * Ako upisano već postoji u šifrarniku - po nazivu ili po šifri - odabire se ta stavka
   * umjesto da nastane druga s istim značenjem. To je isto pravilo koje backend primjenjuje
   * u {@code ensureCode}; ovdje stoji da korisnik odmah vidi da je pogodio postojeću.
   *
   * Ono što je stvarno novo ostaje zapisano kao TEKST i putuje takvo do servera - šifru
   * dodjeljuje on, jer je korisnik pri unosu zapisa uopće ne vidi.
   */
  protected addNewValue(): void {
    const text = this.newValue().trim();
    if (text === '') {
      return;
    }
    const existing = this.field().items.find(
      (item) =>
        item.name.toLowerCase() === text.toLowerCase() || item.code.toLowerCase() === text.toLowerCase()
    );
    const chosen = existing?.code ?? text;

    if (this.multiple()) {
      const current = asCodes(this.value());
      if (!current.includes(chosen)) {
        this.value.set(this.inCodebookOrder([...current, chosen]));
      }
    } else {
      this.value.set(chosen);
    }
    this.newValue.set('');
  }

  protected clearChoice(): void {
    this.value.set(this.multiple() ? [] : '');
  }

  // --- veza na zapis drugog obrasca ---

  protected readonly referenceOpen = signal(false);
  protected readonly linksOpen = signal(false);

  /** Id-evi na koje polje pokazuje - jedan ili više, uvijek kao niz. */
  protected readonly referenceIds = computed(() => asIds(this.value()));

  /**
   * Naziv povezanog zapisa; dok se ne dohvati, stoji sam broj.
   *
   * Broj nije zamjena za naziv, ali jest istina o tome što u zapisu piše - a prazan čip bi
   * izgledao kao da veze nema.
   */
  protected referenceLabel(id: number): string {
    const targetTemplateId = this.field().options.targetTemplateId;
    const displayKey = this.field().options.displayColumnKey;
    if (targetTemplateId === null || !displayKey) {
      return `#${id}`;
    }
    return this.labelService.labelOf(targetTemplateId, displayKey, id) ?? `#${id}`;
  }

  /**
   * Zapiši odabir iz dijaloga.
   *
   * Jednovrijednosno polje drži sam broj, a ne niz od jednog člana: takav ga oblik očekuje i
   * backend, i po njemu se filtrira. Prazan odabir je prazan tekst, kao i kod ostalih polja -
   * tako ga provjera sheme prepozna kao „nepopunjeno".
   */
  /** Poveznice zapisa koji se upisuje - vrijednost je popis, pa se čita kroz `asLinks`. */
  protected readonly links = computed(() => asLinks(this.value()));

  protected linkName(link: RecordLink): string {
    return linkLabel(link);
  }

  /** Dijalog javlja nov popis; ovdje se samo upisuje u vrijednost polja, bez ijednog poziva. */
  protected setLinks(links: RecordLink[]): void {
    this.value.set(links);
  }

  protected setReference(ids: number[]): void {
    this.value.set(this.multiple() ? ids : (ids.length > 0 ? ids[0] : ''));
    this.referenceOpen.set(false);
  }

  protected openPicker(): void {
    this.pickerSearch.set('');
    this.pickerOpen.set(true);
  }

  protected closePicker(): void {
    this.pickerOpen.set(false);
  }
}

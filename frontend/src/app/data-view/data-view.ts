import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { Attachment } from '../attachment/attachment.model';
import { AttachmentService } from '../attachment/attachment.service';
import { AuthService } from '../auth/auth.service';
import { CodebookItem } from '../codebook/codebook.model';
import { CodebookService } from '../codebook/codebook.service';
import { ColumnDefinitionService } from '../column-definition/column-definition.service';
import { ColumnDefinition } from '../column-definition/column-definition.model';
import { TemplateService } from '../template/template.service';
import { TenantService } from '../tenant/tenant.service';
import { Survey, SurveyInput } from '../survey/survey.model';
import { SurveyFilter, SurveyService } from '../survey/survey.service';
import { AttachmentCell } from './attachment-cell';
import { FieldInput } from './field-input';
import { RecordHistoryDialog } from './record-history-dialog';
import { RecordPickerDialog } from './record-picker-dialog';
import { ReferenceLabelService } from './reference-label.service';
import { RecordLinksDialog } from './record-links-dialog';
import { RelatedRecordsDialog } from './related-records-dialog';
import {
  FieldValue,
  SchemaField,
  asCodes,
  asIds,
  RecordLink,
  asLinks,
  codebookLabel,
  formatDateTime,
  formatNumber,
  initialValue,
  isEmptyValue,
  isMultiValued,
  sameValue,
  toSchemaField,
  withItems
} from './field';

/**
 * Koliko redaka server šalje po stranici.
 *
 * Mora se slagati s `SurveyResultService.PAGE_SIZE` na backendu; server je taj koji reže, a
 * ovdje se broj koristi samo za ispis „1–50 od 1.234”. Da se razlikuju, ispis bi lagao.
 */
const PAGE_SIZE = 50;

/**
 * Podaci (prikaz instance) - zapisi ODABRANOG OBRASCA (templatea) prema njegovoj shemi.
 *
 * Firma dolazi iz gornje trake (TenantID), a obrazac se bira ovdje (dijeli se s
 * Editorom kroz {@link TemplateService}). Backend vraća i sprema samo zapise tog
 * obrasca. Dok firma ili obrazac nisu odabrani, ekran traži odabir.
 *
 * Zapis se može dodati na dva načina: kroz dijalog (cijela forma na okupu) ili
 * kao prazan redak umetnut izravno u tablicu, ispod onog pored kojeg se krenulo.
 * Oba puta završavaju u istom POST-u; razlikuju se samo po tome gdje se upisuje.
 */
@Component({
  selector: 'app-data-view',
  imports: [
    FormsModule,
    FieldInput,
    AttachmentCell,
    RecordHistoryDialog,
    RecordLinksDialog,
    RelatedRecordsDialog,
    RecordPickerDialog
  ],
  templateUrl: './data-view.html',
  styleUrl: './data-view.css',
  host: {
    // dijalog se mora dati zatvoriti i tipkovnicom, ne samo mišem
    '(document:keydown.escape)': 'onEscape()'
  }
})
export class DataView {
  private readonly surveyService = inject(SurveyService);
  private readonly columnDefinitionService = inject(ColumnDefinitionService);
  private readonly codebookService = inject(CodebookService);
  private readonly attachmentService = inject(AttachmentService);
  private readonly templateService = inject(TemplateService);
  private readonly tenantService = inject(TenantService);
  private readonly authService = inject(AuthService);
  private readonly labelService = inject(ReferenceLabelService);

  protected readonly activeCompanyId = this.tenantService.activeCompanyId;
  protected readonly templates = this.templateService.templates;
  protected readonly activeTemplateId = this.templateService.activeTemplateId;

  /**
   * Smije li otključati zapis - administrator, globalni ili firmin.
   *
   * Sučelje po ovome samo odlučuje što nacrtati; pravu odluku donosi backend, koji običnom
   * korisniku vraća 403. Zaključavanje je pritom svačije: klik je „gotov sam, šaljem dalje".
   */
  protected readonly canUnlock = this.authService.canUnlockRecords;

  protected readonly surveys = signal<Survey[]>([]);
  protected readonly definitions = signal<ColumnDefinition[]>([]);
  protected readonly loading = signal(false);
  /** Greška koja pripada ekranu: neuspjelo učitavanje, brisanje, unos u retku tablice. */
  protected readonly error = signal<string | null>(null);
  /**
   * Greška koja pripada onome što se upravo upisuje u dijalogu. Ide zasebno jer bi
   * inače završila na stranici IZA zatamnjene pozadine - ondje gdje je korisnik,
   * zagledan u dijalog, uopće ne vidi.
   */
  protected readonly dialogError = signal<string | null>(null);
  /**
   * Greška retka koji se upisuje u tablici. Stoji iznad tablice (redak je pretijesan
   * za poruku), ali joj vijek traje točno koliko i taj redak - zajedno s njim nestaje.
   * Da dijeli signal s greškama ekrana, odustajanje od unosa ostavilo bi poruku da visi
   * nad nečim čega više nema.
   */
  protected readonly inlineError = signal<string | null>(null);

  /**
   * Uvjeti pretrage - redak po redak, spojeni s I.
   *
   * Pretraga se od 12.08. radi na SERVERU: ovdje stoji samo ono što je korisnik upisao, a
   * filtriranje, sortiranje i rezanje na stranice radi baza. Dok se filtriralo u pregledniku,
   * ekran je morao imati sve zapise - na 20.000 redaka to su deseci megabajta i stotine
   * tisuća ćelija u stranici.
   */
  protected readonly filters = signal<SurveyFilter[]>([]);

  /**
   * Suženje na točno određene zapise; prazno = cijeli obrazac.
   *
   * Nije filtar nego druga vrsta pitanja - filtri govore o SADRŽAJU zapisa, ovo o samim
   * retcima. Postoji zbog dijaloga povezanih zapisa: ondje se vidjelo „#12", ali se taj zapis
   * u tablici nije dao naći (broj zapisa nije stupac sheme, pa se po njemu ne da filtrirati),
   * a bez retka u tablici nema ni uređivanja, brisanja ni priloga.
   */
  protected readonly focusIds = signal<number[]>([]);

  /**
   * Zapis koji treba prikazati ČIM se obrazac promijeni - obično polje, namjerno ne signal.
   *
   * Efekt koji prati odabrani obrazac pri svakoj promjeni čisti filtre, poredak i stranicu,
   * pa bi suženje postavljeno prije {@link TemplateService#setActive} bilo počišćeno prije
   * nego ga je itko upotrijebio. Zato se ostavlja ovdje, a efekt ga potroši nakon čišćenja.
   *
   * Signal ne dolazi u obzir: efekt koji ga čita I briše bio bi ovisan o vlastitom rezultatu.
   */
  private pendingFocusIds: number[] | null = null;

  /** Stranica koja se gleda (od 0) i ono što je server javio o cjelini. */
  protected readonly page = signal(0);
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(0);

  /** Stupac i smjer sortiranja; null = poredak unosa. */
  protected readonly sortBy = signal<string | null>(null);
  protected readonly sortDir = signal<'asc' | 'desc'>('asc');

  /**
   * Odgoda između tipkanja i zahtjeva.
   *
   * Bez nje svaki znak šalje upit, pa "zagreb" znači šest upita od kojih pet nikoga ne
   * zanima - a odgovori se znaju vratiti izvan reda i ostaviti na ekranu rezultat za "zagr".
   */
  private static readonly TYPING_DELAY_MS = 300;
  private typingTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly showAddDialog = signal(false);
  /** Zapis čija je povijest otvorena; null = dijalog zatvoren. */
  protected readonly historySurveyId = signal<number | null>(null);
  /**
   * Otvoreni dijalog povezanih zapisa: koji gumb je pritisnut i nad kojim retkom.
   *
   * Drži se STUPAC, a ne samo id zapisa: iz njega dolazi i obrazac u kojem se traži i stupac
   * koji pokazuje natrag. Jedan redak može imati i dva takva gumba (npr. „Potprocesi" i
   * „Rizici"), pa sam id retka ne bi rekao koji je od njih otvoren.
   */
  protected readonly relatedFor = signal<{ field: SchemaField; surveyId: number } | null>(null);
  /**
   * Otvoreni dijalog poveznica: koji stupac i koji redak.
   *
   * Isto kao kod povezanih zapisa - jedan redak smije imati dva stupca s poveznicama („Dokazi"
   * i „Propisi"), pa sam id retka ne bi rekao koji je od njih otvoren.
   */
  protected readonly linksFor = signal<{ field: SchemaField; surveyId: number } | null>(null);
  protected readonly addValues = signal<Record<string, FieldValue>>({});

  protected readonly editingId = signal<number | null>(null);
  protected readonly editValues = signal<Record<string, FieldValue>>({});

  // id retka ISPOD kojeg stoji prazan redak za izravan unos (null = nema ga)
  protected readonly inlineAfterId = signal<number | null>(null);
  protected readonly inlineValues = signal<Record<string, FieldValue>>({});

  /**
   * Stavke šifrarnika koje ovaj obrazac koristi, po id-u šifrarnika.
   *
   * Dohvaćaju se jednom po ŠIFRARNIKU, a ne po stupcu: dva stupca smiju gledati u isti
   * šifrarnik, pa bi dohvat po stupcu isti popis vukao dvaput.
   */
  private readonly codebookItems = signal<Map<number, CodebookItem[]>>(new Map());

  /**
   * Prilozi SVIH zapisa ovog obrasca, dohvaćeni jednim pozivom.
   *
   * Drži ih ekran, a ne pojedina ćelija: ćelija ih zna samo prikazati, a dohvat po ćeliji
   * značio bi jedan poziv po retku i stupcu. Sadržaj datoteka pritom ne dolazi - njega
   * backend šalje tek kad ga korisnik zatraži.
   */
  private readonly attachments = signal<Attachment[]>([]);

  // shema aktivnog obrasca kao polja forme, sa stavkama pridruženim šifrarničkim stupcima
  protected readonly fields = computed<SchemaField[]>(() => {
    const items = this.codebookItems();
    return this.definitions().map(toSchemaField).map((field) => withItems(field, items));
  });

  protected readonly columns = computed(() => this.fields().map((f) => f.key));

  /**
   * Polja koja korisnik stvarno unosi.
   *
   * Otpadaju readOnly stupci (njih postavlja backend) te gumb i datoteka, koji uopće nemaju
   * vrijednost - poslati im bilo što značilo bi tvrditi da je spremaju. Prilozi žive u
   * vlastitoj tablici i mijenjaju se vlastitim pozivima.
   */
  protected readonly editableFields = computed(() =>
    this.fields().filter((f) => !f.readOnly && f.type !== 'button' && f.type !== 'file')
  );

  private readonly fieldsByKey = computed(() => {
    const map = new Map<string, SchemaField>();
    for (const field of this.fields()) {
      map.set(field.key, field);
    }
    return map;
  });

  protected readonly editingSurvey = computed(() => {
    const id = this.editingId();
    return id === null ? null : (this.surveys().find((s) => s.id === id) ?? null);
  });

  /**
   * Stupci po kojima se smije sortirati.
   *
   * Gumb i datoteka u zapisu ne drže ništa (prilozi su u vlastitoj tablici), a šifrarnički
   * stupac s višestrukim odabirom drži POPIS šifara - poredak popisa nije poredak ničega što
   * korisnik vidi. Veza drži ID, a prikazuje naziv: poredak po id-u bio bi poredak po nečemu
   * što se na ekranu ne vidi, a poredak po nazivu traži spajanje s drugim obrascem.
   *
   * Backend sve to odbija; ovdje se ne nudi, da klik na zaglavlje ne završi greškom.
   */
  protected readonly sortableFields = computed(() =>
    this.fields().filter(
      (f) =>
        f.type !== 'button' && f.type !== 'file' && f.type !== 'link'
        && f.type !== 'reference' && !isMultiValued(f)
    )
  );

  /**
   * Stupci po kojima se smije FILTRIRATI.
   *
   * Nije isto što i sortiranje: veza se ne da poredati (drži id, prikazuje naziv), ali se
   * itekako da tražiti - „pokaži rizike ovog procesa" je jedno od glavnih pitanja koja veze
   * uopće i omogućuju. Traži se pritom točan zapis, odabran iz istog dijaloga, a ne upisani
   * tekst: nazivom bi se pogađalo, id-om se ne.
   */
  protected readonly filterableFields = computed(() => {
    const sortable = this.sortableFields();
    return this.fields().filter((f) => f.type === 'reference' || sortable.includes(f));
  });

  /** Stupac jednog retka filtra - po njemu se zna crta li se polje za upis ili odabir zapisa. */
  protected filterField(columnKey: string): SchemaField | null {
    return this.fieldsByKey().get(columnKey) ?? null;
  }

  /** Naziv zapisa odabranog u filtru; prazno dok filtar nije postavljen. */
  protected filterRecordLabel(columnKey: string, value: string): string {
    const field = this.filterField(columnKey);
    if (field === null || value.trim() === '') {
      return '';
    }
    return this.referenceLabels(field, Number(value))[0] ?? value;
  }

  /** Redak filtra u kojem je otvoren dijalog za odabir zapisa; null = nijedan. */
  protected readonly filterPickerIndex = signal<number | null>(null);

  protected openFilterPicker(index: number): void {
    this.filterPickerIndex.set(index);
  }

  /**
   * Zapis odabran u filtru.
   *
   * U filtar ide ID, jer se po njemu i traži; naziv se prikazuje zasebno. Prazan odabir
   * briše uvjet, isto kao da je polje ispražnjeno.
   */
  protected setFilterRecord(index: number, ids: number[]): void {
    this.filterPickerIndex.set(null);
    this.filters.set(
      this.filters().map((f, i) => (i === index ? { ...f, value: ids.length > 0 ? String(ids[0]) : '' } : f))
    );
    this.applyQuery();
  }

  /** Redni broj prvog i zadnjeg retka na stranici - za „1–50 od 1.234”. */
  protected readonly rangeFrom = computed(() =>
    this.totalElements() === 0 ? 0 : this.page() * PAGE_SIZE + 1
  );
  protected readonly rangeTo = computed(() =>
    Math.min((this.page() + 1) * PAGE_SIZE, this.totalElements())
  );

  /**
   * Brojevi stranica za traku - najviše sedam, sa skokom na prvu i zadnju.
   *
   * `null` je razmak („…”): popis od 400 stranica ne stane ni na jedan zaslon, a i kad bi
   * stao, nikome ne treba gumb za stranicu 217.
   */
  protected readonly pageNumbers = computed<(number | null)[]>(() => {
    const total = this.totalPages();
    const current = this.page();
    if (total <= 7) {
      return Array.from({ length: total }, (_, i) => i);
    }
    const around = [current - 1, current, current + 1].filter((p) => p > 0 && p < total - 1);
    const shown = [0, ...around, total - 1];
    const result: (number | null)[] = [];
    let previous = -1;
    for (const p of shown) {
      if (p - previous > 1) {
        result.push(null);
      }
      result.push(p);
      previous = p;
    }
    return result;
  });

  constructor() {
    // promjena aktivne firme -> ponovno učitaj njezine obrasce
    effect(() => {
      const companyId = this.activeCompanyId();
      if (companyId === null) {
        this.templateService.clear();
      } else {
        this.reloadTemplates();
      }
    });

    // promjena odabranog obrasca -> učitaj njegove podatke i shemu
    effect(() => {
      const templateId = this.activeTemplateId();
      /*
       * SVE ostalo ide kroz untracked: ovaj efekt smije ovisiti isključivo o odabranom
       * obrascu.
       *
       * Bez toga ga budi i promjena stranice, filtra ili poretka - jer `load` preko
       * `loadSurveys` čita te signale, a efekt pamti sve što pročita. Posljedica je bila
       * da odlazak na stranicu 2 pokrene efekt, koji odabir odmah poništi na „prva
       * stranica, bez filtra": tablica je pokazivala retke stranice 2 (njezin odgovor je
       * stigao), a traka je stajala na 1.
       */
      untracked(() => {
        this.closeAddDialog();
        this.cancelEdit();
        this.cancelInlineAdd();
        // filtri i poredak se vežu uz stupce OVOG obrasca - drugi ih obrazac nema
        this.filters.set([]);
        this.sortBy.set(null);
        this.page.set(0);
        // Jedino što promjenu obrasca smije preživjeti je suženje koje je nju i izazvalo
        // („idi na povezani zapis"). Zato se ne postavlja izvana nego se ovdje POTROŠI -
        // vidi {@link pendingFocusIds}. Obična promjena obrasca ga nema, pa čisti suženje.
        this.focusIds.set(this.pendingFocusIds ?? []);
        this.pendingFocusIds = null;

        if (templateId === null) {
          this.surveys.set([]);
          this.definitions.set([]);
          this.codebookItems.set(new Map());
          this.attachments.set([]);
        } else {
          this.load(templateId);
        }
      });
    });
  }

  protected onTemplateChange(templateId: number | null): void {
    this.templateService.setActive(templateId);
  }

  private reloadTemplates(): void {
    const requestedFor = this.activeCompanyId();
    this.templateService.refresh().subscribe({
      error: () => {
        if (this.activeCompanyId() === requestedFor) {
          this.error.set('Dohvaćanje obrazaca nije uspjelo.');
        }
      }
    });
  }

  private load(templateId: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.columnDefinitionService.getForTemplate(templateId).subscribe({
      next: (data) => {
        if (!this.isStale(templateId)) {
          this.definitions.set(data);
          this.loadCodebookItems(templateId, data);
          this.loadAttachments(templateId, data);
        }
      },
      error: () => {
        if (!this.isStale(templateId)) {
          this.error.set('Dohvaćanje sheme nije uspjelo.');
        }
      }
    });
    this.loadSurveys(templateId);
  }

  /**
   * Dohvat jedne stranice zapisa, s trenutnom pretragom i poretkom.
   *
   * Odvojeno od {@link load}: shema i šifrarnici se dohvaćaju jednom po obrascu, a ovo se
   * ponavlja na svaku promjenu stranice, filtra ili poretka - dakle često.
   *
   * Prazan filtar se ne šalje: server bi ga odbio kao besmislen, a korisnik ga vidi kao redak
   * koji je tek otvorio i još nije ispunio.
   */
  private loadSurveys(templateId: number): void {
    this.loading.set(true);
    this.surveyService
      .getSurveys(templateId, {
        page: this.page(),
        sortBy: this.sortBy(),
        sortDir: this.sortDir(),
        filters: this.filters().filter((f) => f.column && f.value.trim() !== ''),
        ids: this.focusIds()
      })
      .subscribe({
        next: (result) => {
          if (this.isStale(templateId)) {
            return;
          }
          this.surveys.set(result.content);
          this.totalElements.set(result.totalElements);
          this.totalPages.set(result.totalPages);
          // nazivi povezanih zapisa: jedan zahtjev po stupcu za cijelu stranicu
          this.ensureReferenceLabels(result.content);
          this.loading.set(false);
        },
        error: () => {
          if (this.isStale(templateId)) {
            return;
          }
          this.error.set('Dohvaćanje podataka nije uspjelo. Provjerite je li backend pokrenut.');
          this.loading.set(false);
        }
      });
  }

  /** Ponovni dohvat trenutne stranice - nakon unosa, brisanja ili promjene filtra. */
  protected reloadSurveys(): void {
    const templateId = this.activeTemplateId();
    if (templateId !== null) {
      this.loadSurveys(templateId);
    }
  }

  // ===================== PRETRAGA, POREDAK, STRANICE =====================

  protected addFilter(): void {
    this.filters.set([...this.filters(), { column: this.sortableFields()[0]?.key ?? '', value: '' }]);
  }

  protected removeFilter(index: number): void {
    this.filters.set(this.filters().filter((_, i) => i !== index));
    this.applyQuery();
  }

  protected setFilterColumn(index: number, column: string): void {
    this.filters.set(this.filters().map((f, i) => (i === index ? { ...f, column } : f)));
    this.applyQuery();
  }

  /**
   * Upisivanje u polje filtra. Zahtjev se šalje tek kad tipkanje stane - vidi
   * {@link TYPING_DELAY_MS}.
   */
  protected setFilterValue(index: number, value: string): void {
    this.filters.set(this.filters().map((f, i) => (i === index ? { ...f, value } : f)));
    if (this.typingTimer !== null) {
      clearTimeout(this.typingTimer);
    }
    this.typingTimer = setTimeout(() => this.applyQuery(), DataView.TYPING_DELAY_MS);
  }

  /**
   * Klik na zaglavlje stupca: prvi put sortira uzlazno, drugi put silazno, treći put vraća
   * poredak unosa. Bez trećeg stanja se jednom uključeno sortiranje ne da isključiti.
   */
  protected toggleSort(field: SchemaField): void {
    if (!this.sortableFields().some((f) => f.key === field.key)) {
      return;
    }
    if (this.sortBy() !== field.key) {
      this.sortBy.set(field.key);
      this.sortDir.set('asc');
    } else if (this.sortDir() === 'asc') {
      this.sortDir.set('desc');
    } else {
      this.sortBy.set(null);
    }
    this.applyQuery();
  }

  /** Da li se po ovom stupcu uopće može sortirati - odlučuje i izgled zaglavlja. */
  protected isSortable(field: SchemaField): boolean {
    return this.sortableFields().some((f) => f.key === field.key);
  }

  /**
   * Oznaka uz naziv stupca.
   *
   * Blijedo „↕” stoji na SVAKOM stupcu koji se da sortirati, i prije ijednog klika: bez toga
   * se moralo pogađati da je zaglavlje uopće klikabilno (i pogađalo se). Kad je stupac
   * odabran, strelica pokazuje smjer.
   */
  protected sortMark(field: SchemaField): string {
    if (!this.isSortable(field)) {
      return '';
    }
    if (this.sortBy() !== field.key) {
      return '↕';
    }
    return this.sortDir() === 'asc' ? '▲' : '▼';
  }

  /**
   * Opis pri prelasku mišem - govori što će klik napraviti, a ne što se dogodilo.
   *
   * Kod stupca koji se ne da sortirati objašnjava ZAŠTO, jer je to jedini način da korisnik
   * sazna da klik nije zakazao nego da ondje nema po čemu sortirati.
   */
  protected sortTitle(field: SchemaField): string {
    if (!this.isSortable(field)) {
      return `Stupac "${field.label}" nema vrijednost po kojoj bi se sortirao.`;
    }
    if (this.sortBy() !== field.key) {
      return `Sortiraj po: ${field.label}`;
    }
    return this.sortDir() === 'asc'
      ? 'Sortirano uzlazno - klikni za silazno'
      : 'Sortirano silazno - klikni za poredak unosa';
  }

  protected goToPage(page: number): void {
    if (page < 0 || page >= this.totalPages() || page === this.page()) {
      return;
    }
    this.page.set(page);
    // redak u izradi visi ispod određenog retka, a njega na novoj stranici nema
    this.cancelInlineAdd();
    this.reloadSurveys();
  }

  /**
   * Promjena pretrage ili poretka vraća na prvu stranicu.
   *
   * Bez toga korisnik koji je na stranici 7 suzi pretragu na tri rezultata gleda praznu
   * stranicu 7 od 1 i zaključi da pretraga ne radi.
   */
  private applyQuery(): void {
    this.page.set(0);
    this.cancelInlineAdd();
    this.reloadSurveys();
  }

  /**
   * Stavke svih šifrarnika koje shema koristi, dohvaćene odjednom.
   *
   * Ide u jednom forkJoin-u, a ne stupac po stupac: polja se ne smiju nacrtati s pola
   * popisa (padajući izbornik bi nakratko bio prazan i ponudio brisanje vrijednosti).
   * Neuspjeh se ne prijavljuje kao greška ekrana - podaci su tu i čitljivi, samo bez
   * naziva umjesto šifara, pa bi crvena poruka rekla više nego što se stvarno dogodilo.
   */
  private loadCodebookItems(templateId: number, definitions: ColumnDefinition[]): void {
    const ids = [...new Set(definitions
      .filter((column) => column.columnType === 'codebook' && column.codebookId !== null)
      .map((column) => column.codebookId as number))];
    if (ids.length === 0) {
      this.codebookItems.set(new Map());
      return;
    }

    forkJoin(ids.map((id) => this.codebookService.getItems(id).pipe(
      // šifrarnik koji se u međuvremenu izgubio ne smije srušiti cijeli dohvat
      catchError(() => of([] as CodebookItem[]))
    ))).subscribe((lists) => {
      if (this.isStale(templateId)) {
        return;
      }
      const byId = new Map<number, CodebookItem[]>();
      ids.forEach((id, index) => byId.set(id, lists[index]));
      this.codebookItems.set(byId);
    });
  }

  /**
   * Prilozi svih zapisa obrasca - samo ako obrazac uopće ima stupac za datoteke.
   *
   * Neuspjeh se ne prijavljuje kao greška ekrana: zapisi su tu i čitljivi, samo bez popisa
   * priloga. Isto pravilo kao kod stavki šifrarnika - crvena poruka rekla bi više nego što
   * se stvarno dogodilo.
   */
  private loadAttachments(templateId: number, definitions: ColumnDefinition[]): void {
    if (!definitions.some((column) => column.columnType === 'file')) {
      this.attachments.set([]);
      return;
    }
    this.attachmentService.listForTemplate(templateId).subscribe({
      next: (data) => {
        if (!this.isStale(templateId)) {
          this.attachments.set(data);
        }
      },
      error: () => {
        if (!this.isStale(templateId)) {
          this.attachments.set([]);
        }
      }
    });
  }

  /**
   * Osvježi šifrarnike koje je upravo spremljen zapis mogao dopuniti.
   *
   * Stupac sa slobodnim unosom stvara novu stavku NA SERVERU, pri spremanju zapisa. Popis
   * stavki učitan pri otvaranju obrasca u tom trenutku zastari: zapis nosi šifru koju taj
   * popis ne poznaje, pa prikaz pada na samu šifru („OSIJEK") umjesto naziva („Osijek").
   *
   * Dohvaća se samo ono što je stvarno zastarjelo - šifrarnik u kojem se pojavila nepoznata
   * šifra. Kad je korisnik odabrao postojeću vrijednost (uobičajen slučaj), poziva nema.
   */
  private refreshGrownCodebooks(templateId: number, saved: Survey): void {
    const ids = [...new Set(
      this.fields()
        .filter((field) => field.options.allowNewValues && field.codebookId !== null)
        .filter((field) => this.hasUnknownCode(field, saved.data?.[field.key]))
        .map((field) => field.codebookId as number)
    )];
    if (ids.length === 0) {
      return;
    }

    forkJoin(ids.map((id) => this.codebookService.getItems(id).pipe(
      catchError(() => of([] as CodebookItem[]))
    ))).subscribe((lists) => {
      if (this.isStale(templateId)) {
        return;
      }
      // karta se DOPUNJUJE, ne zamjenjuje: ostali šifrarnici u njoj i dalje vrijede
      const byId = new Map(this.codebookItems());
      ids.forEach((id, index) => byId.set(id, lists[index]));
      this.codebookItems.set(byId);
    });
  }

  /** Nosi li spremljena vrijednost šifru koju učitani popis stavki ne poznaje. */
  private hasUnknownCode(field: SchemaField, value: unknown): boolean {
    const known = new Set(field.items.map((item) => item.code));
    return asCodes(value as FieldValue).some((code) => !known.has(code));
  }

  /** Prilozi jedne ćelije - jednog stupca jednog zapisa. */
  protected attachmentsFor(surveyId: number | null, columnKey: string): Attachment[] {
    if (surveyId === null) {
      return [];
    }
    return this.attachments().filter(
      (attachment) => attachment.surveyId === surveyId && attachment.columnKey === columnKey
    );
  }

  /** Prilog je dodan ili maknut - popis se osvježava u cijelosti, jednim pozivom. */
  protected onAttachmentsChanged(): void {
    const templateId = this.activeTemplateId();
    if (templateId !== null) {
      this.loadAttachments(templateId, this.definitions());
    }
  }

  /**
   * Je li odgovor stigao za obrazac koji više nije odabran.
   *
   * Promjena firme ostavi u zraku zahtjev za obrazac PRETHODNE firme; on završi kao 404
   * (tuđi obrazac se ne otkriva), i to zna stići tek nakon što je ispravan obrazac već
   * učitan. Pusti li se takav odgovor u stanje ekrana, korisnik gleda točne podatke uz
   * poruku da dohvaćanje nije uspjelo - ili, gore, tuđe podatke pod novom firmom.
   *
   * Zastarjeli odgovor ne dira ni {@code loading}: zahtjev koji ga je zamijenio ga je
   * ionako ponovno postavio i sam će ga ugasiti.
   */
  private isStale(templateId: number): boolean {
    return this.activeTemplateId() !== templateId;
  }

  private failed(fallback: string): (error: unknown) => void {
    return (error: unknown) => this.error.set(this.messageOf(error, fallback));
  }

  /** Isto, ali poruka ostaje u dijalogu iz kojeg je akcija krenula. */
  private failedInDialog(fallback: string): (error: unknown) => void {
    return (error: unknown) => this.dialogError.set(this.messageOf(error, fallback));
  }

  private messageOf(error: unknown, fallback: string): string {
    return (error as { error?: { message?: string } })?.error?.message ?? fallback;
  }

  protected formatValue(value: unknown): string {
    if (value === null || value === undefined) {
      return '-';
    }
    return String(value);
  }

  /**
   * Vrijednost onako kako je korisnik treba vidjeti.
   *
   * Kod šifrarničkog stupca u zapisu stoji ŠIFRA ("HR"), a prikazuje se naziv stavke
   * ("Hrvatska") - upravo zato su to dva odvojena polja: naziv se smije mijenjati, a da
   * već spremljeni podaci ostanu netaknuti.
   *
   * Format broja i datuma je isto samo prikaz: u zapisu ostaje broj, odnosno datum u ISO
   * obliku - jedino se takav ispravno sortira i uspoređuje.
   */
  protected displayValue(field: SchemaField, value: unknown): string {
    // gumb i datoteka ne drže vrijednost u zapisu, a poveznice se otvaraju u dijalogu -
    // sve tri crta vlastita kontrola
    if (field.type === 'button' || field.type === 'file' || field.type === 'link') {
      return '';
    }
    if (field.type === 'codebook') {
      const names = this.codeLabels(field, value);
      return names.length === 0 ? '-' : names.join(', ');
    }
    if (field.type === 'reference') {
      const names = this.referenceLabels(field, value);
      return names.length === 0 ? '-' : names.join(', ');
    }
    if (field.type === 'date' && field.options.dateMode === 'datetime' && typeof value === 'string') {
      return formatDateTime(value);
    }
    if ((field.type === 'number' || field.type === 'formula') && typeof value === 'number') {
      return formatNumber(field, value);
    }
    return this.formatValue(value);
  }

  /** Nazivi odabranih stavki - jedan kod jednovrijednosnog stupca, više kod višestrukog. */
  protected codeLabels(field: SchemaField, value: unknown): string[] {
    return asCodes(value as FieldValue).map((code) => codebookLabel(field, code) ?? code);
  }

  /**
   * Nazivi povezanih zapisa.
   *
   * Naziv živi u drugom obrascu, pa ga ćelija ne može izračunati sama - dohvaća ga
   * {@link ReferenceLabelService}, jednim zahtjevom za cijelu stranicu (vidi
   * {@link ensureReferenceLabels}). Dok odgovor ne stigne, stoji sam broj: to je i dalje
   * istina o tome što u zapisu piše, a prazna ćelija bi izgledala kao da veze nema.
   */
  protected referenceLabels(field: SchemaField, value: unknown): string[] {
    const targetTemplateId = field.options.targetTemplateId;
    const displayKey = field.options.displayColumnKey;
    return asIds(value as FieldValue).map((id) => {
      if (targetTemplateId === null || !displayKey) {
        return `#${id}`;
      }
      return this.labelService.labelOf(targetTemplateId, displayKey, id) ?? `#${id}`;
    });
  }

  /**
   * Dohvati nazive svih veza koje stoje na trenutnoj stranici - jednim zahtjevom po stupcu.
   *
   * Zove se nakon dohvata zapisa, a ne iz same ćelije: crtanje ćelije je sinkrono, pa bi
   * zahtjev po ćeliji značio pedeset zahtjeva po stranici, i to za vrijednosti koje se
   * ionako ponavljaju.
   */
  private ensureReferenceLabels(surveys: Survey[]): void {
    for (const field of this.fields()) {
      const targetTemplateId = field.options.targetTemplateId;
      const displayKey = field.options.displayColumnKey;
      if (field.type !== 'reference' || targetTemplateId === null || !displayKey) {
        continue;
      }
      const ids = surveys.flatMap((survey) => asIds(survey.data?.[field.key] as FieldValue));
      this.labelService.ensure(targetTemplateId, displayKey, ids);
    }
  }

  protected isMulti(field: SchemaField): boolean {
    return isMultiValued(field);
  }

  /** Vrijednost readOnly polja kako će je backend postaviti - forma je samo pokazuje. */
  protected readOnlyPreview(field: SchemaField, values: Record<string, FieldValue>): string {
    return this.displayValue(field, values[field.key]);
  }

  // redak nema fiksno "ime", pa ga u potvrdama predstavlja prvi popunjeni stupac
  protected surveyLabel(survey: Survey): string {
    for (const key of this.columns()) {
      const value = survey.data?.[key];
      if (value !== null && value !== undefined && value !== '') {
        return this.formatValue(value);
      }
    }
    return `#${survey.id}`;
  }

  // ===================== ZAJEDNIČKO ZA UNOS =====================

  private freshValues(): Record<string, FieldValue> {
    const values: Record<string, FieldValue> = {};
    for (const field of this.fields()) {
      values[field.key] = initialValue(field);
    }
    return values;
  }

  /**
   * Ista pravila koja brani i backend (obavezno, jedinstveno) - ovdje samo zato da
   * korisnik odgovor dobije odmah. Backend ostaje mjerodavan; jedinstvenost se ovdje
   * mjeri po učitanim retcima, a on je provjerava nad svima.
   *
   * @param excludeId zapis koji se uređuje - ne uspoređuje se sam sa sobom
   * @return poruka o problemu, ili null ako je unos u redu
   */
  private inputProblem(values: Record<string, FieldValue>, excludeId: number | null): string | null {
    for (const field of this.fields()) {
      const value = values[field.key];
      if (field.required && isEmptyValue(value)) {
        return `Polje "${field.label}" je obavezno.`;
      }
      if (field.unique && !isEmptyValue(value)) {
        const taken = this.surveys().some(
          (survey) => survey.id !== excludeId && sameValue(value, survey.data?.[field.key])
        );
        if (taken) {
          return `Vrijednost "${value}" u polju "${field.label}" već postoji u drugom zapisu.`;
        }
      }
    }
    return null;
  }

  /**
   * Podaci za slanje: prazna polja se izostavljaju, readOnly se ne šalju uopće
   * (backend ih ionako postavlja sam).
   */
  private payloadFrom(values: Record<string, FieldValue>): SurveyInput {
    const data: Record<string, FieldValue> = {};
    for (const field of this.editableFields()) {
      const value = values[field.key];
      if (!isEmptyValue(value)) {
        data[field.key] = value;
      }
    }
    return { data };
  }

  // ===================== DIJALOG ZA NOVI ZAPIS =====================

  protected openAddDialog(): void {
    this.addValues.set(this.freshValues());
    this.dialogError.set(null);
    this.showAddDialog.set(true);
  }

  protected closeAddDialog(): void {
    this.showAddDialog.set(false);
    this.dialogError.set(null);
  }

  protected updateAddValue(key: string, value: FieldValue): void {
    this.addValues.set({ ...this.addValues(), [key]: value });
  }

  protected submitAdd(): void {
    const templateId = this.activeTemplateId();
    if (templateId === null) {
      return;
    }
    const problem = this.inputProblem(this.addValues(), null);
    if (problem !== null) {
      this.dialogError.set(problem);
      return;
    }
    this.dialogError.set(null);
    this.surveyService.createSurvey(templateId, this.payloadFrom(this.addValues())).subscribe({
      next: (created) => {
        // Nov zapis se ne dopisuje na kraj popisa nego se stranica dohvaća ponovno: gdje
        // taj redak pripada odlučuje poredak i pretraga na serveru, a i ukupan broj se
        // promijenio. Dopisan redak bi na sortiranoj tablici stajao na krivom mjestu.
        this.refreshGrownCodebooks(templateId, created);
        this.closeAddDialog();
        this.reloadSurveys();
      },
      error: this.failedInDialog('Dodavanje zapisa nije uspjelo.')
    });
  }

  // ===================== PRAZAN REDAK U TABLICI =====================

  /** Otvori prazan redak za unos odmah ispod zadanog. Otvoren može biti samo jedan. */
  protected startInlineAdd(survey: Survey): void {
    this.inlineValues.set(this.freshValues());
    this.inlineError.set(null);
    this.inlineAfterId.set(survey.id);
  }

  /**
   * Makni redak u izradi - i poruku koja se na njega odnosila. Isto vrijedi kad redak
   * nestane sam od sebe (promjena obrasca, filtra ili pretrage), pa sve to ide ovuda.
   */
  protected cancelInlineAdd(): void {
    this.inlineAfterId.set(null);
    this.inlineError.set(null);
  }

  protected updateInlineValue(key: string, value: FieldValue): void {
    this.inlineValues.set({ ...this.inlineValues(), [key]: value });
  }

  protected submitInlineAdd(): void {
    const templateId = this.activeTemplateId();
    const afterId = this.inlineAfterId();
    if (templateId === null || afterId === null) {
      return;
    }
    const problem = this.inputProblem(this.inlineValues(), null);
    if (problem !== null) {
      this.inlineError.set(problem);
      return;
    }
    this.inlineError.set(null);
    this.surveyService.createSurvey(templateId, this.payloadFrom(this.inlineValues())).subscribe({
      next: (created) => {
        // Ovdje se stranica NE dohvaća ponovno, za razliku od unosa kroz dijalog: redak je
        // upisan na točno određeno mjesto u tablici i mora se pojaviti baš ondje. Uz uključeno
        // sortiranje ili pretragu to mjesto nije ono na kojem će biti nakon osvježavanja - i
        // to je svjesna zamjena, jer je gubitak mjesta na kojem se upisivalo gori.
        this.surveys.set(this.insertAfter(this.surveys(), afterId, created));
        // ukupan broj mora pratiti, inače "1–50 od 1.234" laže odmah nakon unosa
        this.totalElements.set(this.totalElements() + 1);
        this.refreshGrownCodebooks(templateId, created);
        this.cancelInlineAdd();
      },
      error: (error: unknown) => this.inlineError.set(this.messageOf(error, 'Dodavanje zapisa nije uspjelo.'))
    });
  }

  /**
   * Novi zapis sjeda odmah iza onog pored kojeg je unesen - da se pojavi ondje gdje ga
   * korisnik upisao, a ne na dnu. Redoslijed vrijedi do sljedećeg učitavanja: backend
   * zapise vraća svojim redoslijedom.
   */
  private insertAfter(list: Survey[], afterId: number, created: Survey): Survey[] {
    const index = list.findIndex((survey) => survey.id === afterId);
    if (index === -1) {
      return [...list, created];
    }
    return [...list.slice(0, index + 1), created, ...list.slice(index + 1)];
  }

  // ===================== UREĐIVANJE =====================

  protected startEdit(survey: Survey): void {
    // Zaključan zapis nema što otvoriti: forma bi se ispunila i pala tek na spremanju.
    // Gumb je i onemogućen, pa je ovo obrana za put koji ga zaobiđe (npr. tipkovnica).
    if (survey.locked) {
      return;
    }
    this.dialogError.set(null);
    this.editingId.set(survey.id);
    const values: Record<string, FieldValue> = {};
    for (const field of this.fields()) {
      const existing = survey.data?.[field.key];
      values[field.key] = existing !== undefined && existing !== null ? (existing as FieldValue) : initialValue(field);
    }
    this.editValues.set(values);
  }

  protected updateEditValue(key: string, value: FieldValue): void {
    this.editValues.set({ ...this.editValues(), [key]: value });
  }

  protected cancelEdit(): void {
    this.editingId.set(null);
    this.dialogError.set(null);
  }

  protected submitEdit(survey: Survey): void {
    const templateId = this.activeTemplateId();
    if (templateId === null) {
      return;
    }
    const problem = this.inputProblem(this.editValues(), survey.id);
    if (problem !== null) {
      this.dialogError.set(problem);
      return;
    }
    this.dialogError.set(null);
    const data: Record<string, FieldValue> = {};
    for (const field of this.editableFields()) {
      const value = this.editValues()[field.key];
      // polje koje je redak VEĆ imao šalje se natrag i kad ga korisnik ne dira,
      // inače bi se izgubilo (PUT je puna zamjena, ne merge)
      const wasPresent = !!survey.data && Object.prototype.hasOwnProperty.call(survey.data, field.key);
      if (wasPresent || !isEmptyValue(value)) {
        data[field.key] = value;
      }
    }
    this.surveyService.updateSurvey(templateId, survey.id, { data }).subscribe({
      next: (updated) => {
        this.surveys.set(this.surveys().map((s) => (s.id === updated.id ? updated : s)));
        this.refreshGrownCodebooks(templateId, updated);
        this.cancelEdit();
      },
      error: this.failedInDialog('Spremanje izmjena nije uspjelo.')
    });
  }

  protected deleteSurvey(survey: Survey): void {
    const templateId = this.activeTemplateId();
    if (templateId === null || survey.locked) {
      return;
    }
    if (!confirm(`Obrisati zapis "${this.surveyLabel(survey)}"?`)) {
      return;
    }
    this.surveyService.deleteSurvey(templateId, survey.id).subscribe({
      next: () => {
        // backend je s zapisom obrisao i njegove priloge; da ih se ovdje ne makne, ostali
        // bi u popisu i pojavili se na novom zapisu koji jednom dobije isti id
        this.attachments.set(this.attachments().filter((a) => a.surveyId !== survey.id));
        // Obrisan je baš zapis na koji je prikaz bio sužen (dolazak iz dijaloga povezanih
        // zapisa i završava brisanjem). Ostane li suženje, tablica javlja "nema zapisa za
        // zadani upit" ispod trake koja tvrdi da prikazuje jedan - a upit je ispravan.
        this.focusIds.set(this.focusIds().filter((id) => id !== survey.id));
        // stranica se dohvaća ponovno: brisanjem se promijenio ukupan broj, a na mjesto
        // obrisanog retka dolazi prvi sa sljedeće stranice
        this.reloadSurveys();
      },
      error: this.failed('Brisanje zapisa nije uspjelo.')
    });
  }

  /** Esc zatvara ono što je otvoreno - prvo dijalozi, pa redak u izradi. */
  /**
   * Klik na gumb u ćeliji. Radnja dolazi iz sheme, pa ekran ne mora znati koja je - samo
   * je proslijedi. Gumb bez radnje (zatečeni stupci iz vremena prije nego je radnja
   * postojala) ne radi ništa, umjesto da padne.
   */
  protected onCellButton(field: SchemaField, survey: Survey): void {
    if (field.options.buttonAction === 'history') {
      this.historySurveyId.set(survey.id);
    } else if (field.options.buttonAction === 'lock') {
      this.toggleLock(survey);
    } else if (field.options.buttonAction === 'related') {
      this.relatedFor.set({ field, surveyId: survey.id });
    }
  }

  protected closeRelated(): void {
    this.relatedFor.set(null);
  }

  /** Poveznice zapisa - popis se otvara u dijalogu, jer u ćeliju ne stane. */
  protected openLinks(field: SchemaField, survey: Survey): void {
    this.linksFor.set({ field, surveyId: survey.id });
  }

  protected closeLinks(): void {
    this.linksFor.set(null);
  }

  /** Poveznice retka koji je otvoren u dijalogu - čitaju se iz zapisa, da prate spremanje. */
  protected shownLinks(): RecordLink[] {
    const open = this.linksFor();
    if (open === null) {
      return [];
    }
    return asLinks(this.surveys().find((s) => s.id === open.surveyId)?.data[open.field.key]);
  }

  /** Je li redak zaključan - dijalog poveznica tada samo čita, kao i kod priloga. */
  protected isLocked(surveyId: number): boolean {
    return this.surveys().find((s) => s.id === surveyId)?.locked ?? false;
  }

  /** Koliko ih redak ima - piše na samom gumbu, da se ne mora otvarati da bi se vidjelo. */
  protected linkCount(field: SchemaField, survey: Survey): number {
    return asLinks(survey.data[field.key]).length;
  }

  /**
   * Popis poveznica je promijenjen u dijalogu - sprema se odmah, cijelim zapisom.
   *
   * Zašto odmah, a ne „pa spremi zapis": dijalog se otvara iz RETKA, a redak se pritom ne
   * uređuje, pa korisnik ne bi imao gdje potvrditi. Isto se ponaša i uklanjanje priloga.
   * Zapis ide cijeli jer drugog puta nema (PUT nad zapisom).
   */
  protected saveLinks(links: RecordLink[]): void {
    const open = this.linksFor();
    const templateId = this.activeTemplateId();
    const survey = open === null ? undefined : this.surveys().find((s) => s.id === open.surveyId);
    if (open === null || templateId === null || survey === undefined) {
      return;
    }

    this.error.set(null);
    const data = { ...survey.data, [open.field.key]: links };
    this.surveyService.updateSurvey(templateId, survey.id, { data }).subscribe({
      next: (updated) => {
        this.surveys.set(this.surveys().map((s) => (s.id === updated.id ? updated : s)));
      },
      error: this.failed('Spremanje poveznica nije uspjelo.')
    });
  }

  /**
   * „Otvori zapis" iz dijaloga povezanih zapisa - tablica se suzi na taj jedan redak.
   *
   * Zašto suženje, a ne novi ekran ni skok na stranicu na kojoj zapis leži. Suženjem redak
   * dobiva sve što ostali retci već imaju (uredi, obriši, zaključaj, prilozi, povijest), bez
   * ijednog novog gumba. Skok na „njegovu" stranicu ne bi radio: koja je to stranica ovisi o
   * poretku i pretrazi, a sam se redak među pedeset drugih ne bi ni vidio.
   *
   * Dva puta, jedan razlog. Kad je obrazac isti, ništa se ne mijenja pa efekt ni ne krene -
   * suženje se postavlja odmah. Kad je drugi, mora ga postaviti sam efekt, jer bi inače
   * njegovo čišćenje došlo POSLIJE (vidi {@link pendingFocusIds}).
   */
  protected openRelatedRecord(target: { templateId: number; recordId: number }): void {
    this.closeRelated();
    if (this.activeTemplateId() === target.templateId) {
      this.focusIds.set([target.recordId]);
      this.applyQuery();
      return;
    }
    this.pendingFocusIds = [target.recordId];
    this.templateService.setActive(target.templateId);
  }

  /** Natrag na cijeli obrazac - suženje je privremen pogled, ne stanje ekrana. */
  protected clearFocus(): void {
    this.focusIds.set([]);
    this.applyQuery();
  }

  /** Što piše u traci iznad tablice dok suženje traje. */
  protected focusNote(): string {
    const ids = this.focusIds();
    return ids.length === 1
      ? `Prikazan je samo zapis #${ids[0]}.`
      : `Prikazano je samo ${ids.length} odabranih zapisa.`;
  }

  /**
   * Zaključaj ili otključaj zapis, ovisno o tome u kojem je stanju.
   *
   * Oba puta traže potvrdu, ali iz različitih razloga: zaključavanje zato što korisniku
   * oduzima izmjenu vlastitog retka, otključavanje zato što je administratorski zahvat u
   * nešto što je netko drugi proglasio gotovim.
   *
   * Zabrana otključavanja je ovdje samo prečac za oko - jedini sudac je backend, koji
   * običnom korisniku vraća 403 i kad gumb nekako klikne.
   */
  private toggleLock(survey: Survey): void {
    const templateId = this.activeTemplateId();
    if (templateId === null) {
      return;
    }

    if (survey.locked && !this.canUnlock()) {
      this.error.set('Zaključani zapis može otključati samo administrator.');
      return;
    }
    const question = survey.locked
      ? `Otključati zapis "${this.surveyLabel(survey)}"?`
      : `Zaključati zapis "${this.surveyLabel(survey)}"? Nakon toga se ne može mijenjati, brisati ni dopunjavati prilozima.`;
    if (!confirm(question)) {
      return;
    }

    this.error.set(null);
    const request = survey.locked
      ? this.surveyService.unlockSurvey(templateId, survey.id)
      : this.surveyService.lockSurvey(templateId, survey.id);
    request.subscribe({
      next: (updated) => {
        this.surveys.set(this.surveys().map((s) => (s.id === updated.id ? updated : s)));
        // redak koji se upravo uređivao (ili se ispod njega upisivao novi) zaključavanjem
        // gubi smisao - forma bi ostala otvorena nad nečim što se više ne da spremiti
        if (updated.locked && this.editingId() === updated.id) {
          this.cancelEdit();
        }
      },
      error: this.failed(survey.locked ? 'Otključavanje nije uspjelo.' : 'Zaključavanje nije uspjelo.')
    });
  }

  /**
   * Natpis gumba koji zaključava. Isti gumb radi oba smjera, pa mora reći koji je na redu -
   * natpis iz sheme („Pošalji na validaciju") opisuje samo zaključavanje.
   */
  protected cellButtonLabel(field: SchemaField, survey: Survey): string {
    if (field.options.buttonAction === 'lock' && survey.locked) {
      return 'Otključaj';
    }
    return field.options.buttonLabel || field.label;
  }

  /**
   * Gumb bez radnje ostaje onemogućen (zatečeni stupci iz vremena prije nego je radnja
   * postojala), a zaključan zapis ne nudi otključavanje onome tko ga ne smije otključati.
   */
  protected cellButtonDisabled(field: SchemaField, survey: Survey): boolean {
    if (!field.options.buttonAction) {
      return true;
    }
    return field.options.buttonAction === 'lock' && survey.locked && !this.canUnlock();
  }

  /** Tko je i kada zaključao - stoji kao opis retka, jer u ćeliju ne stane. */
  protected lockedTitle(survey: Survey): string {
    if (!survey.locked) {
      return '';
    }
    const who = survey.lockedBy ?? 'nepoznat korisnik';
    const when = survey.lockedAt === null ? '' : `, ${new Date(survey.lockedAt).toLocaleString('hr-HR')}`;
    return `Zaključao: ${who}${when}`;
  }

  protected closeHistory(): void {
    this.historySurveyId.set(null);
  }

  protected onEscape(): void {
    if (this.relatedFor() !== null) {
      this.closeRelated();
    } else if (this.historySurveyId() !== null) {
      this.closeHistory();
    } else if (this.showAddDialog()) {
      this.closeAddDialog();
    } else if (this.editingId() !== null) {
      this.cancelEdit();
    } else if (this.inlineAfterId() !== null) {
      this.cancelInlineAdd();
    }
  }
}

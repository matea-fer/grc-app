import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Codebook, CodebookItem } from '../codebook/codebook.model';
import { CodebookService } from '../codebook/codebook.service';
import {
  ButtonAction,
  ColumnDefinition,
  ColumnDefinitionInput,
  ColumnDefinitionType,
  ColumnOptions,
  DateMode,
  EMPTY_COLUMN_OPTIONS,
  NumberMode,
  OnTargetDelete,
  PickerMode
} from '../column-definition/column-definition.model';
import { ColumnDefinitionService } from '../column-definition/column-definition.service';
import { BulkDeleteDialog } from './bulk-delete-dialog';
import { Template } from '../template/template.model';
import { TemplateService } from '../template/template.service';
import { TenantService } from '../tenant/tenant.service';

/**
 * Editor obrazaca - za AKTIVNU firmu se upravlja njezinim templateima (obrascima)
 * i, za odabrani template, njegovim stupcima.
 *
 * Firma dolazi iz gornje trake; odabrani template dijeli se s ekranom "Podaci"
 * kroz {@link TemplateService}. Dok firma nije odabrana, ekran traži odabir.
 */
@Component({
  selector: 'app-schema-editor',
  imports: [FormsModule, BulkDeleteDialog],
  templateUrl: './schema-editor.html',
  styleUrl: './schema-editor.css',
  host: {
    '(document:keydown.escape)': 'onEscape()'
  }
})
export class SchemaEditor {
  private readonly columnDefinitionService = inject(ColumnDefinitionService);
  private readonly codebookService = inject(CodebookService);
  private readonly templateService = inject(TemplateService);
  private readonly tenantService = inject(TenantService);

  protected readonly activeCompanyId = this.tenantService.activeCompanyId;
  protected readonly templates = this.templateService.templates;
  protected readonly activeTemplateId = this.templateService.activeTemplateId;

  protected readonly columns = signal<ColumnDefinition[]>([]);
  /**
   * Redoslijed kakav stoji na serveru - prema njemu se zna je li premještanje nespremljeno.
   *
   * Premještanje se ne šalje odmah, po kliku: dovođenje pet stupaca na svoje mjesto bilo bi
   * pet poziva i pet redaka u dnevniku, a nijedan od njih ne bi bio odluka za sebe. Šalje se
   * jednom, kad je red gotov - isto kao kod stavki šifrarnika.
   */
  private readonly savedOrder = signal<string[]>([]);
  protected readonly savingOrder = signal(false);
  /** Šifrarnici koje ova firma smije koristiti: njezini vlastiti + svi globalni. */
  protected readonly codebooks = signal<Codebook[]>([]);
  /**
   * Stavke šifrarnika odabranog U DIJALOGU - služe samo padajućem izborniku za zadanu
   * vrijednost. Dohvaćaju se tek kad se šifrarnik odabere; popis šifrarnika ih ne nosi.
   */
  protected readonly codebookItems = signal<CodebookItem[]>([]);
  /** Greška koja pripada ekranu: neuspjelo učitavanje, brisanje, preimenovanje u retku. */
  protected readonly error = signal<string | null>(null);
  /**
   * Greška o onome što se upisuje u dijalogu. Ide zasebno jer bi inače završila na
   * stranici IZA zatamnjene pozadine - ondje gdje je korisnik ne vidi. Dijalog za
   * obrazac i onaj za stupac dijele je jer nikad nisu otvorena oba.
   */
  protected readonly dialogError = signal<string | null>(null);
  protected readonly success = signal<string | null>(null);
  private successTimer: ReturnType<typeof setTimeout> | null = null;

  // --- template forma ---
  protected readonly showTemplateDialog = signal(false);
  protected readonly newTemplateName = signal('');
  protected readonly editingTemplateId = signal<number | null>(null);
  protected readonly editTemplateName = signal('');

  // --- stupac forma (isti dijalog sluzi za dodavanje i uredivanje) ---
  // editingKey === null -> dodavanje novog; inace uredivanje stupca tog kljuca
  protected readonly showColumnDialog = signal(false);
  protected readonly editingKey = signal<string | null>(null);
  protected readonly newName = signal('');
  protected readonly newType = signal<ColumnDefinitionType>('string');
  protected readonly newCodebookId = signal<number | null>(null);
  protected readonly newLabel = signal('');
  protected readonly newDefaultValue = signal('');
  protected readonly newRequired = signal(false);
  protected readonly newUnique = signal(false);
  protected readonly newReadOnly = signal(false);
  protected readonly newWidth = signal<number | null>(null);

  // --- postavke ovisne o tipu ---
  // Drže se kao zasebni signali, a ne kao jedan objekt: predložak svaki od njih veže na
  // svoje polje, pa bi objekt značio prepisivanje cjeline pri svakoj promjeni jednog polja.
  // U jedan objekt se slažu tek pri slanju (buildOptions), i to samo oni koji tipu pripadaju.
  protected readonly newAutoIncrement = signal(false);
  protected readonly newNumberMode = signal<NumberMode>('float');
  protected readonly newMin = signal<number | null>(null);
  protected readonly newMax = signal<number | null>(null);
  protected readonly newNumberFormat = signal('');
  protected readonly newPattern = signal('');
  protected readonly newDateMode = signal<DateMode>('date');
  protected readonly newPickerMode = signal<PickerMode>('dropdown');
  protected readonly newMultiple = signal(false);
  protected readonly newAllowNewValues = signal(false);
  protected readonly newFormula = signal('');
  protected readonly newButtonLabel = signal('');
  // Zasad postoji jedna radnja, pa je i zadana - ali se BIRA, da dolazak druge ne mijenja
  // ništa osim popisa u izborniku. (Odonda su došle još tri.)
  protected readonly newButtonAction = signal<ButtonAction>('history');

  // --- veza na drugi obrazac ---
  protected readonly newTargetTemplateId = signal<number | null>(null);
  protected readonly newDisplayColumnKey = signal('');
  protected readonly newOnTargetDelete = signal<OnTargetDelete>('restrict');

  /**
   * Stupci obrasca odabranog kao cilj veze - iz njih se bira onaj koji služi kao naziv.
   *
   * Dohvaćaju se tek kad se cilj odabere, i to zato što pripadaju DRUGOM obrascu: Editor
   * inače radi samo sa stupcima onoga koji uređuje.
   */
  protected readonly targetColumns = signal<ColumnDefinition[]>([]);

  /**
   * Stupci koji smiju služiti kao naziv povezanog zapisa.
   *
   * Veza kao naziv veze otpada: naziv bi se razrješavao u lancu i mogao bi se vrtjeti u
   * krug. Gumb i datoteka otpadaju jer u zapisu ne drže nikakvu vrijednost.
   */
  protected readonly displayColumnChoices = computed(() =>
    this.targetColumns().filter(
      (column) =>
        column.columnType !== 'reference' && column.columnType !== 'button'
        && column.columnType !== 'file' && column.columnType !== 'link'
    )
  );

  /**
   * Stupac čiju vrijednost postavlja server - automatski redni broj i formula.
   *
   * Takvom stupcu se "samo za čitanje" ne bira nego proizlazi iz same postavke (backend
   * ga i nameće), pa se potvrdni okvir gasi umjesto da nudi izbor koji nema učinka.
   */
  protected readonly serverComputed = computed(
    () => this.newType() === 'formula' || (this.newType() === 'number' && this.newAutoIncrement())
  );

  /**
   * Stupac bez vlastite vrijednosti - pravila o sadržaju nad njim nemaju o čemu govoriti.
   *
   * Gumb nema što spremiti, a priložene datoteke žive u vlastitoj tablici; upisati ih i u
   * zapis značilo bi dva izvora istine koji se mogu raziići.
   */
  protected readonly valueless = computed(() => this.newType() === 'button' || this.newType() === 'file');

  /**
   * Tipovi čija je ćelija GUMB, pa im natpis dolazi iz sheme.
   *
   * „Poveznice" su ovdje iako vrijednost imaju: popis se ne ispisuje u ćeliju nego otvara u
   * dijalogu, pa je i ondje jedino što se vidi - gumb.
   */
  protected readonly buttonLike = computed(
    () => this.valueless() || this.newType() === 'link'
  );

  /** Naslov odsjeka s natpisom gumba - ovisi o tome čiji je gumb. */
  protected readonly optionsHeading = computed(() => {
    if (this.newType() === 'file') {
      return 'Datoteka';
    }
    return this.newType() === 'link' ? 'Poveznice' : 'Gumb';
  });

  protected readonly activeTemplateName = computed(() => {
    const id = this.activeTemplateId();
    return this.templates().find((t) => t.id === id)?.name ?? null;
  });

  /** Je li aktivni obrazac označen kao upitnik (retke definira admin, korisnik samo odgovara). */
  protected readonly isQuestionnaire = computed(() => {
    const id = this.activeTemplateId();
    return this.templates().find((t) => t.id === id)?.questionnaire ?? false;
  });

  /**
   * Uključi/isključi „upitnik" način. Kad je uključen, na ekranu Podaci korisnik ne dodaje ni
   * ne briše retke - samo bira odgovor (šifrarnik). Retke (pitanja) definira administrator.
   */
  protected toggleQuestionnaire(value: boolean): void {
    const id = this.activeTemplateId();
    if (id === null) {
      return;
    }
    this.templateService.setQuestionnaire(id, value).subscribe({
      next: () => {
        this.templateService.refresh().subscribe();
        this.notify(value ? 'Obrazac je sada upitnik.' : 'Obrazac više nije upitnik.');
      },
      error: this.failed('Promjena nije uspjela.')
    });
  }

  /**
   * Šifre koje se nude kao zadana vrijednost: AKTIVNE, plus ona koja je na stupcu već
   * spremljena.
   *
   * Isključena se ne nudi jer zadana vrijednost je upravo ponuda za nove unose (backend
   * je i odbija). Ali ona koja je već spremljena mora ostati vidljiva - inače bi
   * otvaranje dijaloga nad takvim stupcem prikazalo prazno i tiho je obrisalo pri
   * prvom spremanju, iako korisnik zadanu vrijednost uopće nije dirao.
   */
  protected readonly defaultValueOptions = computed(() => {
    const current = this.newDefaultValue().trim();
    return this.codebookItems().filter((item) => item.active || item.code === current);
  });

  constructor() {
    // promjena aktivne firme -> ponovno učitaj njezine templatee
    effect(() => {
      const companyId = this.activeCompanyId();
      if (companyId === null) {
        this.templateService.clear();
        this.codebooks.set([]);
      } else {
        this.reloadTemplates();
        this.reloadCodebooks();
      }
    });

    // promjena šifrarnika u dijalogu -> dohvati njegove stavke za izbornik zadane vrijednosti
    effect(() => {
      const codebookId = this.newCodebookId();
      if (codebookId === null) {
        this.codebookItems.set([]);
      } else {
        this.loadCodebookItems(codebookId);
      }
    });

    // promjena odabranog templatea -> učitaj njegove stupce i resetiraj forme
    effect(() => {
      const templateId = this.activeTemplateId();
      untracked(() => {
        this.resetColumnForm();
        this.cancelTemplateEdit();
      });
      if (templateId === null) {
        this.columns.set([]);
      } else {
        this.loadColumns(templateId);
      }
    });
  }

  // ===================== REDOSLIJED STUPACA =====================

  /** Stupac koji se upravo povlači; null kad se ne povlači ništa. */
  protected readonly draggedKey = signal<string | null>(null);

  /** Je li redoslijed promijenjen, a još nije poslan. */
  protected readonly orderChanged = computed(() => {
    const current = this.columns().map((column) => column.columnKey);
    const saved = this.savedOrder();
    return current.length === saved.length && current.some((key, index) => key !== saved[index]);
  });

  protected onDragStart(column: ColumnDefinition): void {
    this.draggedKey.set(column.columnKey);
  }

  protected onDragEnd(): void {
    this.draggedKey.set(null);
  }

  /**
   * Bez ovoga preglednik ne dopušta ispuštanje - zadano ponašanje dragovera je
   * „ovdje se ne smije ispustiti", pa ga se mora izričito poništiti.
   */
  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
  }

  protected onDrop(event: DragEvent, target: ColumnDefinition): void {
    event.preventDefault();
    const dragged = this.draggedKey();
    this.draggedKey.set(null);
    if (dragged === null || dragged === target.columnKey) {
      return;
    }
    this.moveTo(dragged, this.indexOf(target));
  }

  /**
   * Pomicanje strelicama - povlačenje se ne može odraditi tipkovnicom, pa bi bez ovoga
   * redoslijed bio nedostupan onome tko miš ne koristi. Ista odluka kao kod šifrarnika.
   */
  protected moveBy(column: ColumnDefinition, offset: number): void {
    this.moveTo(column.columnKey, this.indexOf(column) + offset);
  }

  protected indexOf(column: ColumnDefinition): number {
    return this.columns().findIndex((c) => c.columnKey === column.columnKey);
  }

  private moveTo(columnKey: string, to: number): void {
    const columns = [...this.columns()];
    const from = columns.findIndex((column) => column.columnKey === columnKey);
    if (from === -1 || to < 0 || to >= columns.length || from === to) {
      return;
    }
    const [moved] = columns.splice(from, 1);
    columns.splice(to, 0, moved);
    this.columns.set(columns);
  }

  /** Vrati popis na ono što stoji na serveru - bez ijednog poziva, redoslijed je samo lokalan. */
  protected cancelOrder(): void {
    const saved = this.savedOrder();
    this.columns.set(
      saved
        .map((key) => this.columns().find((column) => column.columnKey === key))
        .filter((column): column is ColumnDefinition => column !== undefined)
    );
  }

  protected saveOrder(): void {
    const templateId = this.activeTemplateId();
    if (templateId === null || this.savingOrder()) {
      return;
    }
    this.error.set(null);
    this.savingOrder.set(true);
    const keys = this.columns().map((column) => column.columnKey);
    this.columnDefinitionService.reorder(templateId, keys).subscribe({
      next: (columns) => {
        this.savingOrder.set(false);
        // popis se preuzima od servera, a ne od nas: jedino tako se vidi ako je netko u
        // međuvremenu shemu promijenio
        this.columns.set(columns);
        this.savedOrder.set(columns.map((column) => column.columnKey));
        this.success.set('Redoslijed stupaca je spremljen.');
      },
      error: (response) => {
        this.savingOrder.set(false);
        this.error.set(response?.error?.message ?? 'Spremanje redoslijeda nije uspjelo.');
      }
    });
  }

  /**
   * Skupno brisanje stoji uz pojedinacno, ne umjesto njega: ikona u retku i dalje brise
   * jedan obrazac. Zajedno se brisu oni koji se pojedinacno ne daju - par koji pokazuje
   * jedan na drugoga.
   */
  protected readonly showBulkDelete = signal(false);

  protected onBulkDeleted(count: number): void {
    this.reloadTemplates();
    this.notify(`Obrisano ${count} ${count === 1 ? 'obrazac' : 'obrazaca'}.`);
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

  /**
   * Šifrarnici koje odabrana firma smije koristiti - svi njezini + svi globalni.
   *
   * Doseg odlučuje backend iz tenant konteksta, pa se ovdje ne filtrira: firmin obrazac
   * smije ČITATI globalni šifrarnik iako ga ne smije mijenjati.
   */
  private reloadCodebooks(): void {
    const requestedFor = this.activeCompanyId();
    this.codebookService.list(null, '').subscribe({
      next: (data) => {
        if (this.activeCompanyId() === requestedFor) {
          this.codebooks.set(data);
        }
      },
      error: () => {
        if (this.activeCompanyId() === requestedFor) {
          this.error.set('Dohvaćanje šifrarnika nije uspjelo.');
        }
      }
    });
  }

  /**
   * Stavke šifrarnika odabranog u dijalogu.
   *
   * Neuspjeh ne prijavljuje grešku nego ostavlja prazan izbornik: zadana vrijednost je
   * neobavezna, pa nema razloga crvenom porukom prekidati definiranje stupca. Odgovor za
   * šifrarnik koji više nije odabran se odbacuje - inače bi sporiji raniji zahtjev
   * pregazio ispravan popis.
   */
  private loadCodebookItems(codebookId: number): void {
    this.codebookService.getItems(codebookId).subscribe({
      next: (items) => {
        if (this.newCodebookId() === codebookId) {
          this.codebookItems.set(items);
        }
      },
      error: () => {
        if (this.newCodebookId() === codebookId) {
          this.codebookItems.set([]);
        }
      }
    });
  }

  private loadColumns(templateId: number): void {
    this.error.set(null);
    this.columnDefinitionService.getForTemplate(templateId).subscribe({
      next: (data) => {
        if (!this.isStale(templateId)) {
          this.columns.set(data);
          // svježe učitan popis je po definiciji onaj koji stoji na serveru
          this.savedOrder.set(data.map((column) => column.columnKey));
        }
      },
      error: () => {
        if (!this.isStale(templateId)) {
          this.error.set('Dohvaćanje stupaca nije uspjelo.');
        }
      }
    });
  }

  /**
   * Je li odgovor stigao za obrazac koji više nije odabran.
   *
   * Promjena firme ostavi u zraku zahtjev za obrazac PRETHODNE firme; on završi kao 404
   * (tuđi obrazac se ne otkriva), i to zna stići tek nakon što je ispravan obrazac već
   * učitan. Pusti li se takav odgovor u stanje ekrana, korisnik gleda točne stupce uz
   * poruku da dohvaćanje nije uspjelo.
   */
  private isStale(templateId: number): boolean {
    return this.activeTemplateId() !== templateId;
  }

  private failed(fallback: string): (error: unknown) => void {
    return (error: unknown) => {
      this.success.set(null);
      this.error.set(this.messageOf(error, fallback));
    };
  }

  /** Isto, ali poruka ostaje u dijalogu iz kojeg je akcija krenula. */
  private failedInDialog(fallback: string): (error: unknown) => void {
    return (error: unknown) => {
      this.success.set(null);
      this.dialogError.set(this.messageOf(error, fallback));
    };
  }

  private messageOf(error: unknown, fallback: string): string {
    return (error as { error?: { message?: string } })?.error?.message ?? fallback;
  }

  /** Esc zatvara dijalog koji je otvoren. */
  protected onEscape(): void {
    if (this.showTemplateDialog()) {
      this.closeTemplateDialog();
    } else if (this.showColumnDialog()) {
      this.closeColumnDialog();
    }
  }

  private notify(message: string): void {
    this.error.set(null);
    this.success.set(message);
    if (this.successTimer !== null) {
      clearTimeout(this.successTimer);
    }
    this.successTimer = setTimeout(() => this.success.set(null), 3000);
  }

  // ===================== TEMPLATEI =====================

  protected openTemplateDialog(): void {
    this.newTemplateName.set('');
    this.dialogError.set(null);
    this.showTemplateDialog.set(true);
  }

  protected closeTemplateDialog(): void {
    this.showTemplateDialog.set(false);
    this.dialogError.set(null);
  }

  protected addTemplate(): void {
    const name = this.newTemplateName().trim();
    if (!name) {
      this.dialogError.set('Naziv obrasca je obavezan.');
      return;
    }
    this.dialogError.set(null);
    this.templateService.create(name).subscribe({
      next: (created) => {
        this.closeTemplateDialog();
        this.notify(`Obrazac "${name}" dodan.`);
        // osvježi popis pa odaberi novi (učitavanje stupaca kreće kroz effect)
        this.templateService.refresh().subscribe({
          next: () => this.templateService.setActive(created.id)
        });
      },
      error: this.failedInDialog('Dodavanje obrasca nije uspjelo.')
    });
  }

  protected selectTemplate(id: number): void {
    this.templateService.setActive(id);
  }

  protected startEditTemplate(template: Template): void {
    this.editingTemplateId.set(template.id);
    this.editTemplateName.set(template.name);
  }

  protected cancelTemplateEdit(): void {
    this.editingTemplateId.set(null);
  }

  protected saveTemplateEdit(id: number): void {
    const name = this.editTemplateName().trim();
    if (!name) {
      this.success.set(null);
      this.error.set('Naziv obrasca je obavezan.');
      return;
    }
    this.error.set(null);
    this.templateService.rename(id, name).subscribe({
      next: () => {
        this.editingTemplateId.set(null);
        this.notify(`Obrazac preimenovan u "${name}".`);
        this.reloadTemplates();
      },
      error: this.failed('Preimenovanje obrasca nije uspjelo.')
    });
  }

  protected removeTemplate(template: Template): void {
    if (!confirm(`Obrisati obrazac "${template.name}"? Brišu se svi njegovi stupci i zapisi.`)) {
      return;
    }
    this.error.set(null);
    this.templateService.delete(template.id).subscribe({
      next: () => {
        // ako je bio aktivan, refresh (reconcile) prebaci odabir na prvi preostali
        if (this.activeTemplateId() === template.id) {
          this.templateService.setActive(null);
        }
        this.notify(`Obrazac "${template.name}" obrisan.`);
        this.reloadTemplates();
      },
      error: this.failed('Brisanje obrasca nije uspjelo.')
    });
  }

  // ===================== STUPCI =====================

  /**
   * Promjena tipa odbacuje odabrani šifrarnik.
   *
   * Bez toga bi stupac prebačen npr. na "Tekst" zadržao skriven codebookId i backend bi ga
   * odbio porukom o šifrarniku - a korisnik u tom trenutku uopće ne gleda šifrarnik.
   *
   * Zadana vrijednost se briše samo kad šifrarnik ulazi ili izlazi iz igre: šifra u
   * tekstualnom stupcu (i obrnuto) ne znači ništa. Kod ostalih promjena tipa se zadržava -
   * neispravnu će javiti provjera, a prepisivanje onoga što je korisnik upisao je gore.
   */
  protected onTypeChange(type: ColumnDefinitionType): void {
    const wasCodebook = this.newType() === 'codebook';
    this.newType.set(type);
    if (type !== 'codebook') {
      this.newCodebookId.set(null);
    }
    if (wasCodebook !== (type === 'codebook')) {
      this.newDefaultValue.set('');
    }
    // Postavke starog tipa ne prenose se na novi: raspon brojeva na tekstu (ili uzorak na
    // datumu) backend odbija, a korisnik u tom trenutku uopće ne gleda to polje - vidio bi
    // grešku o postavci koju nije ni dirao.
    this.resetOptionsForm();
    if (type === 'formula' || type === 'button' || type === 'file' || type === 'reference'
        || type === 'link') {
      // veza se bira po zapisu, a poveznice su popis - predpopunjena vrijednost nema smisla
      this.newDefaultValue.set('');
    }
    if (type === 'formula' || type === 'button' || type === 'file' || type === 'link') {
      this.newUnique.set(false);
    }
    if (type === 'button' || type === 'file') {
      this.newRequired.set(false);
    }
  }

  /** Automatski redni broj sam određuje ostatak stupca - ostalo bi mu samo proturječilo. */
  protected onAutoIncrementChange(enabled: boolean): void {
    this.newAutoIncrement.set(enabled);
    if (enabled) {
      this.newNumberMode.set('int');
      this.newDefaultValue.set('');
    }
  }

  /** Padajući izbornik ne prikazuje više odabranih vrijednosti - vidi provjeru na backendu. */
  protected onMultipleChange(multiple: boolean): void {
    this.newMultiple.set(multiple);
    if (multiple) {
      this.newUnique.set(false);
      if (this.newPickerMode() === 'dropdown') {
        this.newPickerMode.set('checkbox');
      }
    }
  }

  /** Zadana vrijednost je šifra IZ odabranog šifrarnika - u drugom ne znači ništa. */
  protected onCodebookChange(codebookId: number | null): void {
    if (codebookId !== this.newCodebookId()) {
      this.newDefaultValue.set('');
    }
    this.newCodebookId.set(codebookId);
  }

  /** Naziv šifrarnika za tablicu stupaca; "-" kad stupac nije vezan ni na jedan. */
  protected codebookName(codebookId: number | null): string {
    if (codebookId === null) {
      return '-';
    }
    return this.codebooks().find((codebook) => codebook.id === codebookId)?.name ?? `id=${codebookId}`;
  }

  protected typeLabel(type: ColumnDefinitionType): string {
    switch (type) {
      case 'string':
        return 'Tekst';
      case 'number':
        return 'Broj';
      case 'date':
        return 'Datum';
      case 'codebook':
        return 'Iz šifrarnika';
      case 'formula':
        return 'Formula';
      case 'button':
        return 'Gumb';
      case 'file':
        return 'Datoteka';
      case 'reference':
        return 'Veza na obrazac';
      case 'link':
        return 'Poveznice';
      default:
        return type;
    }
  }

  /**
   * Kratak opis postavki stupca za tablicu.
   *
   * Nabraja se ono što mijenja PONAŠANJE (što se smije upisati, tko upisuje), a ne ono što
   * mijenja izgled - širina i format broja bi u pregledu stupaca bili šum.
   */
  protected optionsSummary(column: ColumnDefinition): string {
    const options = column.options;
    const parts: string[] = [];
    if (options.autoIncrement) {
      parts.push('automatski broj');
    }
    if (options.numberMode === 'int') {
      parts.push('cijeli broj');
    }
    if (options.min !== null || options.max !== null) {
      parts.push(`raspon ${options.min ?? ''}..${options.max ?? ''}`);
    }
    if (options.pattern) {
      parts.push(`uzorak ${options.pattern}`);
    }
    if (options.dateMode === 'datetime') {
      parts.push('s vremenom');
    }
    if (column.columnType === 'codebook') {
      parts.push(this.pickerLabel(options.pickerMode));
    }
    if (options.multiple) {
      parts.push('višestruko');
    }
    if (options.allowNewValues) {
      parts.push('slobodan unos');
    }
    if (options.formula) {
      parts.push(options.formula);
    }
    if (options.targetTemplateId !== null) {
      parts.push(`→ ${this.templateName(options.targetTemplateId)}`);
      parts.push(options.onTargetDelete === 'clear' ? 'brisanje prekida vezu' : 'brisanje zabranjeno');
    }
    return parts.join(', ') || '-';
  }

  /** Naziv obrasca za pregled stupaca; sam id ne bi rekao ništa onome tko tablicu čita. */
  protected templateName(templateId: number): string {
    return this.templates().find((template) => template.id === templateId)?.name ?? `id=${templateId}`;
  }

  private pickerLabel(mode: PickerMode | null): string {
    switch (mode) {
      case 'checkbox':
        return 'okviri';
      case 'popup':
        return 'dijalog';
      default:
        return 'izbornik';
    }
  }

  private resetColumnForm(): void {
    this.showColumnDialog.set(false);
    this.dialogError.set(null);
    this.editingKey.set(null);
    this.newName.set('');
    this.newType.set('string');
    this.newCodebookId.set(null);
    this.newLabel.set('');
    this.newDefaultValue.set('');
    this.newRequired.set(false);
    this.newUnique.set(false);
    this.newReadOnly.set(false);
    this.newWidth.set(null);
    this.resetOptionsForm();
  }

  /** Postavke ovisne o tipu vraćene na zadano - vidi {@link onTypeChange}. */
  private resetOptionsForm(): void {
    this.newAutoIncrement.set(false);
    this.newNumberMode.set('float');
    this.newMin.set(null);
    this.newMax.set(null);
    this.newNumberFormat.set('');
    this.newPattern.set('');
    this.newDateMode.set('date');
    this.newPickerMode.set('dropdown');
    this.newMultiple.set(false);
    this.newAllowNewValues.set(false);
    this.newFormula.set('');
    this.newButtonLabel.set('');
    this.newButtonAction.set('history');
    this.newTargetTemplateId.set(null);
    this.newDisplayColumnKey.set('');
    this.newOnTargetDelete.set('restrict');
    this.targetColumns.set([]);
  }

  /**
   * Odabran je obrazac na koji veza pokazuje - dohvati njegove stupce.
   *
   * Stupac za naziv se pritom briše: naziv je ključ IZ ciljanog obrasca, pa u drugom
   * obrascu ne znači ništa. Isto pravilo kao kod promjene šifrarnika i zadane vrijednosti.
   */
  protected onTargetTemplateChange(templateId: number | null): void {
    if (templateId !== this.newTargetTemplateId()) {
      this.newDisplayColumnKey.set('');
    }
    this.newTargetTemplateId.set(templateId);
    this.loadColumnsOf(templateId, this.targetColumns);
  }

  private loadColumnsOf(templateId: number | null, into: { set(value: ColumnDefinition[]): void }): void {
    if (templateId === null) {
      into.set([]);
      return;
    }
    this.columnDefinitionService.getForTemplate(templateId).subscribe({
      next: (columns) => into.set(columns),
      // Prazan popis nije tiha greška: izbornik tada nema što ponuditi, pa se stupac ne da
      // ni spremiti (provjera traži odabran stupac). Bolje nego zaustaviti cijeli dijalog.
      error: () => into.set([])
    });
  }

  /**
   * Postavke složene u jedan objekt, i to samo one koje odabranom tipu pripadaju.
   *
   * Filtriranje po tipu nije opreznost nego pravilo: backend odbija postavku koja se tipu
   * ne tiče, jer bi inače ostala u shemi kao tvrdnja koju ništa ne provodi - i oživjela
   * čim se stupcu jednom promijeni tip.
   */
  private buildOptions(type: ColumnDefinitionType): ColumnOptions {
    const options: ColumnOptions = { ...EMPTY_COLUMN_OPTIONS };
    switch (type) {
      case 'number':
        options.autoIncrement = this.newAutoIncrement();
        options.numberMode = this.newAutoIncrement() ? 'int' : this.newNumberMode();
        options.min = this.newMin();
        options.max = this.newMax();
        options.numberFormat = this.newNumberFormat().trim() || null;
        options.pattern = this.newPattern().trim() || null;
        break;
      case 'string':
        options.pattern = this.newPattern().trim() || null;
        break;
      case 'date':
        options.dateMode = this.newDateMode();
        break;
      case 'codebook':
        options.pickerMode = this.newPickerMode();
        options.multiple = this.newMultiple();
        options.allowNewValues = this.newAllowNewValues();
        break;
      case 'formula':
        options.formula = this.newFormula().trim() || null;
        break;
      // natpis na gumbu u ćeliji dijele oba stupca bez vrijednosti
      case 'button':
        options.buttonLabel = this.newButtonLabel().trim() || null;
        // radnja je samo gumbova - stupac za prilaganje već ima svoju (otvara odabir datoteke)
        options.buttonAction = this.newButtonAction();
        break;
      case 'file':
      case 'link':
        options.buttonLabel = this.newButtonLabel().trim() || null;
        break;
      case 'reference':
        options.targetTemplateId = this.newTargetTemplateId();
        options.displayColumnKey = this.newDisplayColumnKey().trim() || null;
        options.onTargetDelete = this.newOnTargetDelete();
        options.multiple = this.newMultiple();
        break;
    }
    return options;
  }

  /** Dijalog za nov stupac - prazan obrazac, bez ključa koji bi značio uređivanje. */
  protected openColumnDialog(): void {
    this.resetColumnForm();
    this.showColumnDialog.set(true);
  }

  protected closeColumnDialog(): void {
    this.resetColumnForm();
  }

  protected startEditColumn(column: ColumnDefinition): void {
    this.resetColumnForm();
    this.showColumnDialog.set(true);
    this.editingKey.set(column.columnKey);
    this.newName.set(column.columnKey);
    this.newType.set(column.columnType);
    this.newCodebookId.set(column.codebookId);
    this.newLabel.set(column.label ?? '');
    this.newDefaultValue.set(column.defaultValue ?? '');
    this.newRequired.set(column.required);
    this.newUnique.set(column.unique);
    this.newReadOnly.set(column.readOnly);
    this.newWidth.set(column.width);

    const options = column.options;
    this.newAutoIncrement.set(options.autoIncrement);
    this.newNumberMode.set(options.numberMode ?? 'float');
    this.newMin.set(options.min);
    this.newMax.set(options.max);
    this.newNumberFormat.set(options.numberFormat ?? '');
    this.newPattern.set(options.pattern ?? '');
    this.newDateMode.set(options.dateMode ?? 'date');
    this.newPickerMode.set(options.pickerMode ?? 'dropdown');
    this.newMultiple.set(options.multiple);
    this.newAllowNewValues.set(options.allowNewValues);
    this.newFormula.set(options.formula ?? '');
    this.newButtonLabel.set(options.buttonLabel ?? '');
    this.newButtonAction.set(options.buttonAction ?? 'history');
    this.newTargetTemplateId.set(options.targetTemplateId);
    this.newDisplayColumnKey.set(options.displayColumnKey ?? '');
    this.newOnTargetDelete.set(options.onTargetDelete ?? 'restrict');
    // stupci drugih obrazaca ne stižu uz sam stupac - izbornici bi inače bili prazni,
    // pa bi uređivanje postojeće veze izgledalo kao da je odabir izgubljen
    this.loadColumnsOf(options.targetTemplateId, this.targetColumns);
  }

  /**
   * Ista pravila koja brani i backend - ovdje samo zato da korisnik odgovor dobije
   * odmah, bez odlaska na server. Backend ostaje mjerodavan.
   *
   * @return poruka o problemu, ili null ako je definicija u redu
   */
  private columnFormProblem(columnType: ColumnDefinitionType, codebookId: number | null): string | null {
    if (columnType === 'codebook' && codebookId === null) {
      return 'Odaberi šifrarnik iz kojeg dolaze vrijednosti.';
    }
    if (columnType === 'formula' && this.newFormula().trim() === '') {
      return 'Upiši formulu, npr. [cijena] * [kolicina].';
    }
    if (columnType === 'reference') {
      if (this.newTargetTemplateId() === null) {
        return 'Odaberi obrazac na čije zapise stupac pokazuje.';
      }
      if (this.newDisplayColumnKey().trim() === '') {
        return 'Odaberi koji se stupac tog obrasca prikazuje umjesto broja zapisa.';
      }
    }

    const optionsProblem = this.optionsProblem(columnType);
    if (optionsProblem !== null) {
      return optionsProblem;
    }

    const hasDefault = this.newDefaultValue().trim() !== '';
    if (hasDefault) {
      const problem = this.defaultValueProblem(columnType, codebookId);
      if (problem !== null) {
        return problem;
      }
    }
    if (this.newUnique() && hasDefault) {
      return 'Jedinstven stupac ne može imati zadanu vrijednost - drugi zapis bi odmah bio duplikat.';
    }
    // automatski redni broj i formula su readOnly, ali vrijednost ne dolazi iz zadane
    // vrijednosti nego je računa server - njima obaveznost ne treba ništa predpopunjeno
    if (this.newRequired() && this.newReadOnly() && !hasDefault && !this.serverComputed()) {
      return 'Obavezan stupac koji se ne može uređivati mora imati zadanu vrijednost.';
    }
    return null;
  }

  /**
   * Ista pravila o postavkama koja brani i backend - ovdje samo da odgovor stigne odmah.
   *
   * Uzorak se provjerava tako da se pokuša sastaviti: neispravan bi se pri upisu retka tiho
   * preskakao, pa bi stupac izgledao kao da ima provjeru koja se nikad ne uključuje.
   *
   * Preglednik i backend se pritom ne slažu u svemu - "^\d{5" je u JavaScriptu ispravan
   * izraz, a u Javi nije. Ova provjera zato hvata samo očito; mjerodavan ostaje backend.
   */
  private optionsProblem(columnType: ColumnDefinitionType): string | null {
    if (columnType === 'string' || columnType === 'number') {
      const pattern = this.newPattern().trim();
      if (pattern !== '') {
        try {
          new RegExp(pattern);
        } catch {
          return `Uzorak "${pattern}" nije ispravan regularni izraz.`;
        }
      }
    }
    if (columnType === 'number') {
      const min = this.newMin();
      const max = this.newMax();
      if (min !== null && max !== null && min > max) {
        return 'Najmanja vrijednost ne može biti veća od najveće.';
      }
    }
    const width = this.newWidth();
    if (width !== null && (width < 40 || width > 2000)) {
      return 'Širina stupca mora biti između 40 i 2000 piksela.';
    }
    return null;
  }

  /**
   * Zadana vrijednost se upisuje kao tekst za svaki tip, pa mora odgovarati tom tipu.
   *
   * Kod šifrarničkog stupca se ovdje NE provjerava je li tekst stvarna šifra - stavke
   * ovaj ekran ne dohvaća. Tu provjeru radi backend, uz poruku koja imenuje šifrarnik.
   */
  private defaultValueProblem(columnType: ColumnDefinitionType, codebookId: number | null): string | null {
    const value = this.newDefaultValue().trim();
    switch (columnType) {
      case 'number':
        return Number.isNaN(Number(value)) ? `Zadana vrijednost "${value}" nije broj.` : null;
      case 'date':
        return /^\d{4}-\d{2}-\d{2}$/.test(value)
          ? null
          : 'Zadana vrijednost datuma mora biti u obliku YYYY-MM-DD.';
      default:
        return null;
    }
  }

  protected submit(): void {
    const templateId = this.activeTemplateId();
    if (templateId === null) {
      return;
    }
    const columnKey = this.newName().trim();
    if (!columnKey) {
      this.dialogError.set('Naziv stupca je obavezan.');
      return;
    }
    const columnType = this.newType();
    const codebookId = columnType === 'codebook' ? this.newCodebookId() : null;
    const problem = this.columnFormProblem(columnType, codebookId);
    if (problem !== null) {
      this.dialogError.set(problem);
      return;
    }

    this.dialogError.set(null);
    const label = this.newLabel().trim();
    const defaultValue = this.newDefaultValue().trim();
    const payload = {
      columnKey,
      columnType,
      codebookId,
      label: label || null,
      required: this.newRequired(),
      unique: this.newUnique(),
      defaultValue: defaultValue || null,
      // vrijednost koju postavlja server se ne unosi; backend to isto nameće, ali stupac
      // ne bi smio ni otputovati u stanju koje samom sebi proturječi
      readOnly: this.newReadOnly() || this.serverComputed(),
      width: this.newWidth(),
      options: this.buildOptions(columnType)
    };
    const editingKey = this.editingKey();
    if (editingKey === null) {
      this.columnDefinitionService.create(templateId, payload).subscribe({
        next: () => {
          this.loadColumns(templateId);
          this.resetColumnForm();
          this.notify(`Stupac "${columnKey}" dodan.`);
        },
        error: this.failedInDialog('Dodavanje stupca nije uspjelo.')
      });
      return;
    }
    this.saveColumnEdit(templateId, editingKey, columnKey, payload, false);
  }

  /**
   * Spremanje izmjene stupca, uz pitanje kad izmjena zapisima uzima vrijednosti.
   *
   * Backend takvu izmjenu prvo ODBIJE (409, oznaka `COLUMN_DATA_LOSS`) i u poruci javi koliko
   * zapisa ostaje bez vrijednosti; ovdje se ta poruka pokaže i, ako korisnik potvrdi, poziv se
   * ponovi s potvrdom. Do 12.08.2026. je izmjena tiho brisala vrijednosti - „promijenio sam
   * uzorak" i „120 zapisa je ostalo bez vrijednosti" bili su isti klik.
   */
  private saveColumnEdit(templateId: number, oldKey: string, columnKey: string,
                         payload: ColumnDefinitionInput, confirmed: boolean): void {
    this.columnDefinitionService.update(templateId, oldKey, payload, confirmed).subscribe({
      next: () => {
        this.loadColumns(templateId);
        this.resetColumnForm();
        this.notify(`Stupac "${columnKey}" ažuriran.`);
      },
      error: (error: unknown) => {
        if (SchemaEditor.isDataLoss(error)) {
          const message = this.messageOf(error, '');
          // pitanje se postavlja SAMO ovdje; drugi 409 (npr. duplikat naziva) ide u poruku
          if (confirm(`${message}

Nastaviti?`)) {
            this.saveColumnEdit(templateId, oldKey, columnKey, payload, true);
            return;
          }
          this.dialogError.set('Izmjena je odustala - podaci su ostali nedirnuti.');
          return;
        }
        this.dialogError.set(this.messageOf(error, 'Uređivanje stupca nije uspjelo.'));
      }
    });
  }

  /**
   * Je li greška ona koja traži potvrdu.
   *
   * Prepoznaje se po strojnoj oznaci, ne po statusu ni po tekstu: 409 vraća i duplikat naziva
   * stupca (gdje pitanje nema smisla), a tekst se smije mijenjati bez da se pokvari ponašanje.
   */
  private static isDataLoss(error: unknown): boolean {
    return (error as { error?: { code?: string } })?.error?.code === 'COLUMN_DATA_LOSS';
  }

  protected removeColumn(column: ColumnDefinition): void {
    const templateId = this.activeTemplateId();
    if (templateId === null) {
      return;
    }
    if (!confirm(`Obrisati stupac "${column.columnKey}"? Brišu se i sve upisane vrijednosti u ovom obrascu.`)) {
      return;
    }
    this.error.set(null);
    this.columnDefinitionService.delete(templateId, column.columnKey).subscribe({
      next: () => {
        // stupac koji se uređivao je nestao - vrati formu u dodavanje
        if (this.editingKey() === column.columnKey) {
          this.resetColumnForm();
        }
        this.loadColumns(templateId);
        this.notify(`Stupac "${column.columnKey}" obrisan.`);
      },
      error: this.failed('Brisanje stupca nije uspjelo.')
    });
  }
}

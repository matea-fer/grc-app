import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Company } from '../company/company.model';
import { Template } from '../template/template.model';
import { TemplateTransferService, TransferPlan, TransferRequest } from './template-transfer.service';

/**
 * Prijenos obrazaca iz jedne firme u drugu.
 *
 * Dijalog ima TRI koraka i to je njegova cijela poanta: odabir → najava → prijenos. Najava
 * nije uljudnost nego nužda - posljedice se iz samog odabira ne vide. Isti obrazac u drugoj
 * firmi može dobiti drugi naziv, šifrarnik se može stvoriti ili podijeliti s postojećim, a
 * veza na obrazac koji nije označen ostaje bez cilja. Sve troje se dogodi tiho.
 *
 * Prenosi se samo definicija - obrasci, stupci i šifrarnici. Zapisi ostaju gdje su.
 */
@Component({
  selector: 'app-template-transfer-dialog',
  imports: [FormsModule],
  template: `
    <div class="modal-backdrop" (click)="closed.emit()">
      <div class="survey-form-card modal-dialog transfer-dialog" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h3>Prijenos obrazaca</h3>
          <button type="button" class="icon-button" title="Zatvori" (click)="closed.emit()">✕</button>
        </div>

        <div class="form-actions">
          <button type="button" class="secondary" (click)="closed.emit()">Odustani</button>
          @if (plan() === null) {
            <button
              type="button"
              class="primary"
              [disabled]="!canPreview() || busy()"
              (click)="showPlan()"
            >{{ busy() ? 'Računam…' : 'Pokaži što će se dogoditi' }}</button>
          } @else {
            <button type="button" class="primary" [disabled]="busy()" (click)="apply()">
              {{ busy() ? 'Prenosim…' : 'Prenesi' }}
            </button>
          }
        </div>

        @if (error(); as message) {
          <p class="error dialog-error">{{ message }}</p>
        }

        <!-- ===================== 1. odabir ===================== -->
        <div class="form-grid">
          <label class="form-field">
            <span class="form-field-label">Iz firme</span>
            <select [ngModel]="sourceId()" (ngModelChange)="onSourceChange($event)">
              <option [ngValue]="null">— odaberi —</option>
              @for (company of companies(); track company.id) {
                <option [ngValue]="company.id">{{ company.name }}</option>
              }
            </select>
          </label>

          <label class="form-field">
            <span class="form-field-label">U firmu</span>
            <select [ngModel]="targetId()" (ngModelChange)="onTargetChange($event)">
              <option [ngValue]="null">— odaberi —</option>
              @for (company of targetChoices(); track company.id) {
                <option [ngValue]="company.id">{{ company.name }}</option>
              }
            </select>
          </label>
        </div>

        @if (sourceId() !== null) {
          <h4 class="section-heading">Obrasci</h4>
          @if (templates().length === 0) {
            <p class="field-hint">Ta firma nema nijedan obrazac.</p>
          } @else {
            <div class="choice-group transfer-list">
              @for (template of templates(); track template.id) {
                <label class="choice">
                  <input
                    type="checkbox"
                    [checked]="isChosen(template.id)"
                    (change)="toggle(template.id)"
                  />
                  <span>{{ template.name }}</span>
                </label>
              }
            </div>
            <button type="button" class="cell-button" (click)="toggleAll()">
              {{ allChosen() ? 'Odznači sve' : 'Označi sve' }}
            </button>
          }
        }

        <!-- ===================== 2. najava ===================== -->
        @if (plan(); as p) {
          <h4 class="section-heading">Što će se dogoditi</h4>

          @if (looksLikeRepeat()) {
            <p class="transfer-note transfer-warn-block">
              <strong>Izgleda kao ponovljeni prijenos.</strong> Ciljna firma već ima obrazac
              istog naziva za <em>svaki</em> označeni obrazac, pa će svi nastati kao kopije.
              Ako si ovo već jednom prenijela, odustani — inače ćeš dobiti drugi komplet.
            </p>
          }

          <p class="transfer-note">
            Prenose se samo obrasci, stupci i šifrarnici. <strong>Zapisi se ne prenose</strong> —
            ostaju u izvornoj firmi.
          </p>

          <table class="transfer-table">
            <thead>
              <tr><th>Obrazac</th><th>Nastaje kao</th><th>Stupaca</th></tr>
            </thead>
            <tbody>
              @for (t of p.templates; track t.sourceId) {
                <tr>
                  <td>{{ t.sourceName }}</td>
                  <td>
                    {{ t.targetName }}
                    @if (t.renamed) {
                      <span class="transfer-warn">naziv je bio zauzet</span>
                    }
                  </td>
                  <td>{{ t.columnCount }}</td>
                </tr>
              }
            </tbody>
          </table>

          @if (p.codebooks.length > 0) {
            <h4 class="section-heading">Šifrarnici</h4>
            <table class="transfer-table">
              <thead>
                <tr><th>Šifrarnik</th><th>Što se događa</th><th>Stavki</th></tr>
              </thead>
              <tbody>
                @for (c of p.codebooks; track c.name) {
                  <tr>
                    <td>{{ c.name }}</td>
                    <td>
                      @if (c.scope === 'GLOBAL') {
                        globalni — dijele ga sve firme, ne kopira se
                      } @else if (c.reused) {
                        ciljna firma ga već ima — stupci se vežu na postojeći
                      } @else {
                        stvara se nov, sa stavkama
                      }
                    </td>
                    <td>{{ c.itemCount }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }

          @if (p.brokenReferences.length > 0) {
            <h4 class="section-heading">Veze koje ostaju bez cilja</h4>
            <p class="transfer-note transfer-warn-block">
              Ovi stupci pokazuju na obrazac koji nisi označila. Prenose se, ali bez veze —
              označi i te obrasce ako je želiš zadržati.
            </p>
            <ul class="transfer-broken">
              @for (b of p.brokenReferences; track b.templateName + b.columnKey) {
                <li><strong>{{ b.templateName }}</strong> · stupac „{{ b.columnKey }}" → {{ b.targetName }}</li>
              }
            </ul>
          }
        }
      </div>
    </div>
  `,
  styles: `
    .transfer-dialog {
      max-width: 46rem;
      width: 100%;
    }

    /* Popis obrazaca zna narasti; pomiče se unutar dijaloga, kao i kod odabira šifrarnika. */
    .transfer-list {
      max-height: 14rem;
      overflow-y: auto;
      margin-bottom: 0.75rem;
      padding: 0.5rem 0;
    }

    .section-heading {
      margin: 1.5rem 0 0.5rem;
      font-size: 14px;
      color: var(--boja-tekst-prigusen);
    }

    .transfer-note {
      margin: 0 0 0.75rem;
      font-size: 13px;
      color: var(--boja-tekst-prigusen);
    }

    .transfer-table {
      width: 100%;
      border-collapse: collapse;
    }

    .transfer-table th,
    .transfer-table td {
      text-align: left;
      padding: 4px 10px;
      border-bottom: 1px solid var(--boja-rub);
      white-space: normal;
    }

    /* Ono što se mijenja bez pitanja mora se vidjeti kao upozorenje, ne kao usputni podatak. */
    .transfer-warn {
      display: inline-block;
      margin-left: 6px;
      padding: 1px 8px;
      font-size: 12px;
      color: var(--boja-upozorenje);
      background: var(--boja-upozorenje-tiha);
      border: 1px solid var(--boja-upozorenje-rub);
      border-radius: var(--radijus-pilula);
    }

    .transfer-warn-block {
      padding: 0.5rem 0.75rem;
      color: var(--boja-upozorenje);
      background: var(--boja-upozorenje-tiha);
      border: 1px solid var(--boja-upozorenje-rub);
      border-radius: var(--radijus);
    }

    .transfer-broken {
      margin: 0;
      padding-left: 1.25rem;
      font-size: 13px;
    }
  `
})
export class TemplateTransferDialog {
  private readonly service = inject(TemplateTransferService);

  readonly companies = input.required<Company[]>();
  /** Firma odabrana u traci - najčešće je ona i izvor, pa se nudi unaprijed. */
  readonly initialSourceId = input<number | null>(null);

  readonly closed = output<void>();
  /**
   * Javlja se tek kad je prijenos stvarno izvršen, i nosi izvještaj sa sobom - poruku
   * ispisuje ekran iza, jer dijalog u tom trenutku više ne postoji.
   */
  readonly transferred = output<TransferPlan>();

  protected readonly sourceId = signal<number | null>(null);
  protected readonly targetId = signal<number | null>(null);
  protected readonly templates = signal<Template[]>([]);
  protected readonly chosen = signal<number[]>([]);
  protected readonly plan = signal<TransferPlan | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    queueMicrotask(() => {
      const initial = this.initialSourceId();
      if (initial !== null) {
        this.onSourceChange(initial);
      }
    });
  }

  /** Ista firma s obje strane nema smisla, pa se u drugom izborniku ne nudi. */
  protected readonly targetChoices = computed(() =>
    this.companies().filter((company) => company.id !== this.sourceId())
  );

  protected readonly canPreview = computed(() =>
    this.sourceId() !== null && this.targetId() !== null && this.chosen().length > 0
  );

  /**
   * Svaki označeni obrazac u ciljnoj firmi već postoji pod istim nazivom.
   *
   * Pojedinačno preimenovanje je uredna stvar - netko svjesno radi drugu inačicu. Ali kad
   * se to dogodi SVIMA odjednom, to je gotovo uvijek ponovljeni prijenos, a ne namjera; i
   * upravo se tako i dogodilo prvi put kad je ova radnja puštena u rad.
   */
  protected readonly looksLikeRepeat = computed(() => {
    const templates = this.plan()?.templates ?? [];
    return templates.length > 0 && templates.every((t) => t.renamed);
  });

  protected readonly allChosen = computed(() =>
    this.templates().length > 0 && this.chosen().length === this.templates().length
  );

  protected isChosen(id: number): boolean {
    return this.chosen().includes(id);
  }

  protected toggle(id: number): void {
    this.invalidatePlan();
    this.chosen.set(
      this.isChosen(id) ? this.chosen().filter((x) => x !== id) : [...this.chosen(), id]
    );
  }

  protected toggleAll(): void {
    this.invalidatePlan();
    this.chosen.set(this.allChosen() ? [] : this.templates().map((t) => t.id));
  }

  protected onSourceChange(companyId: number | null): void {
    this.sourceId.set(companyId);
    this.chosen.set([]);
    this.templates.set([]);
    this.invalidatePlan();
    if (companyId === null) {
      return;
    }
    if (this.targetId() === companyId) {
      this.targetId.set(null);
    }
    this.service.templatesOf(companyId).subscribe({
      next: (templates) => this.templates.set(templates),
      error: (error: unknown) => this.error.set(this.messageOf(error, 'Dohvaćanje obrazaca nije uspjelo.'))
    });
  }

  protected onTargetChange(companyId: number | null): void {
    this.targetId.set(companyId);
    this.invalidatePlan();
  }

  protected showPlan(): void {
    this.run((request) => this.service.preview(request), (plan) => this.plan.set(plan));
  }

  /** Uspješan prijenos zatvara dijalog - posao je gotov, a izvještaj je već bio pokazan. */
  protected apply(): void {
    this.run((request) => this.service.transfer(request), (plan) => {
      this.transferred.emit(plan);
      this.closed.emit();
    });
  }

  /**
   * Svaka promjena odabira baca izračunatu najavu.
   *
   * Bez toga bi se moglo potvrditi ono što je izračunato PRIJE promjene - a upravo je najava
   * jedino mjesto na kojem se posljedice vide, pa zastarjela najava ne bi bila samo beskorisna
   * nego bi lagala.
   */
  private invalidatePlan(): void {
    this.plan.set(null);
  }

  private run(
    call: (request: TransferRequest) => import('rxjs').Observable<TransferPlan>,
    onDone: (plan: TransferPlan) => void
  ): void {
    const source = this.sourceId();
    const target = this.targetId();
    if (source === null || target === null || this.chosen().length === 0) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    call({ sourceCompanyId: source, targetCompanyId: target, templateIds: this.chosen() }).subscribe({
      next: (plan) => {
        this.busy.set(false);
        onDone(plan);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(this.messageOf(error, 'Prijenos nije uspio.'));
      }
    });
  }

  private messageOf(error: unknown, fallback: string): string {
    return (error as { error?: { message?: string } })?.error?.message ?? fallback;
  }
}

import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';

import { TenantService } from '../tenant/tenant.service';
import { DashboardService } from '../dashboards/dashboard.service';
import { Survey } from '../survey/survey.model';

/**
 * Domenske konstante ove ploče - jedino mjesto na kojem se spominje struka.
 *
 * Ploča prikazuje zadatke (obrazac "Zadaci") vezane uz odabrani rizik ILI samoprocjenu.
 * Veza se filtrira NA SERVERU po id-u (referentni stupac), pa radi i s tisućama zapisa.
 * Ako obrasca u firmi nema, ploča to javi umjesto da padne.
 */
const TEMPLATE_TASKS = 'Zadaci';
const TEMPLATE_RISK = 'Rizici';
const TEMPLATE_ASSESSMENT = 'Samoprocjena';

const COL_REF_RISK = 'povezani_rizik';
const COL_REF_ASSESSMENT = 'povezana_samoprocjena';
const COL_NAME = 'naziv';
const COL_STATUS = 'status';
const COL_PRIORITY = 'prioritet';
const COL_DEADLINE = 'rok';
const COL_ASSIGNEE = 'nositelj';

/** Šifra statusa koja znači „gotovo" - takav zadatak nije otvoren ni u kašnjenju. */
const DONE_STATUS = 'zavrsen';

type Mode = 'rizik' | 'samoprocjena';

interface ParentOption {
  id: number;
  naziv: string;
}

interface TaskRow {
  naziv: string;
  nositelj: string;
  rok: string;
  prioritet: string;
  status: string;
  done: boolean;
  overdue: boolean;
}

@Component({
  selector: 'app-zadaci-pregled',
  imports: [FormsModule],
  templateUrl: './zadaci-pregled.html',
  styleUrl: './zadaci-pregled.css'
})
export class ZadaciPregled {
  private readonly tenantService = inject(TenantService);
  private readonly dashboard = inject(DashboardService);

  protected readonly activeCompanyId = this.tenantService.activeCompanyId;

  protected readonly mode = signal<Mode>('rizik');
  protected readonly parents = signal<ParentOption[]>([]);
  protected readonly selectedParentId = signal<number | null>(null);

  protected readonly loadingParents = signal(false);
  protected readonly loadingTasks = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Nedostaje neki od obrazaca (Zadaci / Rizici / Samoprocjena). */
  protected readonly missing = signal(false);

  private readonly tasks = signal<Survey[]>([]);
  private readonly labels = signal<Map<string, Map<string, string>>>(new Map());
  private readonly tasksTemplateId = signal<number | null>(null);

  private readonly today = new Date().toISOString().slice(0, 10);

  protected readonly parentLabel = computed(() =>
    this.mode() === 'rizik' ? 'rizik' : 'samoprocjenu'
  );

  constructor() {
    effect(() => {
      const companyId = this.activeCompanyId();
      const mode = this.mode();
      untracked(() => {
        if (companyId === null) {
          this.resetAll();
        } else {
          this.loadParents(mode);
        }
      });
    });
  }

  // --- izvedeni pokazatelji ------------------------------------------------

  protected readonly redovi = computed<TaskRow[]>(() =>
    this.tasks().map((t) => {
      const statusCode = t.data[COL_STATUS];
      const done = statusCode === DONE_STATUS;
      const rok = this.tekst(t.data[COL_DEADLINE]);
      const overdue = !done && rok !== '—' && rok < this.today;
      return {
        naziv: this.tekst(t.data[COL_NAME]),
        nositelj: this.tekst(t.data[COL_ASSIGNEE]),
        rok,
        prioritet: this.label(COL_PRIORITY, t.data[COL_PRIORITY]),
        status: this.label(COL_STATUS, t.data[COL_STATUS]),
        done,
        overdue
      };
    })
  );

  protected readonly ukupno = computed(() => this.redovi().length);
  protected readonly otvoreni = computed(() => this.redovi().filter((r) => !r.done).length);
  protected readonly uKasnjenju = computed(() => this.redovi().filter((r) => r.overdue).length);

  protected readonly selectedParentName = computed(() => {
    const id = this.selectedParentId();
    return this.parents().find((p) => p.id === id)?.naziv ?? null;
  });

  // --- radnje --------------------------------------------------------------

  protected onModeChange(mode: Mode): void {
    if (mode !== this.mode()) {
      this.mode.set(mode);
    }
  }

  protected onParentChange(id: number | null): void {
    this.selectedParentId.set(id);
    if (id === null) {
      this.tasks.set([]);
    } else {
      this.loadTasks(id);
    }
  }

  // --- učitavanje ----------------------------------------------------------

  private loadParents(mode: Mode): void {
    this.loadingParents.set(true);
    this.error.set(null);
    this.missing.set(false);
    this.selectedParentId.set(null);
    this.tasks.set([]);

    const parentName = mode === 'rizik' ? TEMPLATE_RISK : TEMPLATE_ASSESSMENT;

    this.dashboard.templates().subscribe({
      next: (templates) => {
        const tasksTemplate = templates.find((t) => t.name === TEMPLATE_TASKS);
        const parentTemplate = templates.find((t) => t.name === parentName);
        if (!tasksTemplate || !parentTemplate) {
          this.resetData();
          this.missing.set(true);
          this.loadingParents.set(false);
          return;
        }
        this.tasksTemplateId.set(tasksTemplate.id);
        forkJoin({
          codes: this.dashboard.codeLabels(tasksTemplate.id),
          parents: this.dashboard.allSurveys(parentTemplate.id)
        }).subscribe({
          next: ({ codes, parents }) => {
            this.labels.set(codes.labels);
            this.parents.set(
              parents
                .map((p) => ({ id: p.id, naziv: this.tekst(p.data[COL_NAME]) }))
                .sort((a, b) => a.naziv.localeCompare(b.naziv, 'hr'))
            );
            this.loadingParents.set(false);
          },
          error: () => this.fail(true)
        });
      },
      error: () => this.fail(true)
    });
  }

  private loadTasks(parentId: number): void {
    const templateId = this.tasksTemplateId();
    if (templateId === null) {
      return;
    }
    this.loadingTasks.set(true);
    this.error.set(null);

    const column = this.mode() === 'rizik' ? COL_REF_RISK : COL_REF_ASSESSMENT;
    this.dashboard
      .allSurveys(templateId, [{ column, value: String(parentId) }])
      .subscribe({
        next: (tasks) => {
          this.tasks.set(tasks);
          this.loadingTasks.set(false);
        },
        error: () => {
          this.error.set('Dohvaćanje zadataka nije uspjelo.');
          this.tasks.set([]);
          this.loadingTasks.set(false);
        }
      });
  }

  private label(column: string, code: unknown): string {
    if (typeof code !== 'string' || code === '') {
      return '—';
    }
    return this.labels().get(column)?.get(code) ?? code;
  }

  private tekst(value: unknown): string {
    return typeof value === 'string' && value !== '' ? value : '—';
  }

  private resetData(): void {
    this.parents.set([]);
    this.tasks.set([]);
    this.labels.set(new Map());
    this.tasksTemplateId.set(null);
    this.selectedParentId.set(null);
  }

  private resetAll(): void {
    this.resetData();
    this.error.set(null);
    this.missing.set(false);
  }

  private fail(parents: boolean): void {
    this.error.set('Dohvaćanje podataka nije uspjelo.');
    this.resetData();
    if (parents) {
      this.loadingParents.set(false);
    }
  }
}

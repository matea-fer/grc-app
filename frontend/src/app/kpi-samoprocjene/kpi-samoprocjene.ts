import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { forkJoin } from 'rxjs';

import { TenantService } from '../tenant/tenant.service';
import { DashboardService } from '../dashboards/dashboard.service';
import { Survey } from '../survey/survey.model';

/**
 * Domenske konstante ove ploče - JEDINO mjesto na kojem se spominje struka.
 *
 * Ploča je custom prikaz nad obrascem "Samoprocjena": zbraja njegove zapise u KPI-jeve.
 * Nazivi obrasca i stupaca dolaze iz seeda (skill domain-seed). Ako obrasca u odabranoj
 * firmi nema (druga firma, drukčija konfiguracija), ploča to javi umjesto da padne.
 */
const TEMPLATE_NAME = 'Samoprocjena';
const COL_RESULT = 'rezultat_postotak';
const COL_STATUS = 'status';
const COL_OCJENA = 'ocjena';
const COL_OKVIR = 'okvir';

/** Ispod ovog rezultata (%) samoprocjena ulazi u popis „traži pažnju". */
const PAZNJA_PRAG = 70;

interface Udio {
  naziv: string;
  broj: number;
}

interface OkvirRed {
  naziv: string;
  broj: number;
  prosjek: number;
}

interface RedPaznje {
  naziv: string;
  okvir: string;
  status: string;
  ocjena: string;
  rezultat: number | null;
}

@Component({
  selector: 'app-kpi-samoprocjene',
  imports: [],
  templateUrl: './kpi-samoprocjene.html',
  styleUrl: './kpi-samoprocjene.css'
})
export class KpiSamoprocjene {
  private readonly tenantService = inject(TenantService);
  private readonly dashboard = inject(DashboardService);

  protected readonly activeCompanyId = this.tenantService.activeCompanyId;

  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Firma nema obrazac "Samoprocjena" - ploča nema što prikazati. */
  protected readonly missing = signal(false);

  private readonly surveys = signal<Survey[]>([]);
  /** columnKey -> (šifra -> naziv). */
  private readonly labels = signal<Map<string, Map<string, string>>>(new Map());

  constructor() {
    effect(() => {
      const companyId = this.activeCompanyId();
      untracked(() => {
        if (companyId === null) {
          this.reset();
          this.error.set(null);
        } else {
          this.load();
        }
      });
    });
  }

  // --- izvedeni KPI-jevi ---------------------------------------------------

  protected readonly ukupno = computed(() => this.surveys().length);

  private readonly rezultati = computed(() =>
    this.surveys()
      .map((s) => s.data[COL_RESULT])
      .filter((v): v is number => typeof v === 'number')
  );

  protected readonly prosjek = computed(() => {
    const values = this.rezultati();
    if (values.length === 0) {
      return null;
    }
    return Math.round(values.reduce((a, b) => a + b, 0) / values.length);
  });

  protected readonly najnizi = computed(() => {
    const values = this.rezultati();
    return values.length === 0 ? null : Math.min(...values);
  });

  protected readonly brojOkvira = computed(() => {
    const codes = new Set<string>();
    for (const s of this.surveys()) {
      const code = s.data[COL_OKVIR];
      if (typeof code === 'string' && code !== '') {
        codes.add(code);
      }
    }
    return codes.size;
  });

  protected readonly poStatusu = computed(() => this.distribucija(COL_STATUS));
  protected readonly poOcjeni = computed(() => this.distribucija(COL_OCJENA));

  protected readonly poOkviru = computed<OkvirRed[]>(() => {
    const grupe = new Map<string, { zbroj: number; brojRezultata: number; broj: number }>();
    for (const s of this.surveys()) {
      const naziv = this.label(COL_OKVIR, s.data[COL_OKVIR]);
      const g = grupe.get(naziv) ?? { zbroj: 0, brojRezultata: 0, broj: 0 };
      g.broj += 1;
      const r = s.data[COL_RESULT];
      if (typeof r === 'number') {
        g.zbroj += r;
        g.brojRezultata += 1;
      }
      grupe.set(naziv, g);
    }
    return [...grupe.entries()]
      .map(([naziv, g]) => ({
        naziv,
        broj: g.broj,
        prosjek: g.brojRezultata === 0 ? 0 : Math.round(g.zbroj / g.brojRezultata)
      }))
      .sort((a, b) => b.broj - a.broj);
  });

  protected readonly redoviPaznje = computed<RedPaznje[]>(() =>
    this.surveys()
      .map((s) => ({
        naziv: this.tekst(s.data['naziv']),
        okvir: this.label(COL_OKVIR, s.data[COL_OKVIR]),
        status: this.label(COL_STATUS, s.data[COL_STATUS]),
        ocjena: this.label(COL_OCJENA, s.data[COL_OCJENA]),
        rezultat: typeof s.data[COL_RESULT] === 'number' ? (s.data[COL_RESULT] as number) : null
      }))
      .filter((r) => r.rezultat === null || r.rezultat < PAZNJA_PRAG)
      .sort((a, b) => (a.rezultat ?? -1) - (b.rezultat ?? -1))
  );

  /** Najveći broj u raspodjeli - za širinu trake. */
  protected maxBroj(udjeli: Udio[]): number {
    return udjeli.reduce((m, u) => Math.max(m, u.broj), 0);
  }

  protected postotakSirine(broj: number, udjeli: Udio[]): number {
    const max = this.maxBroj(udjeli);
    return max === 0 ? 0 : Math.round((broj / max) * 100);
  }

  // --- učitavanje ----------------------------------------------------------

  private load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.missing.set(false);

    this.dashboard.templates().subscribe({
      next: (templates) => {
        const template = templates.find((t) => t.name === TEMPLATE_NAME);
        if (!template) {
          this.reset();
          this.missing.set(true);
          this.loading.set(false);
          return;
        }
        forkJoin({
          surveys: this.dashboard.allSurveys(template.id),
          codes: this.dashboard.codeLabels(template.id)
        }).subscribe({
          next: ({ surveys, codes }) => {
            this.surveys.set(surveys);
            this.labels.set(codes.labels);
            this.loading.set(false);
          },
          error: () => this.fail()
        });
      },
      error: () => this.fail()
    });
  }

  private distribucija(column: string): Udio[] {
    const brojac = new Map<string, number>();
    for (const s of this.surveys()) {
      const naziv = this.label(column, s.data[column]);
      brojac.set(naziv, (brojac.get(naziv) ?? 0) + 1);
    }
    return [...brojac.entries()]
      .map(([naziv, broj]) => ({ naziv, broj }))
      .sort((a, b) => b.broj - a.broj);
  }

  /** Naziv šifre iz šifrarnika stupca; prazno/nepoznato → „—". */
  private label(column: string, code: unknown): string {
    if (typeof code !== 'string' || code === '') {
      return '—';
    }
    return this.labels().get(column)?.get(code) ?? code;
  }

  private tekst(value: unknown): string {
    return typeof value === 'string' && value !== '' ? value : '—';
  }

  private reset(): void {
    this.surveys.set([]);
    this.labels.set(new Map());
  }

  private fail(): void {
    this.error.set('Dohvaćanje podataka nije uspjelo.');
    this.reset();
    this.loading.set(false);
  }
}

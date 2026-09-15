import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { interval } from 'rxjs';

import { TenantService } from '../tenant/tenant.service';
import { SbomService } from '../sbom/sbom.service';
import { SbomEvaluation, SbomEvaluationDetail, SbomStatus } from '../sbom/sbom.model';

/** Koliko cesto se osvjezava status dok analiza traje. */
const POLL_MS = 2500;

@Component({
  selector: 'app-sbom-eval',
  imports: [],
  templateUrl: './sbom-eval.html',
  styleUrl: './sbom-eval.css'
})
export class SbomEval {
  private readonly tenantService = inject(TenantService);
  private readonly sbomService = inject(SbomService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly activeCompanyId = this.tenantService.activeCompanyId;

  /** Ako je ekran otvoren iz zapisa produkta, ovdje su id i naziv tog produkta. */
  protected readonly productId = signal<number | null>(null);
  protected readonly productName = signal<string | null>(null);

  protected readonly evaluations = signal<SbomEvaluation[]>([]);
  protected readonly loadingList = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly selectedFileName = signal<string | null>(null);
  private selectedFile: File | null = null;
  protected readonly uploading = signal(false);
  protected readonly uploadError = signal<string | null>(null);

  protected readonly detail = signal<SbomEvaluationDetail | null>(null);
  protected readonly detailLoading = signal(false);

  /** Ima li ijedna evaluacija koja se jos vrti - tada se polla. */
  protected readonly hasActive = computed(() =>
    this.evaluations().some((e) => e.status === 'PENDING' || e.status === 'RUNNING')
  );

  constructor() {
    // Query parametri nose kontekst produkta (kad se dođe iz zapisa produkta). Prati se
    // reaktivno jer isti ekran može dobiti drugi produkt bez ponovnog stvaranja komponente.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const raw = params.get('product');
      const id = raw !== null && /^\d+$/.test(raw) ? Number(raw) : null;
      this.productId.set(id);
      this.productName.set(id === null ? null : params.get('productName'));
    });

    // Popis ovisi o firmi I o odabranom produktu - promjena bilo čega ga ponovno učita.
    effect(() => {
      const companyId = this.activeCompanyId();
      this.productId();
      untracked(() => {
        this.detail.set(null);
        if (companyId === null) {
          this.evaluations.set([]);
          this.error.set(null);
        } else {
          this.refreshList(false);
        }
      });
    });

    // Polling: dok neka analiza traje, tiho osvjezavaj popis (i otvoreni detalj).
    interval(POLL_MS)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        if (this.activeCompanyId() === null || !this.hasActive()) {
          return;
        }
        this.refreshList(true);
        const open = this.detail();
        if (open && (open.status === 'PENDING' || open.status === 'RUNNING')) {
          this.reloadDetail(open.id);
        }
      });
  }

  // --- upload --------------------------------------------------------------

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files && input.files.length ? input.files[0] : null;
    this.selectedFile = file;
    this.selectedFileName.set(file ? file.name : null);
    this.uploadError.set(null);
  }

  protected upload(input: HTMLInputElement): void {
    if (!this.selectedFile || this.uploading()) {
      return;
    }
    this.uploading.set(true);
    this.uploadError.set(null);
    const pid = this.productId();
    const product = pid !== null ? { id: pid, name: this.productName() } : undefined;
    this.sbomService.upload(this.selectedFile, product).subscribe({
      next: () => {
        this.uploading.set(false);
        this.selectedFile = null;
        this.selectedFileName.set(null);
        input.value = '';
        this.refreshList(false);
      },
      error: (err: unknown) => {
        this.uploading.set(false);
        this.uploadError.set(SbomEval.describe(err, 'Slanje SBOM-a nije uspjelo.'));
      }
    });
  }

  // --- popis + detalj ------------------------------------------------------

  private refreshList(silent: boolean): void {
    if (!silent) {
      this.loadingList.set(true);
    }
    this.sbomService.list(this.productId()).subscribe({
      next: (list) => {
        this.evaluations.set(list);
        this.loadingList.set(false);
        this.error.set(null);
      },
      error: (err: unknown) => {
        this.loadingList.set(false);
        if (!silent) {
          this.error.set(SbomEval.describe(err, 'Dohvaćanje evaluacija nije uspjelo.'));
        }
      }
    });
  }

  protected view(id: number): void {
    this.detailLoading.set(true);
    this.reloadDetail(id);
  }

  private reloadDetail(id: number): void {
    this.sbomService.detail(id).subscribe({
      next: (d) => {
        this.detail.set(d);
        this.detailLoading.set(false);
      },
      error: () => {
        this.detailLoading.set(false);
      }
    });
  }

  protected closeDetail(): void {
    this.detail.set(null);
  }

  /** Makni filtar produkta - prikaži sve evaluacije firme. */
  protected showAll(): void {
    this.router.navigate(['/sbom'], { queryParams: {} });
  }

  protected remove(evaluation: SbomEvaluation, event: Event): void {
    event.stopPropagation();
    if (!confirm(`Obrisati evaluaciju „${evaluation.fileName}"?`)) {
      return;
    }
    this.sbomService.delete(evaluation.id).subscribe({
      next: () => {
        if (this.detail()?.id === evaluation.id) {
          this.detail.set(null);
        }
        this.refreshList(false);
      },
      error: (err: unknown) => this.error.set(SbomEval.describe(err, 'Brisanje nije uspjelo.'))
    });
  }

  // --- prikaz --------------------------------------------------------------

  protected statusLabel(status: SbomStatus): string {
    switch (status) {
      case 'PENDING':
        return 'Na čekanju';
      case 'RUNNING':
        return 'Analiza u tijeku';
      case 'DONE':
        return 'Gotovo';
      case 'FAILED':
        return 'Neuspjelo';
    }
  }

  protected formatTime(value: string | null): string {
    return value ? new Date(value).toLocaleString('hr-HR') : '—';
  }

  protected severityClass(severity: string): string {
    return 'sev-' + (severity || 'unknown').toLowerCase();
  }

  private static describe(error: unknown, fallback: string): string {
    const message = (error as { error?: { message?: string } })?.error?.message;
    return message ?? fallback;
  }
}

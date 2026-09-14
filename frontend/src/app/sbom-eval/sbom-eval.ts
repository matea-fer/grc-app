import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
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

  protected readonly activeCompanyId = this.tenantService.activeCompanyId;

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
    effect(() => {
      const companyId = this.activeCompanyId();
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
    this.sbomService.upload(this.selectedFile).subscribe({
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
    this.sbomService.list().subscribe({
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

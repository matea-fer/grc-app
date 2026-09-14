import { Component, computed, inject, input, output, signal } from '@angular/core';

import { Template } from '../template/template.model';
import { DeletePlan, TemplateService } from '../template/template.service';

/**
 * Brisanje više obrazaca odjednom.
 *
 * Postoji uz pojedinačno brisanje (ikona ✕ u retku), a ne umjesto njega, i rješava jednu
 * stvar koju pojedinačno ne može: veza između dva obrasca koji OBA nestaju nije prepreka.
 * Par koji pokazuje jedan na drugoga inače se ne da obrisati nijednim redoslijedom — svaki
 * drži onoga drugog — pa se prvo mora ručno obrisati stupac s vezom.
 *
 * Najava je ovdje obavezna, ne uljudnost: brisanje odnosi i sve zapise i priloge obrasca,
 * nepovratno. „3 obrasca" i „3 obrasca i 1.240 zapisa" nisu ista odluka.
 */
@Component({
  selector: 'app-bulk-delete-dialog',
  template: `
    <div class="modal-backdrop" (click)="closed.emit()">
      <div class="survey-form-card modal-dialog bulk-dialog" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h3>Brisanje više obrazaca</h3>
          <button type="button" class="icon-button" title="Zatvori" (click)="closed.emit()">✕</button>
        </div>

        <div class="form-actions">
          <button type="button" class="secondary" (click)="closed.emit()">Odustani</button>
          @if (plan() === null) {
            <button
              type="button"
              class="primary"
              [disabled]="chosen().length === 0 || busy()"
              (click)="showPlan()"
            >{{ busy() ? 'Računam…' : 'Pokaži što će se obrisati' }}</button>
          } @else if (plan(); as p) {
            <!-- Kad postoji prepreka, gumba NEMA - onemogucen gumb koji se ne da kliknuti
                 samo stoji i ne kaze zasto. Prepreke ispod kazu sto napraviti, a cim se
                 odabir promijeni najava se baca i vraca se gumb "Pokazi sto ce se obrisati". -->
            @if (p.blockers.length === 0) {
              <button type="button" class="danger" [disabled]="busy()" (click)="apply()">
                {{ busy() ? 'Brišem…' : 'Obriši nepovratno' }}
              </button>
            }
          }
        </div>

        @if (error(); as message) {
          <p class="error dialog-error">{{ message }}</p>
        }

        <div class="choice-group bulk-list">
          @for (template of templates(); track template.id) {
            <label class="choice">
              <input type="checkbox" [checked]="isChosen(template.id)" (change)="toggle(template.id)" />
              <span>{{ template.name }}</span>
            </label>
          } @empty {
            <span class="field-hint">Ova firma nema nijedan obrazac.</span>
          }
        </div>

        @if (plan(); as p) {
          @if (p.blockers.length > 0) {
            <h4 class="section-heading">Ne može se obrisati</h4>
            <p class="bulk-note bulk-warn-block">
              Na ove obrasce pokazuju stupci obrazaca koji <strong>ostaju</strong>. Označi i te
              obrasce, ili im prvo ukloni stupac s vezom.
            </p>
            <ul class="bulk-blockers">
              @for (blocker of p.blockers; track blocker) {
                <li>{{ blocker }}</li>
              }
            </ul>
          } @else {
            <h4 class="section-heading">Što se briše</h4>
            <p class="bulk-note bulk-warn-block">
              Zapisi i prilozi odlaze zajedno s obrascem. <strong>Ovo se ne može poništiti.</strong>
            </p>
            <table class="bulk-table">
              <thead>
                <tr><th>Obrazac</th><th>Zapisa</th><th>Priloga</th></tr>
              </thead>
              <tbody>
                @for (t of p.templates; track t.id) {
                  <tr>
                    <td>{{ t.name }}</td>
                    <td>{{ t.recordCount }}</td>
                    <td>{{ t.attachmentCount }}</td>
                  </tr>
                }
              </tbody>
              <tfoot>
                <tr>
                  <td><strong>Ukupno {{ p.templates.length }}</strong></td>
                  <td><strong>{{ totalRecords() }}</strong></td>
                  <td><strong>{{ totalAttachments() }}</strong></td>
                </tr>
              </tfoot>
            </table>
          }
        }
      </div>
    </div>
  `,
  styles: `
    .bulk-dialog {
      max-width: 40rem;
      width: 100%;
    }

    .bulk-list {
      max-height: 14rem;
      overflow-y: auto;
      padding: 0.5rem 0;
    }

    .section-heading {
      margin: 1.5rem 0 0.5rem;
      font-size: 14px;
      color: var(--boja-tekst-prigusen);
    }

    .bulk-note {
      margin: 0 0 0.75rem;
      font-size: 13px;
    }

    /* Nepovratna radnja mora izgledati kao nepovratna radnja, i prije nego se klikne. */
    .bulk-warn-block {
      padding: 0.5rem 0.75rem;
      color: var(--boja-greska-tekst);
      background: var(--boja-greska-tiha);
      border: 1px solid var(--boja-greska);
      border-radius: var(--radijus);
    }

    .bulk-table {
      width: 100%;
      border-collapse: collapse;
    }

    .bulk-table th,
    .bulk-table td {
      text-align: left;
      padding: 4px 10px;
      border-bottom: 1px solid var(--boja-rub);
    }

    .bulk-blockers {
      margin: 0;
      padding-left: 1.25rem;
      font-size: 13px;
    }
  `
})
export class BulkDeleteDialog {
  private readonly templateService = inject(TemplateService);

  readonly templates = input.required<Template[]>();

  readonly closed = output<void>();
  /** Javlja se tek kad je brisanje stvarno izvršeno - nosi koliko ih je otišlo. */
  readonly deleted = output<number>();

  protected readonly chosen = signal<number[]>([]);
  protected readonly plan = signal<DeletePlan | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly totalRecords = computed(() =>
    (this.plan()?.templates ?? []).reduce((sum, t) => sum + t.recordCount, 0)
  );

  protected readonly totalAttachments = computed(() =>
    (this.plan()?.templates ?? []).reduce((sum, t) => sum + t.attachmentCount, 0)
  );

  protected isChosen(id: number): boolean {
    return this.chosen().includes(id);
  }

  /** Promjena odabira baca najavu - potvrditi se smije samo ono što je upravo izračunato. */
  protected toggle(id: number): void {
    this.plan.set(null);
    this.chosen.set(
      this.isChosen(id) ? this.chosen().filter((x) => x !== id) : [...this.chosen(), id]
    );
  }

  protected showPlan(): void {
    this.busy.set(true);
    this.error.set(null);
    this.templateService.deletionPlan(this.chosen()).subscribe({
      next: (plan) => {
        this.busy.set(false);
        this.plan.set(plan);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(this.messageOf(error, 'Provjera nije uspjela.'));
      }
    });
  }

  protected apply(): void {
    const count = this.chosen().length;
    this.busy.set(true);
    this.error.set(null);
    this.templateService.deleteMany(this.chosen()).subscribe({
      next: () => {
        this.busy.set(false);
        this.deleted.emit(count);
        this.closed.emit();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(this.messageOf(error, 'Brisanje nije uspjelo.'));
      }
    });
  }

  private messageOf(error: unknown, fallback: string): string {
    return (error as { error?: { message?: string } })?.error?.message ?? fallback;
  }
}

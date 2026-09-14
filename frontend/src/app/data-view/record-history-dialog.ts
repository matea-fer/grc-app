import { Component, inject, input, output, signal } from '@angular/core';

import { RecordChange } from '../survey/survey.model';
import { SurveyService } from '../survey/survey.service';

/**
 * Dijalog „Povijest": revizijski trag jednog zapisa.
 *
 * Odgovara na pitanje koje dnevnik ne može — ne „tko je i kada radio", nego „kako je OVA
 * vrijednost postala ovakva". Zato je i vezan uz jedan redak, a ne uz ekran.
 *
 * Sadržaj se dohvaća pri otvaranju, a ne uz tablicu zapisa: povijest raste brže od samih
 * podataka, pa bi učitana unaprijed značila da se uz svaki redak povuče i sve što mu se
 * ikad dogodilo — a otvori je se jednom u sto pregleda.
 */
@Component({
  selector: 'app-record-history-dialog',
  template: `
    <div class="modal-backdrop" (click)="closed.emit()">
      <div class="survey-form-card modal-dialog history-dialog" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h3>Povijest zapisa</h3>
          <button type="button" class="icon-button" title="Zatvori" (click)="closed.emit()">✕</button>
        </div>

        @if (error(); as message) {
          <p class="error dialog-error">{{ message }}</p>
        } @else if (loading()) {
          <p class="field-hint">Učitavanje…</p>
        } @else if (changes().length === 0) {
          <p class="field-hint">Za ovaj zapis još nema zabilježenih promjena.</p>
        } @else {
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Datum i vrijeme</th>
                  <th>Korisnik</th>
                  <th>Polje</th>
                  <th>Stara vrijednost</th>
                  <th>Nova vrijednost</th>
                </tr>
              </thead>
              <tbody>
                @for (change of changes(); track $index) {
                  <tr>
                    <td>{{ moment(change.changedAt) }}</td>
                    <td>{{ change.username ?? '-' }}</td>
                    <td>{{ change.columnLabel }}</td>
                    <!-- prazno se ispisuje kao crtica: prazna ćelija izgleda kao da podatak
                         nedostaje, a ovdje "nema vrijednosti" JEST podatak -->
                    <td class="old-value">{{ change.oldValue ?? '-' }}</td>
                    <td>{{ change.newValue ?? '-' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <p class="field-hint">Broj zapisa: {{ changes().length }}</p>
        }

        <div class="form-actions">
          <button type="button" class="secondary" (click)="closed.emit()">Zatvori</button>
        </div>
      </div>
    </div>
  `,
  styles: `
    /* Povijest je šira od obrasca za unos - pet stupaca teksta. */
    .history-dialog {
      max-width: 60rem;
      width: 100%;
    }

    .history-dialog .table-scroll {
      max-height: 24rem;
      overflow-y: auto;
    }

    /* Stara vrijednost je ono što VIŠE ne vrijedi, pa je tiša od nove. */
    .old-value {
      color: var(--boja-tekst-prigusen);
    }
  `
})
export class RecordHistoryDialog {
  private readonly surveyService = inject(SurveyService);

  readonly templateId = input.required<number>();
  readonly surveyId = input.required<number>();
  readonly closed = output<void>();

  protected readonly changes = signal<RecordChange[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  constructor() {
    // Dohvat je u konstruktoru, a ne u ngOnInit: komponenta se stvara tek kad se dijalog
    // otvori (@if u predlošku), pa su to isti trenutak.
    queueMicrotask(() => this.load());
  }

  private load(): void {
    this.surveyService.getHistory(this.templateId(), this.surveyId()).subscribe({
      next: (data) => {
        this.changes.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Dohvaćanje povijesti nije uspjelo.');
        this.loading.set(false);
      }
    });
  }

  /** Isti oblik datuma kao u dnevniku - oba su revizijski prikazi. */
  protected moment(changedAt: string): string {
    return new Date(changedAt).toLocaleString('hr-HR');
  }
}

import { Component, computed, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { RecordLink, isOpenableUrl, linkLabel } from './field';

/**
 * Dijalog stupca tipa „Poveznice": popis adresa jednog zapisa, uz dodavanje i uklanjanje.
 *
 * Zašto dijalog, a ne popis u samoj ćeliji (kao kod priloga): poveznica je duga i nosi naziv,
 * pa bi tri poveznice raznijele visinu retka. U ćeliji zato stoji samo gumb s brojem, a sve
 * ostalo se otvara na klik - isto kao kod povijesti i povezanih zapisa.
 *
 * Dijalog NE sprema ništa sam. Vrijednost prima izvana i mijenja je kroz `changed`, pa isti
 * dijalog služi i retku u tablici (gdje promjenu sprema `DataView`) i zapisu koji se tek
 * upisuje (gdje promjena čeka u obrascu, kao i svako drugo polje). Prilog to ne može, jer se
 * veže uz `surveyId` kojeg prije spremanja nema - poveznica je obična vrijednost i taj problem
 * nema.
 */
@Component({
  selector: 'app-record-links-dialog',
  imports: [FormsModule],
  template: `
    <div class="modal-backdrop" (click)="closed.emit()">
      <div class="survey-form-card modal-dialog links-dialog" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h3>{{ title() }}</h3>
          <button type="button" class="icon-button" title="Zatvori" (click)="closed.emit()">✕</button>
        </div>

        @if (links().length === 0) {
          <p class="field-hint">Ovaj zapis još nema nijednu poveznicu.</p>
        } @else {
          <ul class="link-list">
            @for (link of links(); track $index) {
              <li class="link-row">
                <!-- Prava poveznica, a ne gumb: srednji klik i „otvori u novoj kartici" tako
                     rade sami od sebe. Atribut "noopener" je obavezan - bez njega otvorena
                     stranica kroz window.opener može preusmjeriti onu iz koje je otvorena. -->
                <a
                  class="link-name"
                  [href]="link.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  [title]="link.url"
                >{{ label(link) }} ↗</a>
                @if (!readOnly()) {
                  <button type="button" class="link-remove" title="Ukloni" (click)="remove($index)">✕</button>
                }
              </li>
            }
          </ul>
        }

        @if (readOnly()) {
          <!-- zaključan zapis: poveznice se čitaju, ali se ne mijenjaju - isto pravilo kao
               kod priloga, jer je i ovo dokaz uz zapis -->
          <p class="field-hint">Zapis je zaključan, pa se poveznice ne mogu mijenjati.</p>
        } @else {
          <div class="link-form">
            <label class="form-field">
              <span class="form-field-label">Adresa</span>
              <input
                type="text"
                placeholder="https://…"
                [ngModel]="newUrl()"
                (ngModelChange)="newUrl.set($event)"
                (keydown.enter)="add()"
              />
            </label>
            <label class="form-field">
              <span class="form-field-label">Naziv (neobavezno)</span>
              <input
                type="text"
                placeholder="npr. Politika sigurnosti"
                [ngModel]="newName()"
                (ngModelChange)="newName.set($event)"
                (keydown.enter)="add()"
              />
            </label>
            <button type="button" class="primary link-add" [disabled]="!canAdd()" (click)="add()">
              + Dodaj poveznicu
            </button>
          </div>

          @if (error(); as message) {
            <p class="error dialog-error">{{ message }}</p>
          }
        }

        <div class="form-actions">
          <button type="button" class="secondary" (click)="closed.emit()">Zatvori</button>
        </div>
      </div>
    </div>
  `,
  styles: `
    .links-dialog {
      max-width: 40rem;
      width: 100%;
    }

    .link-list {
      list-style: none;
      margin: 0 0 1rem;
      padding: 0;
    }

    .link-row {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.35rem 0;
      border-bottom: 1px solid var(--boja-rub);
    }

    .link-name {
      flex: 1;
      color: var(--boja-akcent);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .link-remove {
      border: none;
      background: none;
      color: var(--boja-greska-tekst);
      cursor: pointer;
      font-size: 0.9rem;
    }

    .link-form {
      display: grid;
      gap: 0.5rem;
    }

    .link-add {
      justify-self: start;
    }
  `
})
export class RecordLinksDialog {
  readonly title = input('Poveznice');
  readonly links = input.required<RecordLink[]>();
  readonly readOnly = input(false);

  readonly changed = output<RecordLink[]>();
  readonly closed = output<void>();

  protected readonly newUrl = signal('');
  protected readonly newName = signal('');
  protected readonly error = signal<string | null>(null);

  protected readonly canAdd = computed(() => this.newUrl().trim() !== '');

  protected label(link: RecordLink): string {
    return linkLabel(link);
  }

  /**
   * Provjera adrese je ovdje samo zato da odgovor stigne odmah; backend ostaje mjerodavan i
   * odbija isto (`SchemaValidator.checkLinks`).
   */
  protected add(): void {
    const url = this.newUrl().trim();
    if (url === '') {
      return;
    }
    if (!isOpenableUrl(url)) {
      this.error.set('Adresa mora počinjati s "http://" ili "https://".');
      return;
    }
    if (this.links().some((link) => link.url === url)) {
      this.error.set('Ta poveznica je već na popisu.');
      return;
    }

    const name = this.newName().trim();
    this.error.set(null);
    this.newUrl.set('');
    this.newName.set('');
    this.changed.emit([...this.links(), name === '' ? { url } : { url, name }]);
  }

  protected remove(index: number): void {
    this.error.set(null);
    this.changed.emit(this.links().filter((_, i) => i !== index));
  }
}

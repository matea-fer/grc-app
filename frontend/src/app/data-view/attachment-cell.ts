import { Component, computed, inject, input, output, signal } from '@angular/core';

import { Attachment, formatFileSize } from '../attachment/attachment.model';
import { AttachmentService } from '../attachment/attachment.service';
import { SchemaField } from './field';

/**
 * Ćelija stupca tipa „Datoteka": popis priloga i gumb za prilaganje novog.
 *
 * Zašto zaseban razred, a ne još jedna grana u `app-field-input`: prilog NIJE vrijednost
 * zapisa. Ne stoji u `data`, ne šalje se s ostalim poljima i ne provjerava ga shema - živi
 * u vlastitoj tablici i mijenja se vlastitim pozivima. Ugurati ga u komponentu koja radi s
 * vrijednostima značilo bi da ona ima jednu granu koja se ponaša po posve drugim pravilima.
 *
 * Prilagati se može SAMO postojećem zapisu: datoteka se veže uz `surveyId`, a njega prije
 * spremanja nema. Zato `surveyId` smije biti null - tada se umjesto gumba pokazuje uputa.
 */
@Component({
  selector: 'app-attachment-cell',
  template: `
    <div class="attachment-cell">
      @for (attachment of attachments(); track attachment.id) {
        <span class="attachment" [title]="describe(attachment)">
          <button type="button" class="attachment-name" (click)="download(attachment)">
            {{ attachment.fileName }}
          </button>
          <!-- sa zaključanog zapisa se dokaz ne miče; preuzimanje ostaje, ono ništa ne mijenja -->
          @if (!locked()) {
            <button type="button" class="attachment-remove" title="Ukloni" (click)="remove(attachment)">✕</button>
          }
        </span>
      }

      @if (surveyId() === null) {
        <span class="field-hint">Spremite zapis pa priložite dokument.</span>
      } @else if (locked()) {
        <!-- gumb se ne onemogućuje nego miče: onemogućen gumb bi tvrdio da se čeka nešto
             što se dogodi samo od sebe, a ovdje treba administrator -->
        <span class="field-hint">Zapis je zaključan.</span>
      } @else {
        <!-- Polje za odabir datoteke je skriveno i pokreće ga gumb: nativna kontrola nosi
             natpis koji se ne da promijeniti ("Choose file"), a ovdje natpis dolazi iz sheme. -->
        <button type="button" class="cell-button" [disabled]="busy()" (click)="picker.click()">
          {{ busy() ? 'Učitavanje…' : buttonLabel() }}
        </button>
        <input
          #picker
          type="file"
          class="file-picker"
          (change)="onFilePicked($any($event.target))"
        />
      }

      @if (error(); as message) {
        <span class="attachment-error">{{ message }}</span>
      }
    </div>
  `,
  styles: `
    .attachment-cell {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.35rem;
    }

    /* Prilog je značka s dvije radnje: ime preuzima, križić uklanja. */
    .attachment {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.1rem 0.2rem 0.1rem 0.55rem;
      background: var(--boja-znacka);
      border-radius: var(--radijus-pilula);
    }

    .attachment-name {
      max-width: 14rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      padding: 0;
      font: inherit;
      font-size: 0.85rem;
      color: var(--boja-akcent);
      background: none;
      border: none;
      cursor: pointer;
    }

    .attachment-name:hover {
      text-decoration: underline;
    }

    .attachment-remove {
      padding: 0 0.3rem;
      font-size: 0.75rem;
      color: var(--boja-greska-tekst);
      background: none;
      border: none;
      cursor: pointer;
    }

    /* skriveno, ali i dalje u dokumentu - gumb ga otvara programski */
    .file-picker {
      display: none;
    }

    .attachment-error {
      font-size: 0.85rem;
      color: var(--boja-greska-tekst);
    }
  `
})
export class AttachmentCell {
  private readonly attachmentService = inject(AttachmentService);

  readonly templateId = input.required<number>();
  /** null dok zapis nije spremljen - prilog se tada nema uz što vezati. */
  readonly surveyId = input.required<number | null>();
  readonly field = input.required<SchemaField>();
  readonly attachments = input<Attachment[]>([]);
  /**
   * Zaključan zapis ne prima nove priloge i ne da postojeće maknuti - dokazi su ono zbog
   * čega se zapis i zaključava. Backend to odbija sam (409); ovdje se samo ne nudi.
   */
  readonly locked = input(false);

  /** Popis priloga drži ekran Podaci, pa promjenu javljamo njemu. */
  readonly changed = output<void>();

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly buttonLabel = computed(() => this.field().options.buttonLabel || 'Učitaj dokument');

  protected describe(attachment: Attachment): string {
    return `${attachment.fileName} · ${formatFileSize(attachment.sizeBytes)} · ${attachment.uploadedBy}`;
  }

  protected onFilePicked(input: HTMLInputElement): void {
    const file = input.files?.[0];
    // polje se prazni odmah: bez toga se ista datoteka ne bi mogla ponovno odabrati
    // (preglednik ne javlja promjenu kad je vrijednost ista)
    input.value = '';
    if (!file) {
      return;
    }
    const surveyId = this.surveyId();
    if (surveyId === null) {
      return;
    }

    this.busy.set(true);
    this.error.set(null);
    this.attachmentService.upload(this.templateId(), surveyId, this.field().key, file).subscribe({
      next: () => {
        this.busy.set(false);
        this.changed.emit();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(this.messageOf(error, 'Prilaganje nije uspjelo.'));
      }
    });
  }

  /**
   * Preuzimanje ide kroz servis, pa se datoteka dobiva kao blob u memoriji - poveznica ne
   * bi nosila token i backend bi je odbio. Zato se privremena adresa napravi ovdje, klikne
   * programski i odmah oslobodi: bez toga blob ostaje u memoriji do osvježenja stranice.
   */
  protected download(attachment: Attachment): void {
    this.error.set(null);
    this.attachmentService.download(this.templateId(), attachment.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = attachment.fileName;
        link.click();
        URL.revokeObjectURL(url);
      },
      error: (error: unknown) => this.error.set(this.messageOf(error, 'Preuzimanje nije uspjelo.'))
    });
  }

  protected remove(attachment: Attachment): void {
    if (!confirm(`Ukloniti datoteku "${attachment.fileName}"?`)) {
      return;
    }
    this.error.set(null);
    this.attachmentService.delete(this.templateId(), attachment.id).subscribe({
      next: () => this.changed.emit(),
      error: (error: unknown) => this.error.set(this.messageOf(error, 'Brisanje nije uspjelo.'))
    });
  }

  private messageOf(error: unknown, fallback: string): string {
    return (error as { error?: { message?: string } })?.error?.message ?? fallback;
  }
}

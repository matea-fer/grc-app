import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { AuthService } from '../auth/auth.service';
import { Codebook, CodebookItemInput } from '../codebook/codebook.model';
import { CodebookService } from '../codebook/codebook.service';

/**
 * Redak u uređivanju. `key` postoji samo da @for ima stabilan trag - novi retci još
 * nemaju id, a `track $index` bi pri brisanju premjestio unos u krivo polje.
 */
interface EditableItem {
  key: number;
  /** id s backenda; null za redak koji još nije spremljen */
  id: number | null;
  code: string;
  name: string;
  active: boolean;
}

/**
 * Uređivanje stavki jednog šifrarnika.
 *
 * Spremanje je PUNA ZAMJENA popisa: šalje se cijeli niz, a stavka koje u njemu nema
 * se briše. Zato se radi na lokalnoj kopiji i šalje tek na "Spremi" - da se pola
 * izmjene ne nađe na serveru.
 *
 * Redoslijed se mijenja povlačenjem retka i određuje redoslijed u padajućem izborniku.
 * Sam `sortOrder` se ne šalje - server ga dodjeljuje po položaju u nizu, pa klijent
 * šalje redoslijed, a ne brojeve.
 *
 * Neaktivna stavka se NE briše nego skriva - ostaje čitljiva u starim zapisima koji
 * je već nose. Zato je "Aktivan" potvrdni okvir, a ne razlog za brisanje retka.
 */
@Component({
  selector: 'app-codebook-editor',
  imports: [FormsModule],
  templateUrl: './codebook-editor.html',
  styleUrl: './codebook-editor.css'
})
export class CodebookEditor {
  private readonly codebookService = inject(CodebookService);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  private readonly codebookId = Number(this.route.snapshot.paramMap.get('id'));

  protected readonly codebook = signal<Codebook | null>(null);
  protected readonly items = signal<EditableItem[]>([]);
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly success = signal<string | null>(null);
  private successTimer: ReturnType<typeof setTimeout> | null = null;

  /** Ima li nespremljenih izmjena - spremanje je izričito, pa se to mora vidjeti. */
  protected readonly dirty = signal(false);

  private nextKey = 1;

  /** Globalni šifrarnik smije mijenjati samo globalni administrator. */
  protected readonly canEdit = computed(() => {
    const codebook = this.codebook();
    if (codebook === null) {
      return false;
    }
    return codebook.scope === 'GLOBAL'
      ? this.authService.isAdmin()
      : this.authService.isAdmin() || this.authService.isTenantAdmin();
  });

  constructor() {
    if (Number.isInteger(this.codebookId)) {
      this.load();
    } else {
      this.error.set('Neispravan šifrarnik.');
    }
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);

    this.codebookService.getOne(this.codebookId).subscribe({
      next: (data) => this.codebook.set(data),
      error: () => this.error.set('Šifrarnik nije pronađen.')
    });

    this.codebookService.getItems(this.codebookId).subscribe({
      next: (data) => {
        this.items.set(
          data.map((item) => ({
            key: this.nextKey++,
            id: item.id,
            code: item.code,
            name: item.name,
            active: item.active
          }))
        );
        this.dirty.set(false);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Dohvaćanje stavki nije uspjelo.');
        this.loading.set(false);
      }
    });
  }

  protected back(): void {
    this.router.navigate(['/sifrarnici']);
  }

  private notify(message: string): void {
    this.error.set(null);
    this.success.set(message);
    if (this.successTimer !== null) {
      clearTimeout(this.successTimer);
    }
    this.successTimer = setTimeout(() => this.success.set(null), 3000);
  }

  private messageOf(error: unknown, fallback: string): string {
    return (error as { error?: { message?: string } })?.error?.message ?? fallback;
  }

  // ===================== UREĐIVANJE RETKA =====================

  private patch(key: number, change: Partial<EditableItem>): void {
    this.items.set(this.items().map((item) => (item.key === key ? { ...item, ...change } : item)));
    this.dirty.set(true);
  }

  protected updateCode(key: number, code: string): void {
    this.patch(key, { code });
  }

  protected updateName(key: number, name: string): void {
    this.patch(key, { name });
  }

  protected updateActive(key: number, active: boolean): void {
    this.patch(key, { active });
  }

  protected addRow(): void {
    this.items.set([...this.items(), { key: this.nextKey++, id: null, code: '', name: '', active: true }]);
    this.dirty.set(true);
  }

  // ===================== REDOSLIJED =====================

  /** Redak koji se upravo povlači; null kad se ne povlači ništa. */
  protected readonly draggedKey = signal<number | null>(null);

  protected onDragStart(item: EditableItem): void {
    this.draggedKey.set(item.key);
  }

  protected onDragEnd(): void {
    this.draggedKey.set(null);
  }

  /**
   * Bez ovoga preglednik ne dopušta ispuštanje - zadano ponašanje dragovera je
   * "ovdje se ne smije ispustiti", pa ga se mora izričito poništiti.
   */
  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
  }

  /** Ispuštanje nad retkom: povučeni redak se premješta na to mjesto. */
  protected onDrop(event: DragEvent, target: EditableItem): void {
    event.preventDefault();
    const dragged = this.draggedKey();
    this.draggedKey.set(null);
    if (dragged === null || dragged === target.key) {
      return;
    }
    this.moveTo(dragged, this.items().findIndex((item) => item.key === target.key));
  }

  /**
   * Pomicanje strelicama - povlačenje se ne može odraditi tipkovnicom, pa bi bez ovoga
   * redoslijed bio nedostupan onome tko miš ne koristi.
   */
  protected moveBy(item: EditableItem, offset: number): void {
    const from = this.items().findIndex((row) => row.key === item.key);
    this.moveTo(item.key, from + offset);
  }

  /** Je li redak prvi/zadnji - strelica koja nema kamo se ugasi. */
  protected indexOf(item: EditableItem): number {
    return this.items().findIndex((row) => row.key === item.key);
  }

  private moveTo(key: number, to: number): void {
    const items = [...this.items()];
    const from = items.findIndex((item) => item.key === key);
    if (from === -1 || to < 0 || to >= items.length || from === to) {
      return;
    }
    const [moved] = items.splice(from, 1);
    items.splice(to, 0, moved);
    this.items.set(items);
    this.dirty.set(true);
  }

  /**
   * Miče redak iz popisa. Stavka nestaje tek pri spremanju, jer je spremanje puna
   * zamjena - do tada je izmjena samo lokalna i može se odbaciti osvježavanjem.
   */
  protected removeRow(item: EditableItem): void {
    if (item.id !== null && !confirm(`Ukloniti stavku "${item.code}"? Spremanjem nestaje iz šifrarnika.`)) {
      return;
    }
    this.items.set(this.items().filter((row) => row.key !== item.key));
    this.dirty.set(true);
  }

  protected discard(): void {
    if (this.dirty() && !confirm('Odbaciti nespremljene izmjene?')) {
      return;
    }
    this.load();
  }

  // ===================== SPREMANJE =====================

  /**
   * Ista pravila koja brani i backend - ovdje samo zato da korisnik odgovor dobije
   * odmah. Backend ostaje mjerodavan.
   *
   * @return poruka o problemu, ili null ako je popis u redu
   */
  private inputProblem(): string | null {
    const seen = new Set<string>();
    for (const item of this.items()) {
      const code = item.code.trim();
      if (!code) {
        return 'Šifra je obavezna u svakom retku.';
      }
      if (!item.name.trim()) {
        return `Naziv je obavezan (šifra "${code}").`;
      }
      // "OPEN" i "open" su korisniku ista šifra, pa se uspoređuju bez obzira na velika slova
      const key = code.toLowerCase();
      if (seen.has(key)) {
        return `Šifra "${code}" se pojavljuje više puta.`;
      }
      seen.add(key);
    }
    return null;
  }

  protected save(): void {
    const problem = this.inputProblem();
    if (problem !== null) {
      this.success.set(null);
      this.error.set(problem);
      return;
    }

    const payload: CodebookItemInput[] = this.items().map((item) => ({
      id: item.id,
      code: item.code.trim(),
      name: item.name.trim(),
      active: item.active
    }));

    this.error.set(null);
    this.saving.set(true);
    this.codebookService.saveItems(this.codebookId, payload).subscribe({
      next: (saved) => {
        // odgovor nosi id-eve novih stavki i konačan redoslijed - preuzima se u cijelosti
        this.items.set(
          saved.map((item) => ({
            key: this.nextKey++,
            id: item.id,
            code: item.code,
            name: item.name,
            active: item.active
          }))
        );
        this.dirty.set(false);
        this.saving.set(false);
        this.notify(`Spremljeno ${saved.length} stavki.`);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.success.set(null);
        this.error.set(this.messageOf(error, 'Spremanje stavki nije uspjelo.'));
      }
    });
  }
}

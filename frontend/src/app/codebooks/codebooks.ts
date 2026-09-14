import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { AuthService } from '../auth/auth.service';
import { Codebook, CodebookScope } from '../codebook/codebook.model';
import { CodebookService } from '../codebook/codebook.service';
import { TenantService } from '../tenant/tenant.service';

const SCOPE_LABELS: Record<CodebookScope, string> = {
  GLOBAL: 'Globalni',
  TENANT: 'Firmin'
};

/**
 * Popis šifrarnika - imenovanih popisa dopuštenih vrijednosti koje dijeli više obrazaca.
 *
 * Filtar po dosegu i pretraga po nazivu idu NA SERVER (query parametri), za razliku od
 * ostalih ekrana koji filtriraju u pregledniku. Pretraga se odgađa da svaki pritisnut
 * znak ne bude jedan poziv, a odgovori se broje da stariji ne pregazi noviji.
 *
 * Čitati smiju svi, uređivati samo administratori - i to globalne šifrarnike samo
 * globalni administrator. Zato se gumbi crtaju po dosegu pojedinog retka, a ne jednom
 * za cijeli ekran. Backend to neovisno brani s 403.
 */
@Component({
  selector: 'app-codebooks',
  imports: [FormsModule],
  templateUrl: './codebooks.html',
  styleUrl: './codebooks.css',
  host: {
    '(document:keydown.escape)': 'closeAddDialog()'
  }
})
export class Codebooks {
  private readonly codebookService = inject(CodebookService);
  private readonly tenantService = inject(TenantService);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly activeCompanyId = this.tenantService.activeCompanyId;
  protected readonly isAdmin = this.authService.isAdmin;
  protected readonly isTenantAdmin = this.authService.isTenantAdmin;

  protected readonly codebooks = signal<Codebook[]>([]);
  protected readonly loading = signal(false);
  /** Greška koja pripada ekranu: neuspjelo učitavanje, brisanje, preimenovanje u retku. */
  protected readonly error = signal<string | null>(null);
  /**
   * Greška o onome što se upisuje u dijalogu. Ide zasebno jer bi inače završila na
   * stranici IZA zatamnjene pozadine - ondje gdje je korisnik ne vidi.
   */
  protected readonly dialogError = signal<string | null>(null);
  protected readonly success = signal<string | null>(null);
  private successTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly scopeFilter = signal<'' | CodebookScope>('');
  protected readonly searchTerm = signal('');
  private searchTimer: ReturnType<typeof setTimeout> | null = null;
  // redni broj zahtjeva; odgovor starijeg se odbacuje (isti razlog kao isStale drugdje)
  private requestSeq = 0;

  protected readonly showAddDialog = signal(false);
  protected readonly newName = signal('');
  protected readonly newScope = signal<CodebookScope>('TENANT');

  protected readonly editingId = signal<number | null>(null);
  protected readonly editName = signal('');

  /** Smije li uopće dodavati šifrarnike (koji doseg - odlučuje newScope). */
  protected readonly canCreate = computed(() => this.isAdmin() || this.isTenantAdmin());

  /** Globalni doseg nudi se samo globalnom administratoru; ostalima je jedini izbor TENANT. */
  protected readonly scopeOptions = computed<CodebookScope[]>(() =>
    this.isAdmin() ? ['TENANT', 'GLOBAL'] : ['TENANT']
  );

  constructor() {
    // promjena aktivne firme -> drugi popis (firmini šifrarnici su drugi, globalni isti)
    effect(() => {
      this.activeCompanyId();
      untracked(() => {
        this.cancelEdit();
        this.load();
      });
    });
  }

  protected scopeLabel(scope: CodebookScope): string {
    return SCOPE_LABELS[scope] ?? scope;
  }

  /** Smije li se ovaj redak uređivati - globalni samo globalnom administratoru. */
  protected canEdit(codebook: Codebook): boolean {
    return codebook.scope === 'GLOBAL' ? this.isAdmin() : this.isAdmin() || this.isTenantAdmin();
  }

  private load(): void {
    const seq = ++this.requestSeq;
    this.loading.set(true);
    this.error.set(null);
    const scope = this.scopeFilter() === '' ? null : (this.scopeFilter() as CodebookScope);
    this.codebookService.list(scope, this.searchTerm()).subscribe({
      next: (data) => {
        if (seq !== this.requestSeq) {
          return;
        }
        this.codebooks.set(data);
        this.loading.set(false);
      },
      error: () => {
        if (seq !== this.requestSeq) {
          return;
        }
        this.error.set('Dohvaćanje šifrarnika nije uspjelo.');
        this.loading.set(false);
      }
    });
  }

  protected onScopeFilterChange(scope: '' | CodebookScope): void {
    this.scopeFilter.set(scope);
    this.cancelEdit();
    this.load();
  }

  /**
   * Pretraga ide na server, pa se poziv odgađa - inače bi svaki pritisnut znak bio
   * jedan zahtjev. Prethodni odgodni poziv se poništava, da vrijedi samo zadnje upisano.
   */
  protected onSearchChange(event: Event): void {
    this.searchTerm.set((event.target as HTMLInputElement).value);
    this.cancelEdit();
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
    this.searchTimer = setTimeout(() => this.load(), 300);
  }

  protected openItems(codebook: Codebook): void {
    this.router.navigate(['/sifrarnici', codebook.id]);
  }

  private failed(fallback: string): (error: unknown) => void {
    return (error: unknown) => {
      this.success.set(null);
      this.error.set(this.messageOf(error, fallback));
    };
  }

  /** Isto, ali poruka ostaje u dijalogu iz kojeg je akcija krenula. */
  private failedInDialog(fallback: string): (error: unknown) => void {
    return (error: unknown) => {
      this.success.set(null);
      this.dialogError.set(this.messageOf(error, fallback));
    };
  }

  private messageOf(error: unknown, fallback: string): string {
    return (error as { error?: { message?: string } })?.error?.message ?? fallback;
  }

  private notify(message: string): void {
    this.error.set(null);
    this.success.set(message);
    if (this.successTimer !== null) {
      clearTimeout(this.successTimer);
    }
    this.successTimer = setTimeout(() => this.success.set(null), 3000);
  }

  // ===================== NOVI ŠIFRARNIK =====================

  protected openAddDialog(): void {
    this.newName.set('');
    // administratoru firme je jedini mogući doseg njegova firma
    this.newScope.set('TENANT');
    this.dialogError.set(null);
    this.showAddDialog.set(true);
  }

  protected closeAddDialog(): void {
    this.showAddDialog.set(false);
    this.dialogError.set(null);
  }

  protected add(): void {
    const name = this.newName().trim();
    if (!name) {
      this.dialogError.set('Naziv šifrarnika je obavezan.');
      return;
    }
    this.dialogError.set(null);
    this.codebookService.create({ name, scope: this.newScope() }).subscribe({
      next: () => {
        this.closeAddDialog();
        this.notify(`Šifrarnik "${name}" dodan.`);
        this.load();
      },
      error: this.failedInDialog('Dodavanje šifrarnika nije uspjelo.')
    });
  }

  // ===================== PREIMENOVANJE U RETKU =====================

  protected startEdit(codebook: Codebook): void {
    this.editingId.set(codebook.id);
    this.editName.set(codebook.name);
  }

  protected cancelEdit(): void {
    this.editingId.set(null);
  }

  protected saveEdit(id: number): void {
    const name = this.editName().trim();
    if (!name) {
      this.success.set(null);
      this.error.set('Naziv šifrarnika je obavezan.');
      return;
    }
    this.error.set(null);
    this.codebookService.rename(id, { name }).subscribe({
      next: () => {
        this.editingId.set(null);
        this.notify(`Šifrarnik preimenovan u "${name}".`);
        this.load();
      },
      error: this.failed('Preimenovanje šifrarnika nije uspjelo.')
    });
  }

  protected remove(codebook: Codebook): void {
    if (
      !confirm(
        `Obrisati šifrarnik "${codebook.name}"? Brišu se i sve njegove stavke (${codebook.itemCount}).`
      )
    ) {
      return;
    }
    this.error.set(null);
    this.codebookService.delete(codebook.id).subscribe({
      next: () => {
        this.notify(`Šifrarnik "${codebook.name}" obrisan.`);
        this.load();
      },
      error: this.failed('Brisanje šifrarnika nije uspjelo.')
    });
  }
}

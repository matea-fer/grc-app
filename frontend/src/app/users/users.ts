import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AuthService } from '../auth/auth.service';
import { Role } from '../auth/auth.model';
import { CompanyService } from '../company/company.service';
import { User } from '../user/user.model';
import { UserService } from '../user/user.service';

/** Nazivi uloga na jednom mjestu - koriste ih i padajući izbornik i tablica. */
const ROLE_LABELS: Record<Role, string> = {
  ADMIN: 'Globalni administrator',
  TENANT_ADMIN: 'Administrator firme',
  USER: 'Korisnik'
};

/**
 * Korisnici - popis i dodavanje. Ekran vide dvije uloge, s različitim dosegom:
 *
 *  - globalni administrator vidi sve račune i dodaje ih bilo kojoj firmi, pa se
 *    firma bira u formi;
 *  - administrator firme vidi i dodaje isključivo račune SVOJE firme, pa birača
 *    firme uopće nema - jedina moguća je njegova, i backend je ionako uzima iz
 *    tokena, a ne iz onoga što ovaj ekran pošalje.
 *
 * Backend vraća samo ono što pozivatelj smije vidjeti, pa se popis ne filtrira
 * ovdje - ekran crta ono što je dobio.
 */
@Component({
  selector: 'app-users',
  imports: [FormsModule],
  templateUrl: './users.html',
  styleUrl: './users.css',
  host: {
    '(document:keydown.escape)': 'closeAddDialog()'
  }
})
export class Users {
  private readonly userService = inject(UserService);
  private readonly companyService = inject(CompanyService);
  private readonly authService = inject(AuthService);

  protected readonly users = signal<User[]>([]);
  protected readonly companies = this.companyService.companies;
  protected readonly isTenantAdmin = this.authService.isTenantAdmin;
  /** Greška koja pripada ekranu: neuspjelo učitavanje ili brisanje. */
  protected readonly error = signal<string | null>(null);
  /**
   * Greška o onome što se upisuje u dijalogu. Ide zasebno jer bi inače završila na
   * stranici IZA zatamnjene pozadine - ondje gdje je korisnik ne vidi.
   */
  protected readonly dialogError = signal<string | null>(null);
  protected readonly success = signal<string | null>(null);

  private successTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly showAddDialog = signal(false);
  protected readonly newUsername = signal('');
  protected readonly newPassword = signal('');
  protected readonly newRole = signal<Role>('USER');
  protected readonly newCompanyId = signal<number | null>(null);

  // vlastiti račun se ne smije obrisati (backend to i odbija) - gumb se zato skriva
  protected readonly currentUsername = computed(() => this.authService.currentUser()?.username ?? null);

  /** Naziv firme prijavljenog administratora firme - ispisuje se umjesto birača. */
  protected readonly ownCompanyName = computed(() => this.authService.currentUser()?.companyName ?? null);

  /**
   * Uloge koje pozivatelj smije dodijeliti. Administrator firme ne može stvoriti
   * globalnog administratora - to bi bilo penjanje iznad vlastite uloge, i backend
   * to odbija s 403. Ovdje se ta opcija zato uopće ne nudi.
   */
  protected readonly roleOptions = computed<Role[]>(() =>
    this.isTenantAdmin() ? ['USER', 'TENANT_ADMIN'] : ['USER', 'TENANT_ADMIN', 'ADMIN']
  );

  constructor() {
    this.reload();
    // administratoru firme popis firmi ne treba - nema birača; ali ga ionako
    // dobiva suženog na svoju, pa poziv ne škodi i drži traku popunjenom
    this.companyService.refresh().subscribe({
      error: () => this.error.set('Dohvaćanje firmi nije uspjelo.')
    });
  }

  private reload(): void {
    this.userService.getUsers().subscribe({
      next: (data) => this.users.set(data),
      error: () => this.error.set('Dohvaćanje korisnika nije uspjelo.')
    });
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

  protected roleLabel(role: Role): string {
    return ROLE_LABELS[role] ?? role;
  }

  // Globalni ADMIN ne pripada firmi - kad se odabere ta uloga, odabir firme se poništava.
  // TENANT_ADMIN firmu ima, jednako kao i običan korisnik, pa se odabir ne dira.
  protected onRoleChange(role: Role): void {
    this.newRole.set(role);
    if (role === 'ADMIN') {
      this.newCompanyId.set(null);
    }
  }

  /**
   * Firma novog korisnika. Administrator firme je ne bira - jedina moguća je njegova.
   * Backend je za njega ionako uzima iz tokena; ovo je samo da forma pošalje ono što
   * korisnik na ekranu i vidi.
   */
  private targetCompanyId(): number | null {
    return this.isTenantAdmin() ? this.authService.currentUser()?.companyId ?? null : this.newCompanyId();
  }

  protected openAddDialog(): void {
    this.newUsername.set('');
    this.newPassword.set('');
    this.newRole.set('USER');
    this.newCompanyId.set(null);
    this.dialogError.set(null);
    this.showAddDialog.set(true);
  }

  protected closeAddDialog(): void {
    this.showAddDialog.set(false);
    this.dialogError.set(null);
  }

  protected add(): void {
    const username = this.newUsername().trim();
    const password = this.newPassword();
    const role = this.newRole();
    const companyId = this.targetCompanyId();

    if (!username) {
      this.dialogError.set('Korisničko ime je obavezno.');
      return;
    }
    if (password.length < 8) {
      this.dialogError.set('Lozinka mora imati najmanje 8 znakova.');
      return;
    }
    // firmu traže sve uloge osim globalnog administratora - on joj ne pripada
    if (role !== 'ADMIN' && companyId === null) {
      this.dialogError.set('Korisnik mora pripadati firmi.');
      return;
    }

    this.dialogError.set(null);
    this.userService.create({ username, password, role, companyId }).subscribe({
      next: () => {
        this.closeAddDialog();
        this.notify(`Korisnik "${username}" dodan.`);
        this.reload();
      },
      error: this.failedInDialog('Dodavanje korisnika nije uspjelo.')
    });
  }

  protected remove(user: User): void {
    if (!confirm(`Obrisati korisnika "${user.username}"? Više se neće moći prijaviti.`)) {
      return;
    }
    this.error.set(null);
    this.userService.delete(user.id).subscribe({
      next: () => {
        this.notify(`Korisnik "${user.username}" obrisan.`);
        this.reload();
      },
      error: this.failed('Brisanje korisnika nije uspjelo.')
    });
  }
}

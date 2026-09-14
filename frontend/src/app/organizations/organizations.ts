import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Company } from '../company/company.model';
import { CompanyService } from '../company/company.service';
import { TemplateTransferDialog } from '../template-transfer/template-transfer-dialog';
import { TransferPlan } from '../template-transfer/template-transfer.service';
import { TenantService } from '../tenant/tenant.service';

/**
 * Organizacije (tenanti) - osnovni CRUD nad firmama.
 *
 * Ovaj ekran namjerno NIJE vezan uz aktivnu firmu: iz njega se popunjava izbornik
 * za odabir, pa mora vidjeti sve firme. Kad se arhivira firma koja je trenutno
 * aktivna, odabir se poništava.
 *
 * Brisanje ima dva koraka, i to je najvažnije što ovaj ekran prikazuje. Gornja tablica
 * su aktivne firme, donja arhivirane. Arhiviranje je povratno (gumb "Vrati"), a tek
 * "Isprazni trajno" u donjoj tablici stvarno briše podatke - i za njega se traži da se
 * naziv firme upiše rukom.
 */
@Component({
  selector: 'app-organizations',
  imports: [FormsModule, TemplateTransferDialog],
  templateUrl: './organizations.html',
  styleUrl: './organizations.css',
  host: {
    '(document:keydown.escape)': 'onEscape()'
  }
})
export class Organizations {
  private readonly companyService = inject(CompanyService);
  private readonly tenantService = inject(TenantService);

  protected readonly companies = this.companyService.companies;
  protected readonly activeCompanyId = this.tenantService.activeCompanyId;
  /** Arhivirane firme - drže se lokalno jer ih izbornik aktivne firme ne smije vidjeti. */
  protected readonly archived = signal<Company[]>([]);
  /** Greška koja pripada ekranu: neuspjelo učitavanje, brisanje, preimenovanje u retku. */
  protected readonly error = signal<string | null>(null);
  /**
   * Greška o onome što se upisuje u dijalogu. Ide zasebno jer bi inače završila na
   * stranici IZA zatamnjene pozadine - ondje gdje je korisnik ne vidi.
   */
  protected readonly dialogError = signal<string | null>(null);
  protected readonly success = signal<string | null>(null);

  // obavijest o uspjehu sama nestane nakon nekoliko sekundi; drži se zadnji timer
  // da brze uzastopne akcije ne ostave staru poruku da visi
  private successTimer: ReturnType<typeof setTimeout> | null = null;

  /**
   * Prijenos obrazaca iz jedne firme u drugu stoji na OVOM ekranu, a ne u editoru obrazaca:
   * editor radi nad jednom, odabranom firmom, a prijenos nad dvjema odjednom - i smije ga
   * samo globalni administrator, koji je jedini i ovdje.
   */
  protected readonly showTransferDialog = signal(false);

  protected readonly showAddDialog = signal(false);
  protected readonly newName = signal('');

  // firma koja se trajno prazni (null = dijalog zatvoren) i naziv koji korisnik prepisuje
  protected readonly purgeTarget = signal<Company | null>(null);
  protected readonly purgeConfirmation = signal('');

  // firma koja se trenutno preimenuje (inline), i tekst u polju
  protected readonly editingId = signal<number | null>(null);
  protected readonly editName = signal('');

  constructor() {
    this.reload();
  }

  private reload(): void {
    this.companyService.refresh().subscribe({
      error: () => this.error.set('Dohvaćanje firmi nije uspjelo.')
    });
    this.companyService.archived().subscribe({
      next: (data) => this.archived.set(data),
      error: () => this.error.set('Dohvaćanje arhiviranih firmi nije uspjelo.')
    });
  }

  /** Vrijeme arhiviranja za prikaz; isti oblik kao u dnevniku. */
  protected archivedAt(company: Company): string {
    return company.deletedAt ? new Date(company.deletedAt).toLocaleString('hr-HR') : '';
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

  // uspjeh: očisti grešku, prikaži poruku pa je nakon ~3s makni
  private notify(message: string): void {
    this.error.set(null);
    this.success.set(message);
    if (this.successTimer !== null) {
      clearTimeout(this.successTimer);
    }
    this.successTimer = setTimeout(() => this.success.set(null), 3000);
  }

  protected openTransferDialog(): void {
    this.showTransferDialog.set(true);
  }

  protected onTransferred(plan: TransferPlan): void {
    const count = plan.templates.length;
    const broken = plan.brokenReferences.length;
    this.notify(`Preneseno ${count} ${count === 1 ? 'obrazac' : 'obrazaca'}.`
      + (broken > 0 ? ` ${broken} veza ostala je bez cilja.` : ''));
  }

  protected openAddDialog(): void {
    this.newName.set('');
    this.dialogError.set(null);
    this.showAddDialog.set(true);
  }

  protected closeAddDialog(): void {
    this.showAddDialog.set(false);
    this.dialogError.set(null);
  }

  /** Escape zatvara onaj dijalog koji je otvoren; oba nikad nisu istovremeno. */
  protected onEscape(): void {
    this.closeAddDialog();
    this.closePurgeDialog();
  }

  protected add(): void {
    const name = this.newName().trim();
    if (!name) {
      this.dialogError.set('Naziv firme je obavezan.');
      return;
    }
    this.dialogError.set(null);
    this.companyService.create(name).subscribe({
      next: () => {
        this.closeAddDialog();
        this.notify(`Firma "${name}" dodana.`);
        this.reload();
      },
      error: this.failedInDialog('Dodavanje firme nije uspjelo.')
    });
  }

  protected startEdit(company: Company): void {
    this.editingId.set(company.id);
    this.editName.set(company.name);
  }

  protected cancelEdit(): void {
    this.editingId.set(null);
  }

  protected saveEdit(id: number): void {
    const name = this.editName().trim();
    if (!name) {
      this.success.set(null);
      this.error.set('Naziv firme je obavezan.');
      return;
    }
    this.error.set(null);
    this.companyService.rename(id, name).subscribe({
      next: () => {
        this.editingId.set(null);
        this.notify(`Firma preimenovana u "${name}".`);
        this.reload();
      },
      error: this.failed('Preimenovanje firme nije uspjelo.')
    });
  }

  protected remove(company: Company): void {
    if (!confirm(`Arhivirati firmu "${company.name}"? Nestaje s popisa i njeni se korisnici više neće moći prijaviti, ali svi podaci ostaju i firma se može vratiti.`)) {
      return;
    }
    this.error.set(null);
    this.companyService.delete(company.id).subscribe({
      next: () => {
        // aktivni odabir više ne vrijedi ako je pokazivao na ovu firmu
        this.tenantService.clearIfMatches(company.id);
        this.notify(`Firma "${company.name}" arhivirana.`);
        this.reload();
      },
      error: this.failed('Arhiviranje firme nije uspjelo.')
    });
  }

  protected restore(company: Company): void {
    this.error.set(null);
    this.companyService.restore(company.id).subscribe({
      next: () => {
        this.notify(`Firma "${company.name}" vraćena iz arhive.`);
        this.reload();
      },
      error: this.failed('Vraćanje firme nije uspjelo.')
    });
  }

  protected openPurgeDialog(company: Company): void {
    this.purgeTarget.set(company);
    this.purgeConfirmation.set('');
    this.dialogError.set(null);
  }

  protected closePurgeDialog(): void {
    this.purgeTarget.set(null);
    this.dialogError.set(null);
  }

  /**
   * Trajno pražnjenje. Naziv se upisuje rukom, i to nije formalnost: ovo je jedina radnja
   * u aplikaciji koju ništa ne može poništiti, a "jeste li sigurni?" se klikne u istoj
   * kretnji kao i sam gumb. Prepisivanje naziva traži da se pogleda KOJA je firma u pitanju.
   */
  protected purge(): void {
    const company = this.purgeTarget();
    if (!company) {
      return;
    }
    if (this.purgeConfirmation().trim() !== company.name) {
      this.dialogError.set('Upisani naziv se ne poklapa s nazivom firme.');
      return;
    }
    this.dialogError.set(null);
    this.companyService.purge(company.id).subscribe({
      next: () => {
        this.closePurgeDialog();
        this.notify(`Firma "${company.name}" i svi njeni podaci trajno su uklonjeni.`);
        this.reload();
      },
      error: this.failedInDialog('Pražnjenje firme nije uspjelo.')
    });
  }
}

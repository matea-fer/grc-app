import { Injectable, signal } from '@angular/core';

/**
 * Aktivna firma (tenant) - jedan izvor istine za "koju firmu trenutno gledam".
 *
 * Dok nema prave prijave, tenant bira korisnik u padajućem izborniku gornje
 * trake. Odabir se pamti u localStorage da se osvježavanjem stranice ne izgubi.
 * tenantInterceptor ovu vrijednost lijepi kao TenantID na svaki zaštićeni
 * poziv, pa je komponente ne moraju slati ručno.
 *
 * Kad stigne prava prijava, ovaj servis nestaje - tenant će dolaziti iz JWT-a,
 * a interceptor i backend ostaju isti.
 */
@Injectable({ providedIn: 'root' })
export class TenantService {
  private static readonly STORAGE_KEY = 'activeCompanyId';

  private readonly activeId = signal<number | null>(this.readStored());

  // samo za čitanje izvana - mijenja se isključivo kroz setActive/clear
  readonly activeCompanyId = this.activeId.asReadonly();

  setActive(companyId: number | null): void {
    this.activeId.set(companyId);
    if (companyId === null) {
      localStorage.removeItem(TenantService.STORAGE_KEY);
    } else {
      localStorage.setItem(TenantService.STORAGE_KEY, String(companyId));
    }
  }

  // firma je obrisana ili je nema na popisu - odabir više ne vrijedi
  clearIfMatches(companyId: number): void {
    if (this.activeId() === companyId) {
      this.setActive(null);
    }
  }

  private readStored(): number | null {
    const raw = localStorage.getItem(TenantService.STORAGE_KEY);
    if (raw === null) {
      return null;
    }
    const parsed = Number(raw);
    return Number.isInteger(parsed) ? parsed : null;
  }
}

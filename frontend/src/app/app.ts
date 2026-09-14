import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from './auth/auth.service';
import { CompanyService } from './company/company.service';
import { TenantService } from './tenant/tenant.service';
import { TEME, Tema, ThemeService } from './theme/theme.service';

/**
 * Ljuska aplikacije: gornja traka s izbornikom, podatkom o prijavljenom korisniku i
 * (za ADMIN-a) biračem aktivne firme, ispod nje router-outlet.
 *
 * Traka se ne prikazuje dok korisnik nije prijavljen - na ekranu prijave nema što
 * ponuditi, a i svaki bi poziv iz nje ionako vratio 401.
 *
 * Birač firme postoji samo za globalnog ADMIN-a. Svima ostalima - i običnom korisniku
 * i administratoru firme - firmu određuje token, pa im se ime firme ispisuje kao
 * tekst; nema što birati.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
  private readonly companyService = inject(CompanyService);
  private readonly tenantService = inject(TenantService);
  private readonly authService = inject(AuthService);
  private readonly themeService = inject(ThemeService);
  private readonly router = inject(Router);

  /**
   * Tema se bira ovdje jer je traka jedino mjesto vidljivo sa svakog ekrana. Sam odabir
   * ne prolazi kroz komponentu - servis ga upisuje na `<html>`, pa se primijeni i na
   * dijalozima, koji se crtaju izvan ove komponente.
   */
  protected readonly teme = TEME;
  protected readonly tema = this.themeService.izbor;

  protected readonly companies = this.companyService.companies;
  protected readonly activeCompanyId = this.tenantService.activeCompanyId;
  protected readonly currentUser = this.authService.currentUser;
  protected readonly isLoggedIn = this.authService.isLoggedIn;
  protected readonly isAdmin = this.authService.isAdmin;
  protected readonly isTenantAdmin = this.authService.isTenantAdmin;
  protected readonly canManageUsers = this.authService.canManageUsers;
  protected readonly canEditSchema = this.authService.canEditSchema;

  // ime aktivne firme za natpis u traci
  protected readonly activeCompanyName = computed(() => {
    const id = this.activeCompanyId();
    return this.companies().find((c) => c.id === id)?.name ?? null;
  });

  /**
   * Zašto traka ne pokazuje ono što bi trebala. Bez ovoga se neuspjelo učitavanje ni po
   * čemu ne razlikuje od uredne prazne liste: izbornik firmi je prazan u oba slučaja.
   */
  protected readonly sessionError = signal<string | null>(null);

  /**
   * Natpis prazne stavke u izborniku firmi. Nosi razliku koju sam izbornik ne može
   * pokazati: "nije učitano" nije isto što i "nema firmi".
   */
  protected readonly tenantPlaceholder = computed(() => {
    if (this.sessionError() !== null) {
      return '— popis nije učitan —';
    }
    return this.companies().length === 0 ? '— nema firmi —' : '— odaberi firmu —';
  });

  constructor() {
    // prijava (ili povratak s valjanim tokenom) -> provjeri token i napuni popis firmi
    effect(() => {
      if (!this.isLoggedIn()) {
        return;
      }
      untracked(() => this.loadForSession());
    });
  }

  protected onTenantChange(companyId: number | null): void {
    this.tenantService.setActive(companyId);
  }

  protected onThemeChange(tema: Tema): void {
    this.themeService.postavi(tema);
  }

  protected logout(): void {
    this.authService.logout();
    this.router.navigate(['/prijava']);
  }

  /** Ponovni pokušaj nakon greške - tipično kad se backend u međuvremenu digao. */
  protected retry(): void {
    this.loadForSession();
  }

  private loadForSession(): void {
    // token iz localStorage može biti istekao; authInterceptor na 401 odjavljuje
    this.authService.verify().subscribe({ error: () => {} });

    this.sessionError.set(null);
    this.companyService.refresh().subscribe({
      next: (companies) => {
        // odabir zapamćen u localStorage može pokazivati na firmu koje više nema
        const id = this.activeCompanyId();
        if (id !== null && !companies.some((c) => c.id === id)) {
          this.tenantService.setActive(null);
        }
      },
      error: (error: unknown) => this.sessionError.set(App.describe(error))
    });
  }

  /**
   * Poruka za neuspjelo učitavanje, ili null ako je se ne isplati prikazati.
   *
   * Status 0 znači da odgovora nije ni bilo - zahtjev nije stigao do servera (ugašen
   * backend, prekinuta mreža); 504 vraća razvojni proxy kad backend ne odgovori na
   * vrijeme. To je bitno razlikovati od greške koju je server smišljeno vratio.
   *
   * Kod 401 se namjerno ne javlja ništa: authInterceptor tada odjavljuje i vodi na
   * prijavu, pa bi poruka samo bljesnula na ekranu koji nestaje.
   */
  private static describe(error: unknown): string | null {
    const status = (error as { status?: number })?.status ?? 0;
    if (status === 401) {
      return null;
    }
    if (status === 0 || status === 504) {
      return 'Server ne odgovara - popis firmi nije učitan.';
    }
    return `Popis firmi nije učitan (greška ${status}).`;
  }
}

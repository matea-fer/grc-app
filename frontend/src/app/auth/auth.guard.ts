import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from './auth.service';

/**
 * Guardovi samo skrivaju ekrane; ne štite podatke. Prava zaštita je na backendu -
 * ovdje se preskakanje guarda kaznjava time da svaki poziv vrati 401 ili 403.
 */
export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);
  return authService.isLoggedIn() ? true : router.createUrlTree(['/prijava']);
};

/** Ekrani koji se tiču svih firmi odjednom - samo globalni administrator (Organizacije). */
export const adminGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);
  return authService.isAdmin() ? true : router.createUrlTree(['/podaci']);
};

/**
 * Editor obrazaca - i globalni administrator i administrator firme, svaki unutar svoje
 * firme (doseg brani backend).
 *
 * Obični korisnik ovdje nema što raditi: shemu koristi na ekranu Podaci, gdje se po njoj
 * unose zapisi. Backend istu granicu drži neovisno, s 403.
 */
export const schemaEditorGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);
  return authService.canEditSchema() ? true : router.createUrlTree(['/podaci']);
};

/**
 * Ekran "Korisnici" - i globalni administrator i administrator firme.
 *
 * Guard propušta oboje; koje račune tko vidi odlučuje backend iz tokena, jer se
 * podjela na "sve firme" i "moja firma" ne može braniti u pregledniku.
 */
export const userManagerGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);
  return authService.canManageUsers() ? true : router.createUrlTree(['/podaci']);
};

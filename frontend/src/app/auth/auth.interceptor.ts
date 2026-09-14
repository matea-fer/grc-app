import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthService } from './auth.service';

const LOGIN_URL = '/api/auth/login';

/**
 * Lijepi `Authorization: Bearer` na svaki poziv osim same prijave, i hvata 401.
 *
 * Bez hvatanja 401 istekao token završi kao niz nerazumljivih grešaka na ekranima -
 * ovako se korisnik odjavi i vrati na prijavu, jednom, na jednom mjestu.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const token = authService.token();
  const request =
    token !== null && !req.url.startsWith(LOGIN_URL)
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;

  return next(request).pipe(
    catchError((error: unknown) => {
      // Neuspjela prijava je isto 401, ali ona nije istekla sesija - tu poruku
      // prikazuje sam ekran prijave, pa se ovdje namjerno preskače.
      if (error instanceof HttpErrorResponse && error.status === 401 && !req.url.startsWith(LOGIN_URL)) {
        authService.logout();
        router.navigate(['/prijava']);
      }
      return throwError(() => error);
    })
  );
};

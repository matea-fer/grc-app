import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { TenantService } from './tenant.service';

// Rute koje rade nad podacima jedne firme. Šifrarnici su ovdje iako mogu biti i
// globalni: backend firmu koristi kad je zna, a kad je nema radi samo s globalnima.
const TENANT_SCOPED = ['/api/templates', '/api/logs', '/api/codebooks', '/api/sbom'];

/**
 * Lijepi TenantID, ali SAMO administratoru.
 *
 * Običnom korisniku firmu određuje token i backend mu ovo zaglavlje ionako ignorira -
 * slanje podatka koji ništa ne odlučuje samo bi zavaravalo pri čitanju mrežnog
 * prometa. ADMIN firmu u tokenu nema, pa je bira u traci i šalje ovuda.
 */
export const tenantInterceptor: HttpInterceptorFn = (req, next) => {
  const isScoped = TENANT_SCOPED.some((prefix) => req.url.startsWith(prefix));
  if (!isScoped || !inject(AuthService).isAdmin()) {
    return next(req);
  }

  const companyId = inject(TenantService).activeCompanyId();
  if (companyId === null) {
    return next(req);
  }

  return next(req.clone({ setHeaders: { 'TenantID': String(companyId) } }));
};

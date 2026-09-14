import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { SbomEvaluation, SbomEvaluationDetail } from './sbom.model';

/**
 * SBOM evaluacije. Firma se ne šalje - backend je uzima iz TenantID zaglavlja
 * (tenantInterceptor), kao i za obrasce i zapise.
 *
 * Upload vraća zapis u stanju PENDING; analiza teče u pozadini, pa ekran polla
 * {@link list} dok status ne postane DONE/FAILED.
 */
@Injectable({ providedIn: 'root' })
export class SbomService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = '/api/sbom';

  upload(file: File): Observable<SbomEvaluation> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<SbomEvaluation>(this.apiUrl, form);
  }

  list(): Observable<SbomEvaluation[]> {
    return this.http.get<SbomEvaluation[]>(this.apiUrl);
  }

  detail(id: number): Observable<SbomEvaluationDetail> {
    return this.http.get<SbomEvaluationDetail>(`${this.apiUrl}/${id}`);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}

import { HttpClient, HttpParams } from '@angular/common/http';
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

  upload(file: File, product?: { id: number; name: string | null }): Observable<SbomEvaluation> {
    const form = new FormData();
    form.append('file', file);
    if (product) {
      form.append('productId', String(product.id));
      if (product.name) {
        form.append('productName', product.name);
      }
    }
    return this.http.post<SbomEvaluation>(this.apiUrl, form);
  }

  list(productId?: number | null): Observable<SbomEvaluation[]> {
    let params = new HttpParams();
    if (productId != null) {
      params = params.set('productId', String(productId));
    }
    return this.http.get<SbomEvaluation[]>(this.apiUrl, { params });
  }

  detail(id: number): Observable<SbomEvaluationDetail> {
    return this.http.get<SbomEvaluationDetail>(`${this.apiUrl}/${id}`);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}

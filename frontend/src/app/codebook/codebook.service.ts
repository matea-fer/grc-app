import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  Codebook,
  CodebookItem,
  CodebookItemInput,
  CodebookScope,
  CreateCodebookInput,
  RenameCodebookInput
} from './codebook.model';

/**
 * Šifrarnici i njihove stavke.
 *
 * Firma se ne šalje ručno: tenantInterceptor lijepi TenantID na /api/codebooks za
 * globalnog administratora, a svima ostalima firma dolazi iz tokena.
 *
 * Pretraga i filtar po dosegu idu NA SERVER, kao query parametri - za razliku od
 * ostalih ekrana, koji još filtriraju u pregledniku. Ovdje je to jeftino (obični
 * stupci), pa je dobro mjesto da se obrazac uspostavi.
 */
@Injectable({ providedIn: 'root' })
export class CodebookService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = '/api/codebooks';

  list(scope: CodebookScope | null, search: string): Observable<Codebook[]> {
    let params = new HttpParams();
    if (scope !== null) {
      params = params.set('scope', scope);
    }
    if (search.trim() !== '') {
      params = params.set('search', search.trim());
    }
    return this.http.get<Codebook[]>(this.apiUrl, { params });
  }

  getOne(id: number): Observable<Codebook> {
    return this.http.get<Codebook>(`${this.apiUrl}/${id}`);
  }

  create(payload: CreateCodebookInput): Observable<Codebook> {
    return this.http.post<Codebook>(this.apiUrl, payload);
  }

  rename(id: number, payload: RenameCodebookInput): Observable<Codebook> {
    return this.http.put<Codebook>(`${this.apiUrl}/${id}`, payload);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }

  getItems(codebookId: number): Observable<CodebookItem[]> {
    return this.http.get<CodebookItem[]>(`${this.apiUrl}/${codebookId}/items`);
  }

  /** Puna zamjena popisa: stavka koje u nizu nema se briše. */
  saveItems(codebookId: number, items: CodebookItemInput[]): Observable<CodebookItem[]> {
    return this.http.put<CodebookItem[]>(`${this.apiUrl}/${codebookId}/items`, { items });
  }
}

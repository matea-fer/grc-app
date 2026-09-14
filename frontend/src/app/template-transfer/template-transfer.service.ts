import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Template } from '../template/template.model';

/** Zahtjev za prijenos - obrasci se navode izrijekom, jer se biraju kvačicama. */
export interface TransferRequest {
  sourceCompanyId: number;
  targetCompanyId: number;
  templateIds: number[];
}

export interface TemplatePlan {
  sourceId: number;
  sourceName: string;
  targetName: string;
  renamed: boolean;
  columnCount: number;
}

export interface CodebookPlan {
  name: string;
  scope: 'GLOBAL' | 'TENANT';
  /** true = ciljna firma ga već ima pod tim nazivom, pa se stupci vežu na postojeći. */
  reused: boolean;
  itemCount: number;
}

export interface BrokenReference {
  templateName: string;
  columnKey: string;
  targetName: string;
}

export interface TransferPlan {
  templates: TemplatePlan[];
  codebooks: CodebookPlan[];
  brokenReferences: BrokenReference[];
}

/**
 * Prijenos obrazaca između firmi.
 *
 * Jedini servis koji ne radi nad aktivnom firmom nego nad dvjema navedenima, pa mu putanja
 * i stoji izvan `/api/templates`. Smije ga koristiti samo globalni ADMIN - poslužitelj to
 * provjerava sam, ovdje se ekran samo ne nudi ostalima.
 */
@Injectable({ providedIn: 'root' })
export class TemplateTransferService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = '/api/template-transfer';

  /** Obrasci jedne firme - za popis s kvačicama; ne mora biti aktivna firma. */
  templatesOf(companyId: number): Observable<Template[]> {
    return this.http.get<Template[]>(`${this.apiUrl}/templates`, {
      params: new HttpParams().set('companyId', companyId)
    });
  }

  /** Što bi se dogodilo - ne mijenja ništa. */
  preview(request: TransferRequest): Observable<TransferPlan> {
    return this.http.post<TransferPlan>(`${this.apiUrl}/preview`, request);
  }

  transfer(request: TransferRequest): Observable<TransferPlan> {
    return this.http.post<TransferPlan>(this.apiUrl, request);
  }
}

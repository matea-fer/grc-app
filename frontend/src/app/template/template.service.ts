import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { RecordOption, Template } from './template.model';
import { Page } from '../shared/page.model';
import { TenantService } from '../tenant/tenant.service';

/** Jedan obrazac u najavi skupnog brisanja - s onim što odlazi zajedno s njim. */
export interface TemplateDeletion {
  id: number;
  name: string;
  recordCount: number;
  attachmentCount: number;
}

export interface DeletePlan {
  templates: TemplateDeletion[];
  /** Veze iz obrazaca koji OSTAJU; dok ih ima, brisanje se odbija. */
  blockers: string[];
}

/**
 * Templatei (obrasci) aktivne firme + koji je od njih trenutno odabran.
 *
 * Paralela {@link TenantService}: kao što se firma bira jednom pa vrijedi svugdje,
 * tako se i template bira jednom (dijeli ga Editor i Podaci). Popis se drži kao
 * signal da oba ekrana čitaju isti izvor. Odabir se pamti u localStorage, ali se
 * pri svakom {@link refresh} uskladi sa stvarnim popisom - id zapamćen za jednu
 * firmu ne vrijedi za drugu, pa se tada odabir prebaci na prvi dostupni template.
 *
 * Firma se ne šalje ručno - tenantInterceptor lijepi TenantID na /api/templates.
 */
@Injectable({ providedIn: 'root' })
export class TemplateService {
  private readonly http = inject(HttpClient);
  private readonly tenantService = inject(TenantService);
  private readonly apiUrl = '/api/templates';
  private static readonly STORAGE_KEY = 'activeTemplateId';

  private readonly _templates = signal<Template[]>([]);
  readonly templates = this._templates.asReadonly();

  private readonly _activeId = signal<number | null>(this.readStored());
  readonly activeTemplateId = this._activeId.asReadonly();

  /**
   * Dohvati templatee aktivne firme i uskladi odabir s popisom.
   *
   * Odgovor se odbacuje ako je firma u međuvremenu promijenjena: kod brzog prebacivanja
   * dva su popisa u zraku odjednom, a stigne li stariji zadnji, pod novom firmom bi se
   * prikazali tuđi obrasci - i odabir bi se uskladio s njima.
   */
  refresh(): Observable<Template[]> {
    const requestedFor = this.tenantService.activeCompanyId();
    return this.http.get<Template[]>(this.apiUrl).pipe(
      tap((list) => {
        if (this.tenantService.activeCompanyId() !== requestedFor) {
          return;
        }
        this._templates.set(list);
        this.reconcile(list);
      })
    );
  }

  create(name: string): Observable<Template> {
    return this.http.post<Template>(this.apiUrl, { name });
  }

  rename(id: number, name: string): Observable<Template> {
    return this.http.put<Template>(`${this.apiUrl}/${id}`, { name });
  }

  /** Uključi/isključi „upitnik" način obrasca (samo administrator; backend to i provodi). */
  setQuestionnaire(id: number, value: boolean): Observable<Template> {
    const params = new HttpParams().set('value', String(value));
    return this.http.put<Template>(`${this.apiUrl}/${id}/questionnaire`, null, { params });
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }

  /** Što bi skupno brisanje odnijelo - ne mijenja ništa. */
  deletionPlan(templateIds: number[]): Observable<DeletePlan> {
    return this.http.post<DeletePlan>(`${this.apiUrl}/bulk-delete/preview`, { templateIds });
  }

  /**
   * Obriši više obrazaca odjednom.
   *
   * Postoji uz pojedinačno brisanje, a ne umjesto njega: veza između dva obrasca koji OBA
   * nestaju nije prepreka, pa se par koji pokazuje jedan na drugoga ovime obriše u jednom
   * potezu - pojedinačno se ne da nijednim redoslijedom.
   */
  deleteMany(templateIds: number[]): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/bulk-delete`, { templateIds });
  }

  /**
   * Zapisi jednog obrasca kao ponuda za vezu - jedna stranica, uz pretragu.
   *
   * Pretraga ide na SERVER, a ne u preglednik: ciljani obrazac može imati tisuće zapisa, pa
   * bi ih dohvat "svih pa filtriraj" sve povukao samo da bi ih se prikazalo pedeset. Isto
   * pravilo kao kod pretrage zapisa i šifrarnika.
   */
  options(templateId: number, displayKey: string, search: string, page: number): Observable<Page<RecordOption>> {
    let params = new HttpParams().set('displayKey', displayKey).set('page', page);
    if (search.trim() !== '') {
      params = params.set('q', search.trim());
    }
    return this.http.get<Page<RecordOption>>(`${this.apiUrl}/${templateId}/options`, { params });
  }

  /**
   * Nazivi točno zadanih zapisa - drugo pitanje od ponude.
   *
   * Tablica zapisa ovime razrješava veze koje na njoj stoje: id-evi sa stranice idu jednim
   * pozivom. Kroz ponudu to ne bi išlo, jer je ona poredana po nazivu i podijeljena na
   * stranice - traženi zapis može biti na bilo kojoj.
   */
  optionsByIds(templateId: number, displayKey: string, ids: number[]): Observable<Page<RecordOption>> {
    const params = new HttpParams().set('displayKey', displayKey).set('ids', ids.join(','));
    return this.http.get<Page<RecordOption>>(`${this.apiUrl}/${templateId}/options`, { params });
  }

  setActive(id: number | null): void {
    this._activeId.set(id);
    if (id === null) {
      localStorage.removeItem(TemplateService.STORAGE_KEY);
    } else {
      localStorage.setItem(TemplateService.STORAGE_KEY, String(id));
    }
  }

  /** Firma nije odabrana - nema što učitati, počisti i popis i odabir. */
  clear(): void {
    this._templates.set([]);
    this.setActive(null);
  }

  // Zadrži odabir ako je i dalje na popisu; inače prebaci na prvi (ili ništa).
  private reconcile(list: Template[]): void {
    const current = this._activeId();
    if (current !== null && list.some((t) => t.id === current)) {
      return;
    }
    this.setActive(list.length ? list[0].id : null);
  }

  private readStored(): number | null {
    const raw = localStorage.getItem(TemplateService.STORAGE_KEY);
    if (raw === null) {
      return null;
    }
    const parsed = Number(raw);
    return Number.isInteger(parsed) ? parsed : null;
  }
}

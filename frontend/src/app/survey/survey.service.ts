import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Page } from '../shared/page.model';
import { RecordChange, RelatedGroup, Survey, SurveyInput } from './survey.model';

/** Jedan uvjet pretrage: stupac i ono što je korisnik upisao. */
export interface SurveyFilter {
  column: string;
  value: string;
}

/** Što se traži od jedne stranice zapisa. Sve je neobavezno - bez ičega je to prva stranica. */
export interface SurveyQuery {
  page?: number;
  sortBy?: string | null;
  sortDir?: 'asc' | 'desc';
  filters?: SurveyFilter[];
  /**
   * Suzi dohvat na točno ove zapise.
   *
   * Nije filtar nego druga vrsta pitanja: filtri govore o sadržaju zapisa, ovo o samim
   * retcima. Koristi ga dijalog povezanih zapisa u smjeru naprijed, gdje su id-evi već
   * poznati iz podataka retka.
   */
  ids?: number[];
}

/**
 * Zapisi jednog templatea. Template ide kroz putanju; firma se ne šalje - backend
 * je uzima iz TenantID zaglavlja i provjeri da template pripada toj firmi.
 */
@Injectable({ providedIn: 'root' })
export class SurveyService {
  private readonly http = inject(HttpClient);

  private surveysUrl(templateId: number): string {
    return `/api/templates/${templateId}/surveys`;
  }

  /**
   * Jedna stranica zapisa. Pretragu, sortiranje i rezanje na stranice radi BAZA - klijent
   * šalje što traži i dobiva najviše 50 redaka, bez obzira koliko ih obrazac ima.
   *
   * `filter` se šalje više puta, po jednom za svaki uvjet (`grad:zagreb`), i uvjeti se na
   * serveru spajaju s I. Vrijednost smije sadržavati dvotočku - dijeli se samo na prvoj.
   */
  getSurveys(templateId: number, query: SurveyQuery = {}): Observable<Page<Survey>> {
    let params = new HttpParams().set('page', query.page ?? 0);
    if (query.sortBy) {
      params = params.set('sortBy', query.sortBy).set('sortDir', query.sortDir ?? 'asc');
    }
    for (const filter of query.filters ?? []) {
      params = params.append('filter', `${filter.column}:${filter.value}`);
    }
    if (query.ids?.length) {
      params = params.set('ids', query.ids.join(','));
    }
    return this.http.get<Page<Survey>>(this.surveysUrl(templateId), { params });
  }

  createSurvey(templateId: number, payload: SurveyInput): Observable<Survey> {
    return this.http.post<Survey>(this.surveysUrl(templateId), payload);
  }

  updateSurvey(templateId: number, id: number, payload: SurveyInput): Observable<Survey> {
    return this.http.put<Survey>(`${this.surveysUrl(templateId)}/${id}`, payload);
  }

  deleteSurvey(templateId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.surveysUrl(templateId)}/${id}`);
  }

  /**
   * Zaključavanje i otključavanje idu kao podresurs zapisa, a ne kao polje u PUT-u.
   *
   * Razlog je praktičan: PUT je puna zamjena podataka i zaključan ga zapis više ne prima,
   * pa se kroz njega ne bi imalo kako ni otključati. Otključavanje backend dopušta samo
   * administratoru - sučelje gumb sakrije, ali odluku donosi server.
   */
  lockSurvey(templateId: number, id: number): Observable<Survey> {
    return this.http.post<Survey>(`${this.surveysUrl(templateId)}/${id}/lock`, {});
  }

  unlockSurvey(templateId: number, id: number): Observable<Survey> {
    return this.http.delete<Survey>(`${this.surveysUrl(templateId)}/${id}/lock`);
  }

  /**
   * Revizijski trag jednog zapisa, najnovije prvo.
   *
   * Dohvaća se tek na klik, a ne uz tablicu: povijest raste brže od samih zapisa, pa bi
   * uz svaki redak značila da se s podacima povuče i sve što im se ikad dogodilo.
   */
  getHistory(templateId: number, id: number): Observable<RecordChange[]> {
    return this.http.get<RecordChange[]>(`${this.surveysUrl(templateId)}/${id}/history`);
  }

  /**
   * Tko sve pokazuje na ovaj zapis - skupine po obrascu i stupcu, s brojem zapisa.
   *
   * Ništa se ne šalje uz putanju: koji stupci na ovaj obrazac pokazuju već piše u shemama, pa
   * server sam nađe skupine. Sami zapisi se dohvaćaju tek kad se skupina odabere.
   */
  getRelated(templateId: number, id: number): Observable<RelatedGroup[]> {
    return this.http.get<RelatedGroup[]>(`${this.surveysUrl(templateId)}/${id}/related`);
  }
}

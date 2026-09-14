import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { Company } from './company.model';

/**
 * CRUD nad firmama (organizacijama). Ove rute NISU vezane uz tenant kontekst -
 * iz njih se puni izbornik za odabir aktivne firme, pa moraju vidjeti sve.
 *
 * Popis firmi drži se kao signal da ga i gornja traka (izbornik) i ekran
 * Organizacije čitaju iz istog izvora - promjena na jednom mjestu odmah se vidi
 * na drugom. Nakon svake izmjene pozivatelj osvježi popis kroz {@link refresh}.
 */
@Injectable({ providedIn: 'root' })
export class CompanyService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = '/api/companies';

  private readonly _companies = signal<Company[]>([]);
  readonly companies = this._companies.asReadonly();

  /** Ponovno dohvati popis firmi u dijeljeni signal. */
  refresh(): Observable<Company[]> {
    return this.http.get<Company[]>(this.apiUrl).pipe(tap((data) => this._companies.set(data)));
  }

  create(name: string): Observable<Company> {
    return this.http.post<Company>(this.apiUrl, { name });
  }

  rename(id: number, name: string): Observable<Company> {
    return this.http.put<Company>(`${this.apiUrl}/${id}`, { name });
  }

  /**
   * Arhivira firmu - ona nestaje s popisa i njeni se korisnici ne mogu prijaviti, ali svi
   * njeni podaci ostaju i {@link restore} ih vraća. Ne drži se u dijeljenom signalu jer
   * izbornik u traci prikazuje samo aktivne firme.
   */
  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }

  /** Arhivirane firme; dohvaća ih samo ekran Organizacije, pa idu izravno pozivatelju. */
  archived(): Observable<Company[]> {
    return this.http.get<Company[]>(`${this.apiUrl}/archived`);
  }

  restore(id: number): Observable<Company> {
    return this.http.post<Company>(`${this.apiUrl}/${id}/restore`, {});
  }

  /** Nepovratno: briše obrasce, zapise, priloge, šifrarnike i korisnike firme. */
  purge(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}/purge`);
  }
}

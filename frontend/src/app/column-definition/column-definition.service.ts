import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ColumnDefinition, ColumnDefinitionInput, EMPTY_COLUMN_OPTIONS } from './column-definition.model';

/**
 * Stupci sheme jednog templatea. Template ide kroz putanju; firma se ne šalje -
 * backend je uzima iz TenantID zaglavlja (tenantInterceptor) i provjeri da template
 * pripada toj firmi.
 */
@Injectable({ providedIn: 'root' })
export class ColumnDefinitionService {
  private readonly http = inject(HttpClient);

  private columnsUrl(templateId: number): string {
    return `/api/templates/${templateId}/columns`;
  }

  /**
   * Stupac s postavkama koje sigurno postoje.
   *
   * Granica prema serveru je jedino mjesto na kojem se to isplati napraviti: tip kaže da
   * `options` postoji, pa bi svako mjesto koje ga čita inače moralo provjeravati - a jedno
   * koje zaboravi sruši cijeli ekran, i to zbog stupca koji je samo stariji od te postavke.
   */
  private static normalize(column: ColumnDefinition): ColumnDefinition {
    return {
      ...column,
      width: column.width ?? null,
      options: { ...EMPTY_COLUMN_OPTIONS, ...(column.options ?? {}) }
    };
  }

  getForTemplate(templateId: number): Observable<ColumnDefinition[]> {
    return this.http
      .get<ColumnDefinition[]>(this.columnsUrl(templateId))
      .pipe(map((columns) => columns.map(ColumnDefinitionService.normalize)));
  }

  /**
   * Nov redoslijed stupaca. Šalju se SVI ključevi, ne samo premješteni.
   *
   * Time backend može provjeriti da je skup ostao isti: popis koji se ne poklapa znači da je
   * netko u međuvremenu dodao ili maknuo stupac, pa se premještanje odbija umjesto da se tiho
   * primijeni na drugu shemu. Zapisi se pritom ne diraju - poredak stupaca je stvar prikaza.
   */
  reorder(templateId: number, columnKeys: string[]): Observable<ColumnDefinition[]> {
    return this.http
      .put<ColumnDefinition[]>(`${this.columnsUrl(templateId)}/order`, { columnKeys })
      .pipe(map((columns) => columns.map(ColumnDefinitionService.normalize)));
  }

  create(templateId: number, payload: ColumnDefinitionInput): Observable<ColumnDefinition> {
    return this.http
      .post<ColumnDefinition>(this.columnsUrl(templateId), payload)
      .pipe(map(ColumnDefinitionService.normalize));
  }

  /**
   * Uredi postojeći stupac. Backend migrira zapise templatea: preimenovanje mijenja
   * ključ u svim zapisima, a promjena tipa čisti vrijednosti koje mu ne odgovaraju.
   *
   * Izmjena koja zapisima UZIMA vrijednosti odbija se s 409 i oznakom `COLUMN_DATA_LOSS`, uz
   * broj zahvaćenih zapisa u poruci. Tek nakon što korisnik to potvrdi, poziv se ponovi s
   * `confirmDataLoss: true`. Broj tako uvijek dolazi od onoga tko ga je izračunao - preglednik
   * ga ne procjenjuje, jer bi procjena i stvarni učinak mogli razići.
   */
  update(templateId: number, oldKey: string, payload: ColumnDefinitionInput,
         confirmDataLoss = false): Observable<ColumnDefinition> {
    return this.http
      .put<ColumnDefinition>(`${this.columnsUrl(templateId)}/${encodeURIComponent(oldKey)}`,
        { ...payload, confirmDataLoss })
      .pipe(map(ColumnDefinitionService.normalize));
  }

  /** Miče stupac iz templatea i briše vrijednosti tog ključa iz njegovih zapisa. */
  delete(templateId: number, columnKey: string): Observable<void> {
    return this.http.delete<void>(`${this.columnsUrl(templateId)}/${encodeURIComponent(columnKey)}`);
  }
}

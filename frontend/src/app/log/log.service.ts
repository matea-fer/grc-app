import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Page } from '../shared/page.model';
import { LogEntry } from './log.model';

/** Dnevnik aktivne firme. Samo čitanje - zapise stvaraju isključivo servisi backenda. */
@Injectable({ providedIn: 'root' })
export class LogService {
  private readonly http = inject(HttpClient);

  /**
   * Jedna stranica dnevnika, najnovije prvo.
   *
   * `from` je datum OD kojeg se gleda (uključivo), u obliku `yyyy-MM-dd`; bez njega se gleda
   * sve. Datum je lokalni i server ga tumači u našoj vremenskoj zoni - inače bi odabir 1.8.
   * ljeti povukao i zapise od 31.7. poslije 22 sata.
   */
  getLogs(page = 0, from: string | null = null, action: string | null = null): Observable<Page<LogEntry>> {
    let params = new HttpParams().set('page', page);
    if (from) {
      params = params.set('from', from);
    }
    if (action) {
      params = params.set('action', action);
    }
    return this.http.get<Page<LogEntry>>('/api/logs', { params });
  }
}

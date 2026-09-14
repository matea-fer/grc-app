import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Attachment } from './attachment.model';

/**
 * Prilozi zapisa jednog obrasca. Obrazac ide kroz putanju; firma se ne šalje - backend je
 * uzima iz TenantID zaglavlja i provjeri da obrazac pripada toj firmi.
 */
@Injectable({ providedIn: 'root' })
export class AttachmentService {
  private readonly http = inject(HttpClient);

  private baseUrl(templateId: number): string {
    return `/api/templates/${templateId}`;
  }

  /**
   * Svi prilozi obrasca odjednom.
   *
   * Tablica ih treba za sve retke; dohvat po retku značio bi jedan poziv po zapisu, a njih
   * na ekranu zna biti stotinu.
   */
  listForTemplate(templateId: number): Observable<Attachment[]> {
    return this.http.get<Attachment[]>(`${this.baseUrl(templateId)}/attachments`);
  }

  upload(templateId: number, surveyId: number, columnKey: string, file: File): Observable<Attachment> {
    const form = new FormData();
    form.append('file', file);
    // Content-Type se NE postavlja ručno: preglednik ga sam složi zajedno s granicom
    // između dijelova, a ručno postavljen bi tu granicu izostavio i server ne bi znao
    // gdje datoteka počinje.
    return this.http.post<Attachment>(
      `${this.baseUrl(templateId)}/surveys/${surveyId}/attachments/${encodeURIComponent(columnKey)}`,
      form
    );
  }

  /**
   * Sadržaj datoteke.
   *
   * Ide kroz HttpClient, a ne kao obična poveznica, jer zahtjev mora nositi token iz
   * `authInterceptor` - poveznica ga ne bi imala i backend bi je odbio s 401.
   */
  download(templateId: number, attachmentId: number): Observable<Blob> {
    return this.http.get(`${this.baseUrl(templateId)}/attachments/${attachmentId}/content`, {
      responseType: 'blob'
    });
  }

  delete(templateId: number, attachmentId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl(templateId)}/attachments/${attachmentId}`);
  }
}

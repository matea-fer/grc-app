import { Injectable, inject, signal, untracked } from '@angular/core';

import { TemplateService } from '../template/template.service';

/**
 * Nazivi povezanih zapisa: id → naziv, jednom dohvaćeno pa zapamćeno.
 *
 * U zapisu stoji samo `id` — naziv je u drugom obrascu i mijenja se neovisno o nama. Ćelija
 * ga zato mora dohvatiti, a tablica od 50 redaka ne smije zbog toga poslati 50 zahtjeva.
 * Ovdje se traženi id-evi skupe i pošalju odjednom, a odgovor ostane u memoriji do osvježenja
 * stranice.
 *
 * Zašto se naziv ne sprema uz vezu u sam zapis: kopija bi bila brža, ali bi zastarjela čim se
 * ciljani zapis preimenuje. U alatu za usklađenost zastarjeli naziv nije nespretnost nego
 * kvar — izvještaj bi tvrdio nešto što u podacima više ne piše.
 */
@Injectable({ providedIn: 'root' })
export class ReferenceLabelService {
  private readonly templateService = inject(TemplateService);

  /**
   * Ključ je (obrazac, stupac za naziv, id): isti zapis se u dva stupca može prikazivati
   * pod dva različita naziva, pa sam id ne bi bio dovoljan.
   */
  private readonly labels = signal<ReadonlyMap<string, string>>(new Map());

  /**
   * Id-evi za koje je zahtjev već poslan.
   *
   * Namjerno OBIČAN Set, a ne signal: ovo nije stanje koje se prikazuje nego bilješka o tome
   * što je u zraku. Da je signal, njegova bi izmjena ponovno pokrenula efekt koji je zahtjev
   * i poslao — dakle beskonačan krug.
   */
  private readonly pending = new Set<string>();

  /** Naziv, ili null dok se ne dohvati. Čita signal, pa se prikaz sam osvježi kad odgovor stigne. */
  labelOf(templateId: number, displayKey: string, id: number): string | null {
    return this.labels().get(this.key(templateId, displayKey, id)) ?? null;
  }

  /**
   * Pobrini se da nazivi za zadane id-eve budu (ili budu uskoro) poznati.
   *
   * Postojeće stanje se čita kroz `untracked`: pozivatelj je efekt koji ovo zove nakon što
   * pročita zapise, a čitanje `labels` bi ga pretplatilo na vlastiti rezultat i vrtjelo u krug.
   */
  ensure(templateId: number, displayKey: string, ids: number[]): void {
    if (!displayKey) {
      return;
    }
    const known = untracked(this.labels);
    const missing = [...new Set(ids)].filter((id) => {
      const key = this.key(templateId, displayKey, id);
      return !known.has(key) && !this.pending.has(key);
    });
    if (missing.length === 0) {
      return;
    }
    missing.forEach((id) => this.pending.add(this.key(templateId, displayKey, id)));

    this.templateService.optionsByIds(templateId, displayKey, missing).subscribe({
      next: (page) => {
        const updated = new Map(untracked(this.labels));
        page.content.forEach((option) => {
          updated.set(this.key(templateId, displayKey, option.id), option.label);
        });
        this.labels.set(updated);
        missing.forEach((id) => this.pending.delete(this.key(templateId, displayKey, id)));
      },
      error: () => {
        // Neuspjeh se pušta van iz `pending`, da sljedeći prikaz smije pokušati ponovno.
        // Ćelija do tada pokazuje sam id - to je i dalje istina o tome što u zapisu piše.
        missing.forEach((id) => this.pending.delete(this.key(templateId, displayKey, id)));
      }
    });
  }

  /** Naziv koji je upravo saznan drugim putem (npr. odabirom u dijalogu) - da ćelija ne čeka zahtjev. */
  remember(templateId: number, displayKey: string, id: number, label: string): void {
    const updated = new Map(untracked(this.labels));
    updated.set(this.key(templateId, displayKey, id), label);
    this.labels.set(updated);
  }

  private key(templateId: number, displayKey: string, id: number): string {
    return `${templateId}|${displayKey}|${id}`;
  }
}

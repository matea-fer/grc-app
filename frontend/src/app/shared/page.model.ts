/**
 * Jedna stranica rezultata sa servera.
 *
 * `totalElements` je broj SVIH redaka koji odgovaraju upitu, ne samo onih na ovoj stranici -
 * bez njega se ne da nacrtati ni „1–50 od 1.234” ni popis stranica.
 *
 * Oblik je naš (backend ga sastavlja u `PageResponse`), a ne Springov ugrađeni: taj se s
 * verzijama mijenjao, pa bi nadogradnja knjižnice na backendu tiho razbila ovaj ekran.
 */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Prazna stranica - početno stanje ekrana, prije prvog odgovora. */
export function emptyPage<T>(): Page<T> {
  return { content: [], page: 0, size: 0, totalElements: 0, totalPages: 0 };
}

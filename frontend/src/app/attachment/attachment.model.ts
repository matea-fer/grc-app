/**
 * Datoteka priložena uz jedan zapis, na stupcu tipa „Datoteka".
 *
 * Sadržaj NIJE ovdje - dohvaća se zasebnim pozivom, i to tek kad ga korisnik zatraži.
 * Popis priloga se crta uz svaki redak tablice, pa bi datoteke u odgovoru značile da se
 * pri svakom otvaranju ekrana prenese sve što je ikad priloženo.
 *
 * Prilog se ne sprema u `data` zapisa: stupac tipa „file" u zapisu ne drži ništa, a što je
 * priloženo zna isključivo ova lista. Zapisati to na dva mjesta značilo bi da se mogu
 * razići - zapis koji tvrdi da datoteka postoji, i popis koji je nema.
 */
export interface Attachment {
  id: number;
  surveyId: number;
  columnKey: string;
  fileName: string;
  /** Vrsta koju je javio preglednik pri slanju; služi samo za prikaz. */
  contentType: string | null;
  sizeBytes: number;
  uploadedBy: string;
  /** ISO trenutak s backenda */
  uploadedAt: string;
}

/** Veličina u obliku koji se čita - bajtovi uz datoteku nikome ništa ne znače. */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${Math.round(bytes / 1024)} kB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

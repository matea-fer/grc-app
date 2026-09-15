// Jedan obrazac (template) firme: imenovani skup stupaca sa svojim zapisima.
// Firma ih može imati više; stupci i zapisi žive pod templateom, ne pod firmom.
export interface Template {
  id: number;
  name: string;
  /**
   * Upitnik: retke (pitanja) definira administrator u Editoru i vide se na svakoj
   * instanci, a korisnik na Podacima samo bira odgovor (šifrarnik), ne dodaje retke.
   */
  questionnaire: boolean;
}

// Firma za koju se template stvara NE ide u tijelu - backend je uzima iz
// TenantID zaglavlja (aktivna firma iz izbornika).
export interface TemplateInput {
  name: string;
}

/**
 * Jedan zapis kakav se nudi u dijalogu za odabir veze, i kakav se prikazuje u ćeliji.
 *
 * Namjerno samo dvoje: `id` je ono što se sprema u zapis, `label` ono što čovjek vidi.
 * Naziv se ne sprema uz vezu nego se dohvaća pri svakom čitanju - kopija bi zastarjela
 * čim se ciljani zapis preimenuje.
 */
export interface RecordOption {
  id: number;
  label: string;
  /**
   * Vrijednosti zapisa - dijalog odabira prikazuje CIJELI redak, jer jedan stupac često ne
   * razlikuje dva zapisa. Prazno je ondje gdje se traže samo nazivi (ćelije tablice).
   */
  data?: Record<string, unknown>;
}

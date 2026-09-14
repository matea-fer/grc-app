// Fiksna su samo infrastrukturna polja. Sve poslovno je u `data` i određeno
// shemom firme, pa dodavanje novog polja ne dira ovaj file.
//
// Iznimka su `locked*`: zaključanost nije podatak koji obrazac prikuplja nego stanje
// samog zapisa - ono odlučuje smije li se `data` uopće mijenjati. Stiže uz zapis da
// tablica ne mora pogađati zašto joj je izmjena odbijena tek nakon ispunjene forme.
export interface Survey {
  id: number;
  companyId: number;
  templateId: number;
  data: Record<string, unknown>;
  /** Zaključan zapis se ne mijenja, ne briše i ne prima priloge. */
  locked: boolean;
  lockedAt: string | null;
  /** Tko je zaključao; null kad zapis nije zaključan. */
  lockedBy: string | null;
}

// Firma se NE šalje u tijelu - backend je uzima iz TenantID zaglavlja
// (postavlja ga tenantInterceptor). Klijent kojem bismo vjerovali companyId
// mogao bi upisati pod tuđu firmu.
export interface SurveyInput {
  data: Record<string, unknown>;
}

/**
 * Jedna promjena jednog polja - redak dijaloga „Povijest”.
 *
 * `columnKey` je ključ kakav je bio U TRENUTKU promjene i ne mijenja se kad se stupac
 * kasnije preimenuje; `columnLabel` je naziv iz trenutne sheme, a kad stupca više nema,
 * server vraća sam ključ.
 *
 * Vrijednosti su tekst, a ne izvorni tip: u trag ide ono što je čovjek vidio. `null` znači
 * da vrijednosti nije bilo - kod `oldValue` to je nastanak, kod `newValue` brisanje.
 */
export interface RecordChange {
  changedAt: string;
  username: string | null;
  columnKey: string;
  columnLabel: string;
  oldValue: string | null;
  newValue: string | null;
}

/** U kojem smjeru veza ide u odnosu na zapis nad kojim je gumb pritisnut. */
export type LinkDirection = 'OUTGOING' | 'INCOMING';

/**
 * Jedna skupina povezanih zapisa - jedna kartica u dijalogu „Povezani zapisi”.
 *
 * Skupine, a ne jedan popis, jer povezani zapisi dolaze iz RAZLIČITIH obrazaca, a svaki od
 * njih ima svoje stupce - spojeni u jednu tablicu ne bi imali zajedničko zaglavlje.
 *
 * Smjer `OUTGOING` su zapisi na koje ovaj pokazuje. Da veza postoji, vidi se i u ćeliji - ali
 * ondje stoji samo NAZIV; ovdje se vidi i sadržaj tog zapisa. Smjer `INCOMING` su zapisi koji
 * pokazuju na ovaj, a to se drugdje ne može dobiti jer vezu drži samo dijete.
 *
 * Skupina ne nosi same zapise nego samo koliko ih je; dohvaćaju se tek kad se odabere.
 */
export interface RelatedGroup {
  templateId: number;
  templateName: string;
  /**
   * Stupac kroz koji veza ide. Kod `INCOMING` je to stupac TOG obrasca i njime se filtrira;
   * kod `OUTGOING` je stupac OVOG obrasca i služi samo kao naziv kartice.
   */
  columnKey: string;
  columnLabel: string;
  direction: LinkDirection;
  total: number;
  /** Kod `OUTGOING`: id-evi na koje ovaj zapis pokazuje; inače prazno. */
  recordIds: number[];
}

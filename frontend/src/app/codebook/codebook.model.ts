/**
 * Doseg šifrarnika:
 *   GLOBAL - zajednički za sve firme; uređuje ga samo globalni administrator
 *   TENANT - pripada jednoj firmi
 *
 * Popisi su odvojeni: firma ne može dodavati stavke u globalni šifrarnik. Kome
 * globalni ne odgovara, radi svoj.
 */
export type CodebookScope = 'GLOBAL' | 'TENANT';

export interface Codebook {
  id: number;
  name: string;
  scope: CodebookScope;
  /** null za GLOBAL doseg */
  companyId: number | null;
  /** izračunato na backendu, nije polje zapisa */
  itemCount: number;
}

// Firma se NE šalje - backend je uzima iz konteksta (token, odnosno TenantID za
// globalnog administratora). Klijentu kojem bi se vjerovalo mogao bi se podmetnuti
// tuđi companyId.
export interface CreateCodebookInput {
  name: string;
  scope: CodebookScope;
}

/** Mijenja se samo naziv - doseg ne, jer bi šifrarnik odjednom pripadao nekom drugom. */
export interface RenameCodebookInput {
  name: string;
}

export interface CodebookItem {
  id: number;
  /** tehnička stabilna šifra koja se sprema u podatke ("OPEN") */
  code: string;
  /** čitljiv naziv koji korisnik vidi ("Otvoren") */
  name: string;
  /** neaktivna se ne nudi za nove unose, ali ostaje čitljiva u starim zapisima */
  active: boolean;
  sortOrder: number;
}

// sortOrder se NE šalje - server ga dodjeljuje po položaju u nizu, pa klijent šalje
// redoslijed, a ne brojeve
export interface CodebookItemInput {
  /** postojeća stavka, ili null za novu */
  id: number | null;
  code: string;
  name: string;
  active: boolean;
}

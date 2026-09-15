// Tipovi 'select' (vlastiti ugrađeni popis) i 'boolean' (Da/Ne) su ukinuti: oboje je
// sada 'codebook', stupac koji vrijednosti uzima iz šifrarnika. Popis tako živi na
// jednom mjestu i dijeli ga više obrazaca, umjesto da se prepisuje u svaki stupac.
//
//   formula - vrijednost se ne unosi nego računa iz drugih stupaca; računa je server
//   button  - stupac bez vrijednosti: u ćeliji stoji gumb
//   file    - stupac bez vrijednosti: u ćeliji stoje priložene datoteke i gumb za novu.
//             Prilozi NE stoje u zapisu nego u vlastitoj tablici (vidi Attachment).
//   reference - vrijednost je ZAPIS drugog obrasca; u zapisu stoji njegov id, a prikazuje se
//             naziv iz stupca koji je taj stupac odredio kao naziv (options.displayColumnKey)
export type ColumnDefinitionType =
  | 'string'
  | 'number'
  | 'date'
  | 'codebook'
  | 'formula'
  | 'button'
  | 'file'
  | 'reference'
  | 'link';

/** Kako se bira vrijednost iz šifrarnika. */
export type PickerMode = 'dropdown' | 'checkbox' | 'popup';

/** Cijeli broj ili decimalni - određuje i unos i provjeru. */
export type NumberMode = 'int' | 'float';

/** Samo datum, ili datum s vremenom. */
export type DateMode = 'date' | 'datetime';

/**
 * Što gumb radi na klik.
 *
 *   history - otvara revizijski trag retka: koje se polje kada, iz čega i u što promijenilo
 *   lock    - zaključava zapis: redak se više ne mijenja, ne briše i ne prima priloge,
 *             a otključati ga može samo administrator (globalni ili firmin)
 *   related - otvara SVE zapise koji pokazuju na ovaj zapis, iz bilo kojeg obrasca; čita vezu
 *             unatrag, jer smjer naprijed već pokazuje sama ćelija referentnog stupca.
 *             Nema nikakvih postavki - koji stupci na obrazac pokazuju već piše u shemama.
 *
 * Postavka je od početka NAZIV radnje, a ne zastavica - zato je svaka sljedeća radnja dodala
 * samo jednu vrijednost, umjesto nove postavke i novog grananja na svakom mjestu.
 */
export type ButtonAction = 'history' | 'lock' | 'related' | 'sbom';

/**
 * Što se događa s vezama kad se obriše zapis na koji pokazuju.
 *
 *   restrict - brisanje se odbija dok veze postoje (zadano)
 *   clear    - zapis se obriše, a veze na njega se prekinu
 *
 * Zadano je zabrana, i to namjerno: veza koja tiho nestane je izgubljen podatak, a u alatu
 * za usklađenost je upravo veza ono što se dokazuje. Prekid mora biti izričita odluka.
 */
export type OnTargetDelete = 'restrict' | 'clear';

/**
 * Postavke stupca koje ovise o njegovom TIPU.
 *
 * Stoje odvojeno od ostalih atributa jer ih ima puno i stalno dolaze nove; da su sve u
 * ColumnDefinition, svaki bi poziv morao nabrojati i onih desetak koje s tim stupcem
 * nemaju veze. Postavka koja se odabranom tipu ne tiče mora ostati null - backend takav
 * stupac odbija, jer bi inače u shemi ostala tvrdnja koju ništa ne provodi.
 */
export interface ColumnOptions {
  /** broj: vrijednost dodjeljuje server pri spremanju */
  autoIncrement: boolean;
  /** broj: cijeli ili decimalni; null se ponaša kao 'float' */
  numberMode: NumberMode | null;
  min: number | null;
  max: number | null;
  /** broj: uzorak prikaza, npr. "#.##0,00"; ne dira spremljenu vrijednost */
  numberFormat: string | null;
  /** tekst i broj: regularni izraz koji vrijednost mora zadovoljiti */
  pattern: string | null;
  /** datum: null se ponaša kao 'date' */
  dateMode: DateMode | null;
  pickerMode: PickerMode | null;
  /** šifrarnik: vrijednost postaje NIZ šifara umjesto jedne */
  multiple: boolean;
  /** šifrarnik: upisana vrijednost koje još nema dodaje se u šifrarnik */
  allowNewValues: boolean;
  /** formula: izraz, npr. "[cijena] * [kolicina]" */
  formula: string | null;
  /** natpis na gumbu u ćeliji - dijele ga tipovi "button" i "file" */
  buttonLabel: string | null;
  /** gumb: što radi na klik; obavezno za tip "button", null za sve ostalo */
  buttonAction: ButtonAction | null;
  /** veza: obrazac na čiji zapis stupac pokazuje */
  targetTemplateId: number | null;
  /** veza: stupac ciljanog obrasca koji se prikazuje umjesto id-a */
  displayColumnKey: string | null;
  /** veza: što se događa kad se ciljani zapis briše */
  onTargetDelete: OnTargetDelete | null;
}

/** Stupac bez ijedne postavke ovisne o tipu. */
export const EMPTY_COLUMN_OPTIONS: ColumnOptions = {
  autoIncrement: false,
  numberMode: null,
  min: null,
  max: null,
  numberFormat: null,
  pattern: null,
  dateMode: null,
  pickerMode: null,
  multiple: false,
  allowNewValues: false,
  formula: null,
  buttonLabel: null,
  buttonAction: null,
  targetTemplateId: null,
  displayColumnKey: null,
  onTargetDelete: null
};

// Stupac nema id - živi kao element JSON niza unutar jednog templatea, pa ga
// jednoznačno određuje par (templateId, columnKey). Globalnih stupaca više nema.
//
// Uz tip i šifrarnik stupac nosi i pravila unosa:
//   label        - naziv za korisnika; prazan znači "prikaži ključ"
//   required     - redak se ne može spremiti dok polje nije popunjeno
//   unique       - vrijednost se ne smije ponoviti ni u jednom drugom retku istog obrasca
//   defaultValue - predpopunjena vrijednost u formi, uvijek kao tekst (parsira se prema tipu)
//   readOnly     - korisnik ovo polje ne unosi; vrijednost postavlja backend i ostaje takva
//   width        - širina stupca u tablici zapisa, u pikselima; null = neka odluči preglednik
export interface ColumnDefinition {
  columnKey: string;
  columnType: ColumnDefinitionType;
  /**
   * Šifrarnik iz kojeg dolaze dopuštene vrijednosti; null za sve ostale tipove.
   * U redak se sprema ŠIFRA stavke, a ne njezin naziv - naziv se smije mijenjati, šifra ne.
   * Same stavke se dohvaćaju zasebno (CodebookService.getItems), jednom po šifrarniku.
   */
  codebookId: number | null;
  label: string | null;
  required: boolean;
  unique: boolean;
  defaultValue: string | null;
  readOnly: boolean;
  width: number | null;
  options: ColumnOptions;
}

// Template za koji stupac vrijedi ide kroz putanju (/api/templates/{id}/columns),
// ne kroz tijelo.
export interface ColumnDefinitionInput {
  columnKey: string;
  columnType: ColumnDefinitionType;
  codebookId: number | null;
  label: string | null;
  required: boolean;
  unique: boolean;
  defaultValue: string | null;
  readOnly: boolean;
  width: number | null;
  options: ColumnOptions;
}

/**
 * Format broja rastavljen na ono što se stvarno primjenjuje pri prikazu.
 *
 * Uzorak se piše onako kako se piše u ovim krajevima ("#.##0,00" = tisućice točkom,
 * dvije decimale zarezom), ali od cijelog uzorka na kraju vrijede samo dva podatka.
 * Zato se ne gradi vlastiti ispisivač uzoraka nego se ta dva podatka predaju
 * Intl.NumberFormat, koji zna i za jezik i za rubne slučajeve.
 */
export interface NumberFormatParts {
  decimals: number;
  grouping: boolean;
}

/**
 * Uzorak formata pretvoren u broj decimala i zastavicu tisućica.
 *
 * Decimalni dio je ono iza ZADNJEG zareza; tisućice se prepoznaju po točki (ili razmaku)
 * u cjelobrojnom dijelu. Uzorak koji se ne da pročitati daje null - tada se broj ispisuje
 * kako jest, jer je to bolje od pogrešnog formata.
 */
export function parseNumberFormat(pattern: string | null): NumberFormatParts | null {
  const text = pattern?.trim();
  if (!text) {
    return null;
  }
  const lastComma = text.lastIndexOf(',');
  const integerPart = lastComma === -1 ? text : text.slice(0, lastComma);
  const decimalPart = lastComma === -1 ? '' : text.slice(lastComma + 1);

  // u uzorku smiju stajati samo znamenke, # i razdjelnici - sve drugo znači da uzorak
  // nije ono što mislimo da jest
  if (!/^[#0.\s]*$/.test(integerPart) || !/^[#0]*$/.test(decimalPart)) {
    return null;
  }
  return { decimals: decimalPart.length, grouping: /[.\s]/.test(integerPart) };
}

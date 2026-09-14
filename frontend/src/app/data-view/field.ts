import { CodebookItem } from '../codebook/codebook.model';
import {
  ColumnDefinition,
  ColumnDefinitionType,
  ColumnOptions,
  parseNumberFormat
} from '../column-definition/column-definition.model';

export type FieldType = ColumnDefinitionType;

/** Stupac sheme pripremljen za crtanje polja: uz tip nosi i pravila unosa. */
export interface SchemaField {
  key: string;
  /** Naziv koji se prikazuje; kad stupac nema label, to je sam ključ. */
  label: string;
  type: FieldType;
  /** Šifrarnik iz kojeg polje nudi vrijednosti; null za sve ostale tipove. */
  codebookId: number | null;
  /**
   * Stavke tog šifrarnika, dohvaćene zasebno i pridružene naknadno (withItems).
   * Prazno dok se ne učitaju - polje tada nema što ponuditi, ali se i dalje crta.
   */
  items: CodebookItem[];
  required: boolean;
  unique: boolean;
  defaultValue: string | null;
  readOnly: boolean;
  width: number | null;
  options: ColumnOptions;
}

/**
 * Vrijednost jednog polja.
 *
 * Stupac s višestrukim odabirom drži NIZ umjesto jedne vrijednosti - niz šifara kod
 * šifrarnika, niz id-eva kod veze na drugi obrazac.
 */
export type FieldValue = string | number | string[] | number[] | RecordLink[] | null;

export function toSchemaField(column: ColumnDefinition): SchemaField {
  return {
    key: column.columnKey,
    label: column.label?.trim() || column.columnKey,
    type: column.columnType,
    codebookId: column.codebookId,
    items: [],
    required: column.required,
    unique: column.unique,
    defaultValue: column.defaultValue,
    readOnly: column.readOnly,
    width: column.width,
    options: column.options
  };
}

/** Isto polje, s pridruženim stavkama svog šifrarnika. */
export function withItems(field: SchemaField, itemsByCodebook: Map<number, CodebookItem[]>): SchemaField {
  if (field.codebookId === null) {
    return field;
  }
  return { ...field, items: itemsByCodebook.get(field.codebookId) ?? [] };
}

/**
 * Drži li polje više vrijednosti odjednom.
 *
 * Vrijedi za oba tipa koja biraju iz gotove ponude: šifrarnik drži niz šifara, veza niz
 * id-eva. Postavka je ista, pa je i pitanje jedno - ostatak koda ne mora znati koji od ta
 * dva tipa gleda.
 */
export function isMultiValued(field: SchemaField): boolean {
  return (field.type === 'codebook' || field.type === 'reference') && field.options.multiple;
}

/**
 * Vrijednost referentnog polja kao niz id-eva - i kad polje drži samo jednu vezu.
 *
 * Isti razlog kao {@link asCodes}: razlika između "jedna veza" i "popis veza" je u omotu, a
 * ne u tome što se bira, pa je kontrole i prikaz ne moraju razlikovati posvuda.
 *
 * Prazan tekst se preskače - tako u zapis dolazi polje koje korisnik nije dirao.
 */
export function asIds(value: FieldValue): number[] {
  const raw = Array.isArray(value) ? value : [value];
  const ids: number[] = [];
  for (const item of raw) {
    // Prazan tekst se preskače IZRIJEKOM: `Number('')` je 0, pa bi nepopunjeno polje
    // izgledalo kao veza na zapis broj 0 - i u ćeliji bi pisalo "#0" umjesto ponude za odabir.
    if (item === null || item === undefined || (typeof item === 'string' && item.trim() === '')) {
      continue;
    }
    const id = typeof item === 'string' ? Number(item) : item;
    if (typeof id === 'number' && Number.isInteger(id)) {
      ids.push(id);
    }
  }
  return ids;
}

/**
 * Vrijednost polja kao niz šifara - i kad je polje jednovrijednosno.
 *
 * Postoji da kontrole i prikaz ne moraju posvuda razlikovati "jedna šifra" od "popis
 * šifara": razlika je u omotu, ne u tome što se bira.
 */
export function asCodes(value: FieldValue): string[] {
  if (Array.isArray(value)) {
    // Niz smije biti i niz brojeva (veza na zapise), pa se članovi ispisuju: ovo se zove
    // samo nad šifrarničkim poljem, ali tip vrijednosti pokriva oba slučaja.
    return value.map((item) => String(item));
  }
  return typeof value === 'string' && value !== '' ? [value] : [];
}

/**
 * Naziv koji korisnik vidi umjesto šifre spremljene u zapisu.
 *
 * Šifra koja u šifrarniku više nema svoju stavku se prikazuje takva kakva jest: bolje
 * pokazati "ST" nego prazno, jer je u zapisu doista to i piše.
 */
export function codebookLabel(field: SchemaField, code: unknown): string | null {
  if (typeof code !== 'string') {
    return null;
  }
  return field.items.find((item) => item.code === code)?.name ?? code;
}

// prazna vrijednost polja - ne smije se ponuditi kao "pravi" default (npr. 0) jer bi se
// mogla nenamjerno spremiti i kad korisnik ništa ne upiše
export function emptyValue(type: FieldType): FieldValue {
  switch (type) {
    case 'number':
      return null;
    // Poveznice su POPIS i praznina im je prazan popis, ne prazan tekst. Prazan tekst je
    // backend odbijao ("expected list of links"), i to tek pri spremanju - polje je dotad
    // izgledalo kao da radi.
    case 'link':
      return [];
    default:
      return '';
  }
}

/**
 * Vrijednost s kojom polje kreće u praznoj formi: zadana vrijednost stupca ako je ima,
 * inače prazno. Zadana vrijednost je u shemi uvijek tekst, pa se ovdje pretvara u tip
 * u kojem se stvarno sprema. Kod šifrarničkog stupca je to već šifra, pa ostaje tekst.
 */
export function initialValue(field: SchemaField): FieldValue {
  const raw = field.defaultValue?.trim();
  if (!raw) {
    return isMultiValued(field) ? [] : emptyValue(field.type);
  }
  if (isMultiValued(field)) {
    return [raw];
  }
  if (field.type === 'number') {
    const parsed = Number(raw);
    return Number.isNaN(parsed) ? null : parsed;
  }
  return raw;
}

/** Je li polje ostalo nepopunjeno. */
export function isEmptyValue(value: FieldValue): boolean {
  if (Array.isArray(value)) {
    return value.length === 0;
  }
  return value === null || value === undefined || (typeof value === 'string' && value.trim() === '');
}

/**
 * Jesu li dvije vrijednosti "ista" vrijednost za potrebe jedinstvenosti - isto pravilo
 * koje primjenjuje i backend: tekst bez obzira na velika slova i rubne razmake, brojevi
 * po vrijednosti.
 *
 * Nizova ovdje nema: stupac s višestrukim odabirom ne može biti jedinstven (backend to
 * odbija još pri definiranju stupca).
 */
export function sameValue(a: unknown, b: unknown): boolean {
  if (a === null || a === undefined || b === null || b === undefined) {
    return false;
  }
  if (typeof a === 'string' && typeof b === 'string') {
    return a.trim().toLowerCase() === b.trim().toLowerCase();
  }
  if (typeof a === 'number' && typeof b === 'number') {
    return a === b;
  }
  return a === b;
}

/**
 * Broj ispisan prema formatu stupca.
 *
 * Formatiranje je isključivo prikaz - u zapisu ostaje broj kakav jest. Stupac bez formata
 * (i onaj čiji se uzorak ne da pročitati) ispisuje se kako ga preglednik zadano ispiše,
 * jer je to bolje od formata koji smo pogodili.
 */
export function formatNumber(field: SchemaField, value: number): string {
  const parts = parseNumberFormat(field.options.numberFormat);
  if (parts === null) {
    return String(value);
  }
  return new Intl.NumberFormat('hr-HR', {
    minimumFractionDigits: parts.decimals,
    maximumFractionDigits: parts.decimals,
    useGrouping: parts.grouping
  }).format(value);
}

/**
 * Datum s vremenom u čitljiv oblik: "2026-08-04T13:30" -> "04.08.2026. 13:30".
 *
 * Vrijednost se sprema kao ISO tekst jer se jedino takav ispravno sortira i uspoređuje;
 * ono što korisnik čita je druga stvar.
 */
export function formatDateTime(value: string): string {
  const [date, time] = value.split('T');
  const parts = date.split('-');
  if (parts.length !== 3) {
    return value;
  }
  const shown = `${parts[2]}.${parts[1]}.${parts[0]}.`;
  return time ? `${shown} ${time.slice(0, 5)}` : shown;
}

/**
 * Jedna poveznica u zapisu: adresa i neobavezan naziv.
 *
 * Naziv postoji jer je adresa u ćeliji nečitljiva („https://intranet/dms/doc?id=91827"), a
 * ono što čovjek traži je „Politika sigurnosti". Neobavezan je jer se poveznica često samo
 * zalijepi, a tjerati na naziv značilo bi izmišljene nazive.
 */
export interface RecordLink {
  url: string;
  name?: string;
}

/** Poveznice zapisa; sve što nije ispravan popis čita se kao prazno. */
export function asLinks(value: unknown): RecordLink[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter((item): item is RecordLink =>
      typeof item === 'object' && item !== null && typeof (item as RecordLink).url === 'string')
    .map((item) => (item.name ? { url: item.url, name: item.name } : { url: item.url }));
}

/** Što piše na poveznici - naziv ako ga ima, inače sama adresa. */
export function linkLabel(link: RecordLink): string {
  return link.name && link.name.trim() !== '' ? link.name : link.url;
}

/**
 * Smije li se ova adresa otvoriti.
 *
 * Ista granica koju drži i backend, i to iz istog razloga: adresu upisuje jedan korisnik, a
 * klikne je drugi. `javascript:` upisan u redak inače postaje način da se izvrši kod kod
 * kolege koji taj zapis otvori.
 */
export function isOpenableUrl(url: string): boolean {
  return /^https?:\/\//i.test(url.trim());
}

/**
 * Vrijednost jedne ćelije u obliku u kojem je čita čovjek - za tablice koje prikazuju zapise
 * DRUGOG obrasca (dijalog povezanih zapisa, dijalog odabira zapisa).
 *
 * Stoji ovdje, a ne u svakom dijalogu, jer su se već bile razišle: poveznica je popis mapa, pa
 * je dijalog koji je ispisuje običnim `String(value)` prikazivao „[object Object]".
 *
 * Šifrarnički stupac ovdje pokazuje ŠIFRU, ne naziv stavke: nazivi žive u šifrarniku drugog
 * obrasca, pa bi ih trebalo dohvatiti zasebno - a ovi dijalozi postoje da se zapis prepozna,
 * ne da se pročita do kraja.
 */
export function cellText(field: SchemaField, value: unknown): string {
  if (value === null || value === undefined || value === '' || (Array.isArray(value) && value.length === 0)) {
    return '-';
  }
  if (field.type === 'link') {
    return asLinks(value).map(linkLabel).join(', ');
  }
  if (field.type === 'date' && field.options.dateMode === 'datetime' && typeof value === 'string') {
    return formatDateTime(value);
  }
  if ((field.type === 'number' || field.type === 'formula') && typeof value === 'number') {
    return formatNumber(field, value);
  }
  return Array.isArray(value) ? value.join(', ') : String(value);
}

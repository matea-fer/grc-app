import { ColumnDefinition, EMPTY_COLUMN_OPTIONS, parseNumberFormat } from '../column-definition/column-definition.model';
import {
  SchemaField,
  asCodes,
  formatDateTime,
  formatNumber,
  initialValue,
  isEmptyValue,
  toSchemaField
} from './field';

/**
 * Pomoćne funkcije oko polja - bez komponente i bez servera.
 *
 * Ovdje se najviše isplati testirati ono što je nevidljivo: šifrarnički stupac s
 * višestrukim odabirom drži NIZ šifara umjesto jedne, pa svaka funkcija koja je dosad
 * gledala samo jednu vrijednost mora podnijeti i popis. Format broja i datuma se testira
 * jer je čisti prikaz - u zapisu ostaje ono što je bilo.
 */
describe('field - pomoćne funkcije', () => {
  function column(overrides: Partial<ColumnDefinition> = {}): ColumnDefinition {
    return {
      columnKey: 'polje',
      columnType: 'string',
      codebookId: null,
      label: null,
      required: false,
      unique: false,
      defaultValue: null,
      readOnly: false,
      width: null,
      options: { ...EMPTY_COLUMN_OPTIONS },
      ...overrides
    };
  }

  function field(overrides: Partial<ColumnDefinition> = {}): SchemaField {
    return toSchemaField(column(overrides));
  }

  describe('asCodes', () => {
    it('jednu šifru vraća kao popis od jednog člana', () => {
      expect(asCodes('ZG')).toEqual(['ZG']);
    });

    it('popis vraća takav kakav jest', () => {
      expect(asCodes(['ZG', 'ST'])).toEqual(['ZG', 'ST']);
    });

    it('prazno je prazan popis, a ne popis s praznim članom', () => {
      // popis s praznim članom bi se prikazao kao jedna prazna značka i brojao kao odabir
      expect(asCodes('')).toEqual([]);
      expect(asCodes(null)).toEqual([]);
    });
  });

  describe('isEmptyValue', () => {
    it('prazan popis je nepopunjeno polje', () => {
      expect(isEmptyValue([])).toBe(true);
      expect(isEmptyValue(['ZG'])).toBe(false);
    });

    it('sam razmak je i dalje prazno', () => {
      expect(isEmptyValue('   ')).toBe(true);
    });
  });

  describe('initialValue', () => {
    it('višestruki odabir kreće od praznog popisa, ne od praznog teksta', () => {
      const multi = field({
        columnType: 'codebook',
        codebookId: 1,
        options: { ...EMPTY_COLUMN_OPTIONS, multiple: true }
      });

      expect(initialValue(multi)).toEqual([]);
    });

    it('zadana vrijednost višestrukog stupca postaje popis od jednog člana', () => {
      // stupac inače drži popis; predpopunjen redak ne smije imati drukčiji oblik
      const multi = field({
        columnType: 'codebook',
        codebookId: 1,
        defaultValue: 'ZG',
        options: { ...EMPTY_COLUMN_OPTIONS, multiple: true }
      });

      expect(initialValue(multi)).toEqual(['ZG']);
    });
  });

  describe('parseNumberFormat', () => {
    it('čita broj decimala i tisućice iz uzorka', () => {
      expect(parseNumberFormat('#.##0,00')).toEqual({ decimals: 2, grouping: true });
      expect(parseNumberFormat('#0')).toEqual({ decimals: 0, grouping: false });
      expect(parseNumberFormat('0,000')).toEqual({ decimals: 3, grouping: false });
    });

    it('prazan ili neprepoznatljiv uzorak nema učinka', () => {
      // bolje ispisati broj kako jest nego prema formatu koji smo pogodili
      expect(parseNumberFormat(null)).toBeNull();
      expect(parseNumberFormat('  ')).toBeNull();
      expect(parseNumberFormat('kn #.##0,00')).toBeNull();
    });
  });

  describe('formatNumber', () => {
    it('primjenjuje broj decimala i tisućice', () => {
      const money = field({
        columnType: 'number',
        options: { ...EMPTY_COLUMN_OPTIONS, numberFormat: '#.##0,00' }
      });

      // hrvatski zapis: tisućice točkom, decimale zarezom; razmak zna biti "uski"
      expect(formatNumber(money, 1234.5).replace(/\s/g, ' ')).toBe('1.234,50');
    });

    it('stupac bez formata ispisuje broj kako jest', () => {
      expect(formatNumber(field({ columnType: 'number' }), 1234.5)).toBe('1234.5');
    });
  });

  describe('formatDateTime', () => {
    it('ISO zapis pretvara u čitljiv oblik', () => {
      expect(formatDateTime('2026-08-04T13:30')).toBe('04.08.2026. 13:30');
      expect(formatDateTime('2026-08-04T13:30:00')).toBe('04.08.2026. 13:30');
    });

    it('ono što nije datum ostavlja netaknuto', () => {
      expect(formatDateTime('bez datuma')).toBe('bez datuma');
    });
  });

  describe('toSchemaField', () => {
    it('prenosi širinu i postavke sa stupca', () => {
      const source = column({
        columnType: 'number',
        width: 120,
        options: { ...EMPTY_COLUMN_OPTIONS, numberMode: 'int', min: 1, max: 10 }
      });

      const result = toSchemaField(source);

      expect(result.width).toBe(120);
      expect(result.options.numberMode).toBe('int');
      expect(result.options.min).toBe(1);
    });
  });
});

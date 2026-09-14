import { TestBed } from '@angular/core/testing';

import { FieldInput } from './field-input';
import { CodebookItem } from '../codebook/codebook.model';
import { EMPTY_COLUMN_OPTIONS, PickerMode } from '../column-definition/column-definition.model';
import { FieldValue, SchemaField } from './field';

/**
 * Kontrola za unos, s naglaskom na šifrarnički stupac.
 *
 * Slobodan unos je DODATAK načinu prikaza, a ne četvrti način: i potvrdni okviri i dijalog
 * ga nude kao polje uz popis. Ono što se pritom lako izgubi je vrijednost koja u šifrarniku
 * još ne postoji - nju server tek treba napraviti, a do tada je nema u `items`, pa je svaka
 * obrada koja se oslanja na `items` može tiho ispustiti.
 */
describe('FieldInput - šifrarnički stupac', () => {
  const ITEMS: CodebookItem[] = [
    { id: 1, code: 'ZG', name: 'Zagreb', active: true, sortOrder: 0 },
    { id: 2, code: 'ST', name: 'Split', active: true, sortOrder: 1 }
  ];

  function setup(options: {
    pickerMode?: PickerMode;
    multiple?: boolean;
    allowNewValues?: boolean;
    value?: FieldValue;
  }) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});

    const field: SchemaField = {
      key: 'grad',
      label: 'Grad',
      type: 'codebook',
      codebookId: 1,
      items: ITEMS,
      required: false,
      unique: false,
      defaultValue: null,
      readOnly: false,
      width: null,
      options: {
        ...EMPTY_COLUMN_OPTIONS,
        pickerMode: options.pickerMode ?? 'dropdown',
        multiple: options.multiple ?? false,
        allowNewValues: options.allowNewValues ?? false
      }
    };

    const fixture = TestBed.createComponent(FieldInput);
    fixture.componentRef.setInput('field', field);
    fixture.componentRef.setInput('value', options.value ?? (options.multiple ? [] : ''));
    fixture.detectChanges();
    return fixture;
  }

  describe('koja se kontrola crta', () => {
    it('potvrdni okviri ostaju okviri i uz slobodan unos', () => {
      // slobodan unos ne smije pojesti odabrani način prikaza
      expect(setup({ pickerMode: 'checkbox', allowNewValues: true }).componentInstance['control']())
        .toBe('checkbox');
    });

    it('dijalog ostaje dijalog i uz slobodan unos', () => {
      expect(setup({ pickerMode: 'popup', allowNewValues: true }).componentInstance['control']())
        .toBe('popup');
    });

    /** Izbornik u koji se smije i upisati nije izbornik nego polje s ponuđenim popisom. */
    it('padajući izbornik uz slobodan unos postaje polje s popisom', () => {
      expect(setup({ pickerMode: 'dropdown', allowNewValues: true }).componentInstance['control']())
        .toBe('free');
      expect(setup({ pickerMode: 'dropdown' }).componentInstance['control']()).toBe('dropdown');
    });

    /** Stupac iz vremena prije načina prikaza: popis se ne smije pokušati nacrtati izbornikom. */
    it('stupac s više vrijednosti bez zadanog načina prikaza dobiva okvire', () => {
      const fixture = setup({ multiple: true });
      fixture.componentRef.setInput('field', {
        ...fixture.componentInstance.field(),
        options: { ...EMPTY_COLUMN_OPTIONS, pickerMode: null, multiple: true }
      });
      fixture.detectChanges();

      expect(fixture.componentInstance['control']()).toBe('checkbox');
    });
  });

  describe('nova vrijednost', () => {
    it('upisana vrijednost se dodaje uz već odabrane', () => {
      const fixture = setup({ pickerMode: 'checkbox', multiple: true, allowNewValues: true, value: ['ZG'] });
      const instance = fixture.componentInstance;

      instance['newValue'].set('Rijeka');
      instance['addNewValue']();

      // u vrijednosti stoji TEKST, ne šifra - šifru dodjeljuje server pri spremanju
      expect(instance.value()).toEqual(['ZG', 'Rijeka']);
      // polje se prazni, inače bi se ista vrijednost dodala i drugi put
      expect(instance['newValue']()).toBe('');
    });

    /**
     * Isto pravilo koje backend primjenjuje u ensureCode - ovdje stoji da korisnik odmah
     * vidi da je pogodio postojeću stavku, umjesto da nastane druga s istim značenjem.
     */
    it('upisan naziv koji već postoji odabire postojeću stavku', () => {
      const fixture = setup({ pickerMode: 'checkbox', multiple: true, allowNewValues: true });
      const instance = fixture.componentInstance;

      instance['newValue'].set('zagreb');
      instance['addNewValue']();

      expect(instance.value()).toEqual(['ZG']);
    });

    it('kod jedne vrijednosti upisano zamjenjuje prethodno', () => {
      const fixture = setup({ pickerMode: 'popup', allowNewValues: true, value: 'ZG' });
      const instance = fixture.componentInstance;

      instance['newValue'].set('Rijeka');
      instance['addNewValue']();

      expect(instance.value()).toBe('Rijeka');
    });

    it('prazan upis ne dodaje ništa', () => {
      const fixture = setup({ pickerMode: 'checkbox', multiple: true, allowNewValues: true, value: ['ZG'] });
      const instance = fixture.componentInstance;

      instance['newValue'].set('   ');
      instance['addNewValue']();

      expect(instance.value()).toEqual(['ZG']);
    });

    it('ista vrijednost upisana dvaput stoji jednom', () => {
      const fixture = setup({ pickerMode: 'checkbox', multiple: true, allowNewValues: true });
      const instance = fixture.componentInstance;

      instance['newValue'].set('Rijeka');
      instance['addNewValue']();
      instance['newValue'].set('Rijeka');
      instance['addNewValue']();

      expect(instance.value()).toEqual(['Rijeka']);
    });
  });

  describe('nova vrijednost u popisu ponuđenih', () => {
    it('prikazuje se kao ponuda, označena da je nova', () => {
      const fixture = setup({
        pickerMode: 'checkbox',
        multiple: true,
        allowNewValues: true,
        value: ['ZG', 'Rijeka']
      });

      const choices = fixture.componentInstance['choices']();

      expect(choices.map((choice) => choice.name)).toEqual(['Zagreb', 'Split', 'Rijeka']);
      expect(choices.find((choice) => choice.name === 'Rijeka')?.isNew).toBe(true);
      expect(choices.find((choice) => choice.name === 'Zagreb')?.isNew).toBe(false);
    });

    /**
     * Redoslijed prati šifrarnik, a ne redoslijed klikanja - ali vrijednost koje u šifrarniku
     * još nema ne smije pritom ispasti; ona je upravo ono što je korisnik dodao.
     */
    it('preživi odabir druge vrijednosti', () => {
      const fixture = setup({
        pickerMode: 'checkbox',
        multiple: true,
        allowNewValues: true,
        value: ['Rijeka']
      });
      const instance = fixture.componentInstance;

      instance['toggle']('ST', true);

      expect(instance.value()).toEqual(['ST', 'Rijeka']);
    });

    it('odjavljivanje mičе samo tu vrijednost', () => {
      const fixture = setup({
        pickerMode: 'checkbox',
        multiple: true,
        allowNewValues: true,
        value: ['ZG', 'Rijeka']
      });
      const instance = fixture.componentInstance;

      instance['toggle']('ZG', false);

      expect(instance.value()).toEqual(['Rijeka']);
    });
  });
});

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

import { FieldInput } from './field-input';
import { EMPTY_COLUMN_OPTIONS } from '../column-definition/column-definition.model';
import { FieldValue, RecordLink, SchemaField, emptyValue, initialValue } from './field';

/**
 * Polje s poveznicama u obrascu za unos i uređivanje.
 *
 * Regresija za kvar nađen ručnom provjerom: u tablici je gumb radio, ali je u obrascu stajalo
 * obično tekstualno polje. Uzrok je bio tih na najgori način - `control()` nije imao granu za
 * ovaj tip, pa je pao na „tekst": polje je izgledalo ispravno, dalo se u njega upisati, a
 * spremanje je tek onda javljalo da vrijednost nije popis. Zato se ovdje provjerava upravo
 * KOJA se kontrola crta, a ne samo što komponenta izračuna.
 */
describe('FieldInput - poveznice', () => {
  let fixture: ComponentFixture<FieldInput>;

  const field: SchemaField = {
    key: 'dokazi',
    label: 'Dokazi',
    type: 'link',
    codebookId: null,
    items: [],
    required: false,
    unique: false,
    defaultValue: null,
    readOnly: false,
    width: null,
    options: { ...EMPTY_COLUMN_OPTIONS, buttonLabel: 'Dokazi' }
  };

  function setup(value: FieldValue) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    fixture = TestBed.createComponent(FieldInput);
    fixture.componentRef.setInput('field', field);
    fixture.componentRef.setInput('value', value);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('crta se gumb koji otvara popis, a ne polje za upis teksta', () => {
    setup([]);

    expect(fixture.nativeElement.querySelector('.picker-button')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('input[type="text"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('dodaj poveznicu');
  });

  it('već upisane poveznice stoje kao čipovi, s nazivom', () => {
    setup([{ url: 'https://a', name: 'Politika' }, { url: 'https://b' }]);

    const chips = Array.from(fixture.nativeElement.querySelectorAll('.chip'))
      .map((chip) => (chip as HTMLElement).textContent?.trim());
    expect(chips).toEqual(['Politika', 'https://b']);
  });

  /** U obrascu se ništa ne sprema - vrijednost samo čeka spremanje zapisa, kao i svako drugo polje. */
  it('promjena u dijalogu upisuje popis u vrijednost polja', () => {
    const component = setup([]);
    const links: RecordLink[] = [{ url: 'https://a', name: 'Politika' }];

    component['setLinks'](links);

    expect(component.value()).toEqual(links);
  });

  /**
   * Prazan tekst je backend odbijao ("expected list of links"), i to tek pri spremanju - dotad
   * je polje izgledalo kao da radi.
   */
  it('prazna vrijednost je prazan POPIS, ne prazan tekst', () => {
    expect(emptyValue('link')).toEqual([]);
    expect(initialValue(field)).toEqual([]);
  });
});

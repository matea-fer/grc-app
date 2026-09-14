import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { FieldInput } from './field-input';
import { ReferenceLabelService } from './reference-label.service';
import { EMPTY_COLUMN_OPTIONS } from '../column-definition/column-definition.model';
import { FieldValue, SchemaField } from './field';

/**
 * Polje koje pokazuje na zapis drugog obrasca.
 *
 * Dvije stvari se lako pokvare i obje su tihe: da se u zapis sprema BROJ (a ne niz od jednog
 * člana, kakav backend ne očekuje i po kakvom se ne filtrira), i da se prikazuje NAZIV, a ne
 * broj - jer broj korisniku ne znači ništa, a ćelija ne izgleda pokvareno, samo beskorisno.
 */
describe('FieldInput - veza na obrazac', () => {
  let http: HttpTestingController;

  function setup(options: { multiple?: boolean; value?: FieldValue } = {}) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    const field: SchemaField = {
      key: 'proces',
      label: 'Proces',
      type: 'reference',
      codebookId: null,
      items: [],
      required: false,
      unique: false,
      defaultValue: null,
      readOnly: false,
      width: null,
      options: {
        ...EMPTY_COLUMN_OPTIONS,
        targetTemplateId: 20,
        displayColumnKey: 'naziv',
        onTargetDelete: 'restrict',
        multiple: options.multiple ?? false
      }
    };

    const fixture = TestBed.createComponent(FieldInput);
    fixture.componentRef.setInput('field', field);
    fixture.componentRef.setInput('value', options.value ?? (options.multiple ? [] : ''));
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => http.verify());

  it('veza se crta kao dijalog, nikad kao padajući izbornik', () => {
    expect(setup().componentInstance['control']()).toBe('reference');
  });

  it('nepopunjeno polje nudi odabir, a ne prazan gumb', () => {
    const fixture = setup();
    expect(fixture.nativeElement.textContent).toContain('odaberi zapis');
  });

  /** Dok naziv ne stigne, stoji broj: to je i dalje istina o tome što u zapisu piše. */
  it('do dolaska naziva prikazuje broj zapisa', () => {
    const fixture = setup({ value: 12 });
    expect(fixture.nativeElement.textContent).toContain('#12');
  });

  it('naziv zamjenjuje broj čim ga servis zna', () => {
    const fixture = setup({ value: 12 });
    TestBed.inject(ReferenceLabelService).remember(20, 'naziv', 12, 'Nabava');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Nabava');
    expect(fixture.nativeElement.textContent).not.toContain('#12');
  });

  /**
   * Jedna veza se sprema kao BROJ, a ne kao niz od jednog člana: takav je oblik očekuje
   * provjera sheme, i po takvom radi filtar `stupac:12`.
   */
  it('jedna veza se sprema kao broj', () => {
    const fixture = setup();
    fixture.componentInstance['setReference']([15]);

    expect(fixture.componentInstance.value()).toBe(15);
  });

  it('više veza se sprema kao popis brojeva', () => {
    const fixture = setup({ multiple: true });
    fixture.componentInstance['setReference']([12, 15]);

    expect(fixture.componentInstance.value()).toEqual([12, 15]);
  });

  /** Prazan odabir je prazan tekst - tako ga provjera sheme prepozna kao „nepopunjeno". */
  it('poništen odabir ostavlja polje praznim', () => {
    const fixture = setup({ value: 12 });
    fixture.componentInstance['setReference']([]);

    expect(fixture.componentInstance.value()).toBe('');
  });
});

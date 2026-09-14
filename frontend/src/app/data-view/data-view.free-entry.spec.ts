import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { DataView } from './data-view';
import { EMPTY_COLUMN_OPTIONS } from '../column-definition/column-definition.model';
/** Odgovor servera na dohvat zapisa - jedna stranica, kakvu ekran sada očekuje. */
function page(content: object[], totalElements = content.length) {
  return { content, page: 0, size: 50, totalElements, totalPages: Math.ceil(totalElements / 50) };
}

/** Zahtjev za zapisima razlikuje se od unosa po metodi - URL im je isti. */
const SURVEYS_GET = (url: string) => (r: { url: string; method: string }) => r.url === url && r.method === 'GET';
const SURVEYS_POST = (url: string) => (r: { url: string; method: string }) => r.url === url && r.method === 'POST';


/**
 * Stupac sa slobodnim unosom stvara novu stavku šifrarnika NA SERVERU, pri spremanju zapisa.
 *
 * Time popis stavki učitan pri otvaranju obrasca u tom trenutku zastari: spremljen zapis nosi
 * šifru koju taj popis ne poznaje, pa prikaz pada na samu šifru („OSIJEK") umjesto na naziv
 * („Osijek"). Korisniku to izgleda kao da je aplikacija zaboravila što je upisao - a naziv se
 * pojavi tek nakon osvježavanja stranice.
 */
describe('DataView - šifrarnik koji raste unosom', () => {
  let component: DataView;
  let http: HttpTestingController;

  const CODEBOOK_ID = 4;

  const schema = [
    {
      columnKey: 'grad',
      columnType: 'codebook',
      codebookId: CODEBOOK_ID,
      label: 'Grad',
      required: false,
      unique: false,
      defaultValue: null,
      readOnly: false,
      width: null,
      options: { ...EMPTY_COLUMN_OPTIONS, pickerMode: 'dropdown', allowNewValues: true }
    }
  ];

  const items = [{ id: 1, code: 'ZG', name: 'Zagreb', active: true, sortOrder: 0 }];

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('activeCompanyId', '2');
    localStorage.setItem('activeTemplateId', '21');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    const fixture = TestBed.createComponent(DataView);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    http.expectOne('/api/templates').flush([{ id: 21, name: 'Obrazac' }]);
    fixture.detectChanges();
    http.expectOne('/api/templates/21/columns').flush(schema);
    http.expectOne(SURVEYS_GET('/api/templates/21/surveys')).flush(page([]));
    fixture.detectChanges();
    http.expectOne(`/api/codebooks/${CODEBOOK_ID}/items`).flush(items);
    fixture.detectChanges();
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  /** Ono što se stvarno vidi u ćeliji tablice. */
  function shownValue(): string {
    const field = component['fields']()[0];
    return component['displayValue'](field, component['surveys']()[0].data['grad']);
  }

  it('nakon spremanja nove vrijednosti u tablici stoji NAZIV, ne šifra', () => {
    component['openAddDialog']();
    // korisnik upisuje naziv; šifru dodjeljuje server
    component['updateAddValue']('grad', 'Osijek');

    component['submitAdd']();

    http.expectOne(SURVEYS_POST('/api/templates/21/surveys'))
      .flush({ id: 1, companyId: 2, templateId: 21, data: { grad: 'OSIJEK' } });
    // unos kroz dijalog osvježava stranicu: gdje redak pripada odlučuje poredak na serveru
    http.expectOne(SURVEYS_GET('/api/templates/21/surveys'))
      .flush(page([{ id: 1, companyId: 2, templateId: 21, data: { grad: 'OSIJEK' } }]));

    // zapis je stigao sa šifrom koju učitani popis ne poznaje -> popis se mora osvježiti
    http.expectOne(`/api/codebooks/${CODEBOOK_ID}/items`).flush([
      ...items,
      { id: 2, code: 'OSIJEK', name: 'Osijek', active: true, sortOrder: 1 }
    ]);

    expect(shownValue()).toBe('Osijek');
  });

  /**
   * Osvježavanje se ne smije dogadati pri svakom spremanju - samo kad je u zapisu doista
   * zavrsila sifra koju popis ne poznaje.
   */
  it('odabir postojeće vrijednosti ne izaziva ponovni dohvat šifrarnika', () => {
    component['openAddDialog']();
    component['updateAddValue']('grad', 'ZG');

    component['submitAdd']();

    http.expectOne(SURVEYS_POST('/api/templates/21/surveys'))
      .flush({ id: 1, companyId: 2, templateId: 21, data: { grad: 'ZG' } });
    http.expectOne(SURVEYS_GET('/api/templates/21/surveys'))
      .flush(page([{ id: 1, companyId: 2, templateId: 21, data: { grad: 'ZG' } }]));

    http.expectNone(`/api/codebooks/${CODEBOOK_ID}/items`);
    expect(shownValue()).toBe('Zagreb');
  });

  it('isto vrijedi i pri uređivanju postojećeg zapisa', () => {
    component['openAddDialog']();
    component['updateAddValue']('grad', 'ZG');
    component['submitAdd']();
    http.expectOne(SURVEYS_POST('/api/templates/21/surveys'))
      .flush({ id: 1, companyId: 2, templateId: 21, data: { grad: 'ZG' } });
    http.expectOne(SURVEYS_GET('/api/templates/21/surveys'))
      .flush(page([{ id: 1, companyId: 2, templateId: 21, data: { grad: 'ZG' } }]));

    component['startEdit'](component['surveys']()[0]);
    component['updateEditValue']('grad', 'Rijeka');
    component['submitEdit'](component['surveys']()[0]);

    http.expectOne('/api/templates/21/surveys/1')
      .flush({ id: 1, companyId: 2, templateId: 21, data: { grad: 'RIJEKA' } });
    http.expectOne(`/api/codebooks/${CODEBOOK_ID}/items`).flush([
      ...items,
      { id: 3, code: 'RIJEKA', name: 'Rijeka', active: true, sortOrder: 1 }
    ]);

    expect(shownValue()).toBe('Rijeka');
  });
});

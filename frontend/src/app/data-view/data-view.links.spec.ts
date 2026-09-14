import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { DataView } from './data-view';
import { EMPTY_COLUMN_OPTIONS } from '../column-definition/column-definition.model';

function page(content: object[]) {
  return { content, page: 0, size: 50, totalElements: content.length, totalPages: 1 };
}

const SURVEYS_GET = (r: { url: string; method: string }) =>
  r.url === '/api/templates/21/surveys' && r.method === 'GET';

/**
 * Stupac „Poveznice" u tablici zapisa.
 *
 * Poveznica je vrijednost ZAPISA, pa se sprema kao i svaka druga - ali dijalog se otvara iz
 * retka koji se ne uređuje, pa nema gdje potvrditi. Zato se sprema odmah, cijelim zapisom, i
 * upravo to se ovdje brani.
 */
describe('DataView - poveznice zapisa', () => {
  let component: DataView;
  let http: HttpTestingController;

  const schema = [
    {
      columnKey: 'naziv', columnType: 'string', codebookId: null, label: 'Naziv',
      required: false, unique: false, defaultValue: null, readOnly: false, width: null,
      options: { ...EMPTY_COLUMN_OPTIONS }
    },
    {
      columnKey: 'dokazi', columnType: 'link', codebookId: null, label: 'Dokazi',
      required: false, unique: false, defaultValue: null, readOnly: false, width: null,
      options: { ...EMPTY_COLUMN_OPTIONS, buttonLabel: 'Dokazi' }
    }
  ];

  function open(row: object): void {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    const fixture = TestBed.createComponent(DataView);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    http.expectOne('/api/templates').flush([{ id: 21, name: 'Kontrole' }]);
    fixture.detectChanges();
    http.expectOne('/api/templates/21/columns').flush(schema);
    http.expectOne(SURVEYS_GET).flush(page([row]));
    fixture.detectChanges();
  }

  const withLink = {
    id: 1, companyId: 2, templateId: 21,
    data: { naziv: 'Kontrola pristupa', dokazi: [{ url: 'https://a', name: 'Politika' }] },
    locked: false
  };

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('activeCompanyId', '2');
    localStorage.setItem('activeTemplateId', '21');
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('broj poveznica se vidi bez otvaranja dijaloga', () => {
    open(withLink);

    expect(component['linkCount'](component['fields']()[1], component['surveys']()[0])).toBe(1);
  });

  it('dijalog pokazuje poveznice baš tog retka', () => {
    open(withLink);

    component['openLinks'](component['fields']()[1], component['surveys']()[0]);

    expect(component['shownLinks']()).toEqual([{ url: 'https://a', name: 'Politika' }]);
  });

  /** Dijalog nema gumb „spremi" - promjena mora otići na server sama. */
  it('dodana poveznica se sprema odmah, cijelim zapisom', () => {
    open(withLink);
    component['openLinks'](component['fields']()[1], component['surveys']()[0]);

    component['saveLinks']([{ url: 'https://a', name: 'Politika' }, { url: 'https://b' }]);

    const request = http.expectOne((r) => r.url === '/api/templates/21/surveys/1' && r.method === 'PUT');
    // ostale vrijednosti retka moraju ostati - šalje se cijeli zapis, ne samo ovaj stupac
    expect(request.request.body.data.naziv).toBe('Kontrola pristupa');
    expect(request.request.body.data.dokazi).toEqual([
      { url: 'https://a', name: 'Politika' },
      { url: 'https://b' }
    ]);

    request.flush({ ...withLink, data: { ...withLink.data, dokazi: [{ url: 'https://a' }, { url: 'https://b' }] } });
    expect(component['shownLinks']()).toHaveLength(2);
  });

  /** Redak se osvježava odgovorom servera, a ne onim što smo poslali. */
  it('uklonjena poveznica nestaje iz retka nakon odgovora', () => {
    open(withLink);
    component['openLinks'](component['fields']()[1], component['surveys']()[0]);

    component['saveLinks']([]);

    http.expectOne((r) => r.url === '/api/templates/21/surveys/1' && r.method === 'PUT')
      .flush({ ...withLink, data: { naziv: 'Kontrola pristupa', dokazi: [] } });

    expect(component['shownLinks']()).toEqual([]);
    expect(component['linkCount'](component['fields']()[1], component['surveys']()[0])).toBe(0);
  });

  it('zaključan zapis daje dijalog koji samo čita', () => {
    open({ ...withLink, locked: true, lockedAt: '2026-08-01T09:00:00Z', lockedBy: 'ana' });

    expect(component['isLocked'](1)).toBe(true);
  });

  /** Popis se ne da poredati - poredak popisa nije poredak ničega što korisnik vidi. */
  it('po stupcu s poveznicama se ne sortira', () => {
    open(withLink);

    expect(component['sortableFields']().some((f) => f.key === 'dokazi')).toBe(false);
  });

  /** Vrijednost crta gumb, pa u ćeliji ne smije stajati ni „-" ni sirovi JSON. */
  it('ćelija ne ispisuje vrijednost kao tekst', () => {
    open(withLink);

    expect(component['displayValue'](component['fields']()[1], withLink.data.dokazi)).toBe('');
  });
});

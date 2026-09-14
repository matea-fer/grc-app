import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { DataView } from './data-view';
/** Odgovor servera na dohvat zapisa - jedna stranica, kakvu ekran sada očekuje. */
function page(content: object[], totalElements = content.length) {
  return { content, page: 0, size: 50, totalElements, totalPages: Math.ceil(totalElements / 50) };
}

/** Zahtjev za zapisima razlikuje se od unosa po metodi - URL im je isti. */
const SURVEYS_GET = (url: string) => (r: { url: string; method: string }) => r.url === url && r.method === 'GET';
const SURVEYS_POST = (url: string) => (r: { url: string; method: string }) => r.url === url && r.method === 'POST';


/**
 * Redak koji se upisuje izravno u tablici pretijesan je za poruku o grešci, pa ona
 * stoji iznad tablice - ali joj vijek mora trajati točno koliko i tom retku.
 *
 * Poruka koja preživi odustajanje govori o nečemu čega više nema, a korisnik nema
 * načina da je makne osim da pogodi da je bezopasna.
 */
describe('DataView - redak koji se upisuje u tablici', () => {
  let component: DataView;
  let http: HttpTestingController;

  const schema = [
    { columnKey: 'oib', columnType: 'string', codebookId: null, label: 'OIB', required: true, unique: false, defaultValue: null, readOnly: false }
  ];

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('activeCompanyId', '2');
    localStorage.setItem('activeTemplateId', '21');

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
    http.expectOne(SURVEYS_GET('/api/templates/21/surveys'))
      .flush(page([{ id: 1, companyId: 2, templateId: 21, data: { oib: '111' } }]));
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  /** Redak se otvara ispod postojećeg, pa mu treba postojeći redak kao sidro. */
  function openInlineRowUnderFirst(): void {
    component['startInlineAdd'](component['surveys']()[0]);
  }

  it('prazno obavezno polje javlja grešku i ne šalje zahtjev', () => {
    openInlineRowUnderFirst();

    component['submitInlineAdd']();

    expect(component['inlineError']()).toBe('Polje "OIB" je obavezno.');
    http.expectNone(() => true);
  });

  it('odustajanje od retka miče i njegovu poruku', () => {
    openInlineRowUnderFirst();
    component['submitInlineAdd']();
    expect(component['inlineError']()).not.toBeNull();

    component['cancelInlineAdd']();

    expect(component['inlineError']()).toBeNull();
    expect(component['inlineAfterId']()).toBeNull();
  });

  it('ponovno otvaranje retka kreće bez stare poruke', () => {
    openInlineRowUnderFirst();
    component['submitInlineAdd']();

    openInlineRowUnderFirst();

    expect(component['inlineError']()).toBeNull();
  });

  /** Redak nestane i kad ga filtar makne - poruka mora s njim. */
  it('promjena filtra miče redak i njegovu poruku', () => {
    openInlineRowUnderFirst();
    component['submitInlineAdd']();

    component['addFilter']();
    component['setFilterColumn'](0, 'oib');

    // filtar se od 12.08. primjenjuje na serveru, pa promjena povlači novi dohvat
    http.expectOne(SURVEYS_GET('/api/templates/21/surveys')).flush(page([]));

    expect(component['inlineError']()).toBeNull();
    expect(component['inlineAfterId']()).toBeNull();
  });

  it('uspješan unos zatvara redak i ne ostavlja poruku', () => {
    openInlineRowUnderFirst();
    component['updateInlineValue']('oib', '222');

    component['submitInlineAdd']();

    http.expectOne(SURVEYS_POST('/api/templates/21/surveys'))
      .flush({ id: 2, companyId: 2, templateId: 21, data: { oib: '222' } });

    expect(component['inlineError']()).toBeNull();
    expect(component['inlineAfterId']()).toBeNull();
    // novi zapis sjeda odmah iza onog pored kojeg je unesen
    expect(component['surveys']().map((s) => s.id)).toEqual([1, 2]);
  });

  /** Greška retka ne smije se miješati s greškom ekrana (učitavanje, brisanje). */
  it('poruka retka ne dira grešku ekrana', () => {
    openInlineRowUnderFirst();
    component['submitInlineAdd']();

    expect(component['error']()).toBeNull();
  });
});

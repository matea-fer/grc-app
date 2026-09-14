import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { vi } from 'vitest';

import { DataView } from './data-view';
import { EMPTY_COLUMN_OPTIONS } from '../column-definition/column-definition.model';

/**
 * Odlazak na povezani zapis - „vidim #12 u dijalogu, hoću ga u tablici".
 *
 * Tablica se suzi na taj jedan redak (`?ids=`), pa zapis dobiva sve redne radnje bez novog
 * ekrana. Zamka je u efektu koji prati odabrani obrazac: on pri svakoj promjeni čisti filtre,
 * poredak i stranicu, pa suženje postavljeno PRIJE promjene obrasca nikad ne bi doživjelo
 * dohvat. Zato ga postavlja sam efekt, iz običnog polja koje pritom potroši.
 *
 * `detectChanges()` je zato bitan dio ovih testova - bez njega se efekti uopće ne pokreću,
 * pa se ta zamka ne bi ni vidjela.
 */
describe('DataView - odlazak na povezani zapis', () => {
  let component: DataView;
  let http: HttpTestingController;
  let fixture: ComponentFixture<DataView>;

  const SURVEYS = '/api/templates/21/surveys';
  const OTHER_SURVEYS = '/api/templates/30/surveys';

  const schema = [
    {
      columnKey: 'grad', columnType: 'string', codebookId: null, label: 'Grad',
      required: false, unique: false, defaultValue: null, readOnly: false, width: null,
      options: { ...EMPTY_COLUMN_OPTIONS }
    }
  ];

  function page(content: object[], totalElements = content.length) {
    return { content, page: 0, size: 50, totalElements, totalPages: Math.ceil(totalElements / 50) };
  }

  /** Zahtjev za zapisima zadanog obrasca; vraća parametre s kojima je poslan. */
  function takeSurveysRequest(url = SURVEYS): URLSearchParams {
    const request = http.expectOne((r) => r.url === url && r.method === 'GET');
    const params = new URLSearchParams(request.request.params.toString());
    request.flush(page([{ id: 12, companyId: 2, templateId: 21, data: { grad: 'Zagreb' } }], 1));
    return params;
  }

  /** Shema i prilozi koje ekran zatraži kad se obrazac promijeni. */
  function answerTemplateLoad(templateId: number): void {
    http.expectOne(`/api/templates/${templateId}/columns`).flush(schema);
  }

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('activeCompanyId', '2');
    localStorage.setItem('activeTemplateId', '21');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    fixture = TestBed.createComponent(DataView);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    http.expectOne('/api/templates').flush([
      { id: 21, name: 'Procesi' },
      { id: 30, name: 'Rizici' }
    ]);
    fixture.detectChanges();
    answerTemplateLoad(21);
    takeSurveysRequest();
    fixture.detectChanges();
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('bez suženja se ne šalje nijedan id - to je običan pogled na obrazac', () => {
    component['reloadSurveys']();

    expect(takeSurveysRequest().has('ids')).toBe(false);
    expect(component['focusIds']()).toEqual([]);
  });

  it('zapis istog obrasca se traži odmah, suženjem na njegov id', () => {
    component['openRelatedRecord']({ templateId: 21, recordId: 12 });

    const params = takeSurveysRequest();
    expect(params.get('ids')).toBe('12');
    // suženje kreće od prve stranice - traženi redak je jedini koji ostaje
    expect(params.get('page')).toBe('0');
    expect(component['focusIds']()).toEqual([12]);
  });

  /**
   * Regresija koju sam uočila čitajući kod, prije nego što je itko išta napisao: efekt na
   * promjenu obrasca čisti filtre, poredak i stranicu. Suženje postavljeno prije `setActive`
   * bi počistio prije nego što ga je dohvat vidio, pa bi klik na povezani zapis otvorio drugi
   * obrazac - i pokazao SVE njegove zapise, bez ijedne poruke da nešto nije u redu.
   */
  it('zapis drugog obrasca preživi čišćenje koje ide uz promjenu obrasca', () => {
    component['filters'].set([{ column: 'grad', value: 'zagreb' }]);

    component['openRelatedRecord']({ templateId: 30, recordId: 77 });
    fixture.detectChanges();

    answerTemplateLoad(30);
    const params = takeSurveysRequest(OTHER_SURVEYS);
    expect(params.get('ids')).toBe('77');
    // filtri pripadaju stupcima prethodnog obrasca i moraju otpasti - suženje ne
    expect(params.has('filter')).toBe(false);
    expect(component['activeTemplateId']()).toBe(30);
    expect(component['focusIds']()).toEqual([77]);
  });

  it('obična promjena obrasca briše suženje', () => {
    component['openRelatedRecord']({ templateId: 30, recordId: 77 });
    fixture.detectChanges();
    answerTemplateLoad(30);
    takeSurveysRequest(OTHER_SURVEYS);

    component['onTemplateChange'](21);
    fixture.detectChanges();

    answerTemplateLoad(21);
    expect(takeSurveysRequest().has('ids')).toBe(false);
    expect(component['focusIds']()).toEqual([]);
  });

  it('„Prikaži sve zapise" vraća cijeli obrazac', () => {
    component['openRelatedRecord']({ templateId: 21, recordId: 12 });
    takeSurveysRequest();

    component['clearFocus']();

    expect(takeSurveysRequest().has('ids')).toBe(false);
    expect(component['focusIds']()).toEqual([]);
  });

  /**
   * Put iz dijaloga najčešće i završava brisanjem („nađi mi taj zapis da ga maknem"). Ostane li
   * suženje na zapisu kojeg više nema, tablica javlja „nema zapisa za zadani upit" ispod trake
   * koja tvrdi da prikazuje jedan.
   */
  it('brisanje zapisa na koji je prikaz sužen vraća cijeli obrazac', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    component['openRelatedRecord']({ templateId: 21, recordId: 12 });
    takeSurveysRequest();

    component['deleteSurvey'](component['surveys']()[0]);
    http.expectOne((r) => r.url === `${SURVEYS}/12` && r.method === 'DELETE').flush(null);

    expect(takeSurveysRequest().has('ids')).toBe(false);
    expect(component['focusIds']()).toEqual([]);
  });

  /** Traka mora reći i što se vidi i kako se izlazi - inače tablica izgleda kao da je prazna. */
  it('traka imenuje zapis na koji je prikaz sužen', () => {
    component['openRelatedRecord']({ templateId: 21, recordId: 12 });
    takeSurveysRequest();
    fixture.detectChanges();

    expect(component['focusNote']()).toBe('Prikazan je samo zapis #12.');
    expect(fixture.nativeElement.textContent).toContain('Prikaži sve zapise');
  });
});

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { vi } from 'vitest';

import { DataView } from './data-view';
import { EMPTY_COLUMN_OPTIONS } from '../column-definition/column-definition.model';

/**
 * Pretraga, sortiranje i stranice - sve troje radi server, pa je ovdje pitanje što mu se
 * šalje i kada.
 *
 * Zamke koje se brane: da se pri svakom pritisnutom slovu ne šalje zahtjev; da promjena
 * filtra vrati na prvu stranicu (inače korisnik na stranici 7 nakon suženja gleda prazno i
 * misli da pretraga ne radi); i da se ne nudi sortiranje po stupcu koji nema po čemu biti
 * poredan - backend takav zahtjev odbija.
 */
describe('DataView - pretraga, poredak i stranice', () => {
  let component: DataView;
  let http: HttpTestingController;
  /**
   * Drži se zbog `detectChanges()`: bez njega se Angularovi efekti ne pokreću, pa test ne
   * vidi ono što aplikacija radi između dva klika. Točno to je propustilo grešku u kojoj je
   * odlazak na stranicu 2 poništavao odabir stranice.
   */
  let fixture: ComponentFixture<DataView>;

  const SURVEYS = '/api/templates/21/surveys';

  const schema = [
    {
      columnKey: 'grad', columnType: 'string', codebookId: null, label: 'Grad',
      required: false, unique: false, defaultValue: null, readOnly: false, width: null,
      options: { ...EMPTY_COLUMN_OPTIONS }
    },
    {
      columnKey: 'dokument', columnType: 'file', codebookId: null, label: 'Dokument',
      required: false, unique: false, defaultValue: null, readOnly: false, width: null,
      options: { ...EMPTY_COLUMN_OPTIONS, buttonLabel: 'Učitaj' }
    }
  ];

  function page(content: object[], totalElements = content.length, pageNumber = 0) {
    return {
      content, page: pageNumber, size: 50, totalElements,
      totalPages: Math.ceil(totalElements / 50)
    };
  }

  /**
   * Zahtjev za zapisima; vraća parametre s kojima je poslan.
   *
   * Odgovor zadržava ukupan broj (120) jer o njemu ovisi ispis raspona - stranica koja bi
   * javila 0 zapisa poništila bi ono što se u testu upravo provjerava.
   */
  function takeSurveysRequest(totalElements = 120): URLSearchParams {
    const request = http.expectOne((r) => r.url === SURVEYS && r.method === 'GET');
    const params = new URLSearchParams(request.request.params.toString());
    request.flush(page([], totalElements));
    return params;
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

    http.expectOne('/api/templates').flush([{ id: 21, name: 'Obrazac' }]);
    fixture.detectChanges();
    http.expectOne('/api/templates/21/columns').flush(schema);
    http.expectOne('/api/templates/21/attachments').flush([]);
    http.expectOne((r) => r.url === SURVEYS && r.method === 'GET')
      .flush(page([{ id: 1, companyId: 2, templateId: 21, data: { grad: 'Zagreb' } }], 120));
    fixture.detectChanges();
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  // ===================== STRANICE =====================

  it('ukupan broj dolazi sa servera, pa se vidi koliko zapisa uopće ima', () => {
    expect(component['totalElements']()).toBe(120);
    expect(component['totalPages']()).toBe(3);
    expect(component['rangeFrom']()).toBe(1);
    expect(component['rangeTo']()).toBe(50);
  });

  it('odlazak na stranicu šalje njezin broj i mijenja prikazani raspon', () => {
    component['goToPage'](1);

    expect(takeSurveysRequest().get('page')).toBe('1');
    expect(component['rangeFrom']()).toBe(51);
    expect(component['rangeTo']()).toBe(100);
  });

  /**
   * Regresija (12.08.2026.): efekt koji učitava obrazac čitao je i signale stranice, filtra
   * i poretka, pa ga je promjena stranice budila - a on je odabir odmah poništavao na „prva
   * stranica". Tablica je prikazivala retke stranice 2, a traka je ostajala na 1.
   *
   * `detectChanges()` je ovdje bitan dio testa: bez njega se efekti ne pokreću i greška se
   * ne vidi, iako u pregledniku postoji.
   */
  it('odabrana stranica ostaje odabrana - efekt je ne poništava', () => {
    component['goToPage'](1);
    takeSurveysRequest();

    fixture.detectChanges();

    expect(component['page']()).toBe(1);
    // nema drugog dohvata: ni zapisa, ni sheme, ni priloga
    http.expectNone((r) => r.url === SURVEYS);
    http.expectNone('/api/templates/21/columns');
    http.expectNone('/api/templates/21/attachments');
  });

  it('filtar i poredak preživljavaju osvježavanje prikaza', () => {
    component['toggleSort'](component['fields']()[0]);
    takeSurveysRequest();
    component['filters'].set([{ column: 'grad', value: 'zagreb' }]);

    fixture.detectChanges();

    expect(component['sortBy']()).toBe('grad');
    expect(component['filters']()).toHaveLength(1);
    http.expectNone((r) => r.url === SURVEYS);
  });

  it('stranica izvan raspona se ignorira - nema praznog ekrana ni zahtjeva', () => {
    component['goToPage'](7);
    component['goToPage'](-1);

    http.expectNone((r) => r.url === SURVEYS);
    expect(component['page']()).toBe(0);
  });

  /** Popis od 400 stranica ne stane na zaslon; „…” je namjeran, a ne greška. */
  it('traka stranica se skraćuje razmakom kad ih je previše', () => {
    component['totalPages'].set(40);
    component['page'].set(20);

    expect(component['pageNumbers']()).toEqual([0, null, 19, 20, 21, null, 39]);
  });

  // ===================== PRETRAGA =====================

  it('upisivanje šalje jedan zahtjev tek kad tipkanje stane', () => {
    vi.useFakeTimers();
    component['addFilter']();
    // dodavanje filtra samo otvara redak - prazan uvjet se ne šalje
    http.expectNone((r) => r.url === SURVEYS);

    component['setFilterValue'](0, 'z');
    component['setFilterValue'](0, 'za');
    component['setFilterValue'](0, 'zag');
    vi.advanceTimersByTime(299);
    http.expectNone((r) => r.url === SURVEYS);

    vi.advanceTimersByTime(1);

    expect(takeSurveysRequest().getAll('filter')).toEqual(['grad:zag']);
  });

  it('promjena filtra vraća na prvu stranicu', () => {
    component['goToPage'](2);
    takeSurveysRequest();
    expect(component['page']()).toBe(2);

    component['addFilter']();
    component['setFilterColumn'](0, 'grad');

    expect(takeSurveysRequest().get('page')).toBe('0');
    expect(component['page']()).toBe(0);
  });

  it('više filtara putuje kao više uvjeta - server ih spaja s I', () => {
    component['addFilter']();
    component['setFilterColumn'](0, 'grad');
    takeSurveysRequest();

    component['filters'].set([
      { column: 'grad', value: 'zagreb' },
      { column: 'dokument', value: 'ugovor' }
    ]);
    component['reloadSurveys']();

    expect(takeSurveysRequest().getAll('filter')).toEqual(['grad:zagreb', 'dokument:ugovor']);
  });

  it('prazan uvjet se ne šalje - to je redak koji korisnik još nije ispunio', () => {
    component['filters'].set([{ column: 'grad', value: '   ' }]);
    component['reloadSurveys']();

    expect(takeSurveysRequest().has('filter')).toBe(false);
  });

  it('uklanjanje filtra odmah osvježava popis', () => {
    component['filters'].set([{ column: 'grad', value: 'zagreb' }]);
    component['removeFilter'](0);

    expect(takeSurveysRequest().has('filter')).toBe(false);
  });

  // ===================== SORTIRANJE =====================

  it('klik na zaglavlje sortira uzlazno, pa silazno, pa vraća poredak unosa', () => {
    const grad = component['fields']()[0];

    component['toggleSort'](grad);
    let params = takeSurveysRequest();
    expect(params.get('sortBy')).toBe('grad');
    expect(params.get('sortDir')).toBe('asc');

    component['toggleSort'](grad);
    expect(takeSurveysRequest().get('sortDir')).toBe('desc');

    component['toggleSort'](grad);
    expect(takeSurveysRequest().has('sortBy')).toBe(false);
  });

  /**
   * Stupac tipa „datoteka” u zapisu ne drži ništa (prilozi su u vlastitoj tablici), pa nema
   * po čemu biti poredan. Backend takav zahtjev odbija - sučelje ga ne smije ni poslati.
   */
  it('stupac bez vrijednosti se ne da sortirati', () => {
    component['toggleSort'](component['fields']()[1]);

    http.expectNone((r) => r.url === SURVEYS);
    expect(component['sortBy']()).toBeNull();
  });

  it('promjena poretka vraća na prvu stranicu', () => {
    component['goToPage'](1);
    takeSurveysRequest();

    component['toggleSort'](component['fields']()[0]);

    expect(takeSurveysRequest().get('page')).toBe('0');
  });

  /**
   * Blijedo „↕” stoji i prije ijednog klika - inače se mora pogađati da je zaglavlje uopće
   * klikabilno. (Pogađalo se: sortiranje je isprva izgledalo kao da ne postoji.)
   */
  it('sortabilni stupac ima oznaku i prije klika, nesortabilni nema nikakvu', () => {
    const [grad, dokument] = component['fields']();

    expect(component['sortMark'](grad)).toBe('↕');
    expect(component['sortMark'](dokument)).toBe('');
    expect(component['isSortable'](grad)).toBe(true);
    expect(component['isSortable'](dokument)).toBe(false);
  });

  it('oznaka smjera prati odabrani stupac i smjer', () => {
    const [grad, dokument] = component['fields']();

    component['toggleSort'](grad);
    takeSurveysRequest();
    expect(component['sortMark'](grad)).toBe('▲');
    expect(component['sortMark'](dokument)).toBe('');

    component['toggleSort'](grad);
    takeSurveysRequest();
    expect(component['sortMark'](grad)).toBe('▼');

    // treći klik vraća poredak unosa, pa i oznaka opet postaje neutralna
    component['toggleSort'](grad);
    takeSurveysRequest();
    expect(component['sortMark'](grad)).toBe('↕');
  });

  /** Opis govori što će klik napraviti; kod nesortabilnog stupca objašnjava zašto neće ništa. */
  it('opis pri prelasku mišem prati stanje stupca', () => {
    const [grad, dokument] = component['fields']();

    expect(component['sortTitle'](grad)).toBe('Sortiraj po: Grad');
    expect(component['sortTitle'](dokument)).toContain('nema vrijednost');

    component['toggleSort'](grad);
    takeSurveysRequest();
    expect(component['sortTitle'](grad)).toContain('klikni za silazno');

    component['toggleSort'](grad);
    takeSurveysRequest();
    expect(component['sortTitle'](grad)).toContain('klikni za poredak unosa');
  });
});

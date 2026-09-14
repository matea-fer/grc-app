import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AuditLog } from './audit-log';

/**
 * Dnevnik od 12.08.2026. lista po stranicama i bira datum OD kojeg se gleda.
 *
 * Prije toga je backend vraćao zadnjih 200 zapisa i filtriralo se u pregledniku - što je s
 * time da se stariji zapisi uopće ne vide bilo tiho: ekran je izgledao ispravno, samo je
 * odgovarao na manje pitanje nego što je korisnik mislio.
 */
describe('AuditLog - datum, akcija i stranice', () => {
  let component: AuditLog;
  let http: HttpTestingController;
  /** Bez `detectChanges()` se efekti ne pokreću, pa test ne vidi dvostruki dohvat. */
  let fixture: ComponentFixture<AuditLog>;

  function page(content: object[], totalElements = content.length, pageNumber = 0) {
    return {
      content, page: pageNumber, size: 50, totalElements,
      totalPages: Math.ceil(totalElements / 50)
    };
  }

  const entry = {
    id: 1, username: 'ana', action: 'RECORD_LOCKED',
    detail: 'Zapis id=1 u obrascu id=10', createdAt: '2026-08-12T09:00:00Z'
  };

  /** Zahtjev za dnevnikom; vraća parametre s kojima je poslan. */
  function takeRequest(totalElements = 120): URLSearchParams {
    const request = http.expectOne((r) => r.url === '/api/logs');
    const params = new URLSearchParams(request.request.params.toString());
    request.flush(page([], totalElements));
    return params;
  }

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('activeCompanyId', '2');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    fixture = TestBed.createComponent(AuditLog);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    http.expectOne((r) => r.url === '/api/logs').flush(page([entry], 120));
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('prvi dohvat je prva stranica, bez datuma i bez akcije', () => {
    expect(component['entries']()).toHaveLength(1);
    expect(component['totalElements']()).toBe(120);
    expect(component['totalPages']()).toBe(3);
  });

  it('odabrani datum putuje na server kao yyyy-MM-dd', () => {
    component['fromDate'].set('2026-08-01');
    component['onFilterChange']();

    const params = takeRequest();
    expect(params.get('from')).toBe('2026-08-01');
    expect(params.get('page')).toBe('0');
  });

  /** Filtar radi baza; da radi ovdje, prosijao bi samo trenutnih 50 zapisa. */
  it('odabrana akcija putuje na server', () => {
    component['actionFilter'].set('RECORD_LOCKED');
    component['onFilterChange']();

    expect(takeRequest().get('action')).toBe('RECORD_LOCKED');
  });

  it('promjena filtra vraća na prvu stranicu', () => {
    component['goToPage'](2);
    takeRequest();
    expect(component['page']()).toBe(2);

    component['fromDate'].set('2026-08-01');
    component['onFilterChange']();

    expect(takeRequest().get('page')).toBe('0');
    expect(component['page']()).toBe(0);
  });

  it('odlazak na stranicu šalje njezin broj i pomiče prikazani raspon', () => {
    component['goToPage'](1);

    expect(takeRequest().get('page')).toBe('1');
    expect(component['rangeFrom']()).toBe(51);
    expect(component['rangeTo']()).toBe(100);
  });

  /**
   * Regresija (12.08.2026.): efekt koji učitava dnevnik čitao je i signale filtra i stranice,
   * pa ga je svaka njihova promjena budila - jedan klik, dva zahtjeva. Na ekranu „Podaci" je
   * isti kvar poništavao i odabir stranice.
   */
  it('promjena filtra šalje točno jedan zahtjev', () => {
    component['fromDate'].set('2026-08-01');
    component['onFilterChange']();
    takeRequest();

    fixture.detectChanges();

    http.expectNone((r) => r.url === '/api/logs');
    expect(component['fromDate']()).toBe('2026-08-01');
  });

  it('stranica izvan raspona se ignorira', () => {
    component['goToPage'](5);
    component['goToPage'](-2);

    http.expectNone((r) => r.url === '/api/logs');
  });

  /**
   * Ponuda akcija je stalan popis, a ne ono što je viđeno u podacima: sa stranicama bi se
   * izvodila iz 50 zapisa na ekranu, pa bi izbornik nudio samo ono što se ionako već vidi.
   */
  it('izbornik akcija ne ovisi o tome što je na trenutnoj stranici', () => {
    expect(component['knownActions']).toContain('LOGIN_SUCCESS');
    expect(component['knownActions']).toContain('ATTACHMENT_ADDED');
    expect(component['knownActions'].length).toBeGreaterThan(1);
  });
});

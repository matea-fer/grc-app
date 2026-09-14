import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { Organizations } from './organizations';
import { validTestToken } from '../auth/auth.test-utils';

/**
 * Ekran ima jednu radnju koju ništa ne može poništiti - trajno pražnjenje firme - i sve
 * ovdje se vrti oko toga da se do nje ne može doći slučajno:
 *
 *  - obično brisanje samo ARHIVIRA (podaci ostaju, firma se može vratiti),
 *  - pražnjenje ide na zasebnu rutu i traži da se naziv firme prepiše rukom.
 *
 * Zadnje je jedina provjera koju vrijedi testirati na razini ekrana: da se poziv NE pošalje
 * dok se upisano i stvarno ime ne poklope.
 */
describe('Organizations', () => {
  let http: HttpTestingController;

  const ACTIVE = { id: 7, name: 'Acme', deletedAt: null };
  const ARCHIVED = { id: 8, name: 'Beta', deletedAt: '2026-08-10T09:00:00Z' };

  function setup() {
    localStorage.setItem('authToken', validTestToken());
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'admin', role: 'ADMIN', companyId: null, companyName: null })
    );

    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });

    const fixture = TestBed.createComponent(Organizations);
    http = TestBed.inject(HttpTestingController);

    // ekran puni dvije odvojene tablice: aktivne i arhivirane
    http.expectOne('/api/companies').flush([ACTIVE]);
    http.expectOne('/api/companies/archived').flush([ARCHIVED]);

    return fixture;
  }

  /** Pristup zaštićenim članovima; u predlošku su dostupni, u testu nisu. */
  function api(fixture: { componentInstance: unknown }) {
    return fixture.componentInstance as {
      remove(company: unknown): void;
      restore(company: unknown): void;
      openPurgeDialog(company: unknown): void;
      purge(): void;
      purgeConfirmation: { set(value: string): void };
      purgeTarget: () => unknown;
      archived: () => unknown[];
    };
  }

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    http.verify();
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('arhivirane firme se drže odvojeno od aktivnih', () => {
    const fixture = setup();

    expect(api(fixture).archived()).toEqual([ARCHIVED]);
  });

  it('brisanje samo arhivira - ide na DELETE /api/companies/{id}, bez pražnjenja', () => {
    const fixture = setup();
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    api(fixture).remove(ACTIVE);

    const request = http.expectOne('/api/companies/7');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);

    // nakon uspjeha se oba popisa osvježavaju
    http.expectOne('/api/companies').flush([]);
    http.expectOne('/api/companies/archived').flush([ACTIVE]);
  });

  it('vraćanje iz arhive ide na svoju rutu', () => {
    const fixture = setup();

    api(fixture).restore(ARCHIVED);

    const request = http.expectOne('/api/companies/8/restore');
    expect(request.request.method).toBe('POST');
    request.flush(ACTIVE);

    http.expectOne('/api/companies').flush([]);
    http.expectOne('/api/companies/archived').flush([]);
  });

  it('pražnjenje se NE šalje dok upisani naziv ne odgovara nazivu firme', () => {
    const fixture = setup();
    const component = api(fixture);

    component.openPurgeDialog(ARCHIVED);
    component.purgeConfirmation.set('beta'); // razlikuje se u veličini slova
    component.purge();

    // http.verify() u afterEach padne ako je ijedan poziv otišao
    expect(component.purgeTarget()).not.toBeNull();
  });

  it('pražnjenje s točno prepisanim nazivom ide na /purge', () => {
    const fixture = setup();
    const component = api(fixture);

    component.openPurgeDialog(ARCHIVED);
    component.purgeConfirmation.set('Beta');
    component.purge();

    const request = http.expectOne('/api/companies/8/purge');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);

    expect(component.purgeTarget()).toBeNull();
    http.expectOne('/api/companies').flush([]);
    http.expectOne('/api/companies/archived').flush([]);
  });
});

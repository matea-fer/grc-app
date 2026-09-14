import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { vi } from 'vitest';

import { Codebooks } from './codebooks';
import { Codebook } from '../codebook/codebook.model';
import { validTestToken } from '../auth/auth.test-utils';

/**
 * Filtar i pretraga idu NA SERVER, za razliku od ostalih ekrana. Zato se ovdje
 * provjerava što točno završi u query parametrima, i da svaki pritisnut znak ne
 * bude jedan zahtjev.
 */
describe('Codebooks - filtriranje na serveru', () => {
  let http: HttpTestingController;

  function signedInAs(role: 'ADMIN' | 'TENANT_ADMIN' | 'USER'): void {
    localStorage.setItem('authToken', validTestToken());
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'netko', role, companyId: role === 'ADMIN' ? null : 7, companyName: 'Acme' })
    );
    if (role !== 'ADMIN') {
      localStorage.setItem('activeCompanyId', '7');
    }
  }

  function setup() {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
    const fixture = TestBed.createComponent(Codebooks);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    localStorage.clear();
    vi.useRealTimers();
  });

  it('prvo učitavanje ide bez ijednog parametra', () => {
    signedInAs('ADMIN');
    const fixture = setup();

    const request = http.expectOne((r) => r.url === '/api/codebooks');
    expect(request.request.params.keys()).toEqual([]);
    request.flush([]);

    fixture.destroy();
    http.verify();
  });

  it('filtar po dosegu odmah šalje scope parametar', () => {
    signedInAs('ADMIN');
    const fixture = setup();
    http.expectOne((r) => r.url === '/api/codebooks').flush([]);

    fixture.componentInstance['onScopeFilterChange']('GLOBAL');

    const request = http.expectOne((r) => r.url === '/api/codebooks');
    expect(request.request.params.get('scope')).toBe('GLOBAL');
    request.flush([]);

    fixture.destroy();
    http.verify();
  });

  it('pretraga se odgađa - ne šalje zahtjev na svaki znak', () => {
    vi.useFakeTimers();
    signedInAs('ADMIN');
    const fixture = setup();
    http.expectOne((r) => r.url === '/api/codebooks').flush([]);

    const input = document.createElement('input');
    input.value = 'sta';
    fixture.componentInstance['onSearchChange']({ target: input } as unknown as Event);

    // odmah nakon tipkanja još ništa ne ide na server
    http.expectNone((r) => r.url === '/api/codebooks');

    vi.advanceTimersByTime(300);

    const request = http.expectOne((r) => r.url === '/api/codebooks');
    expect(request.request.params.get('search')).toBe('sta');
    request.flush([]);

    fixture.destroy();
    http.verify();
  });
});

/**
 * Globalni šifrarnik nije "šifrarnik s većim ovlastima" nego onaj koji ne pripada
 * nikome - pa ga smije mijenjati samo tko nije vezan uz firmu. Ekran to mora
 * odražavati po RETKU, a ne jednom za cijeli popis.
 */
describe('Codebooks - tko što smije', () => {
  let http: HttpTestingController;

  function signedInAs(role: 'ADMIN' | 'TENANT_ADMIN' | 'USER'): void {
    localStorage.setItem('authToken', validTestToken());
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'netko', role, companyId: role === 'ADMIN' ? null : 7, companyName: 'Acme' })
    );
  }

  function componentFor(role: 'ADMIN' | 'TENANT_ADMIN' | 'USER') {
    signedInAs(role);
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
    const fixture = TestBed.createComponent(Codebooks);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.match(() => true).forEach((request) => request.flush([]));
    return fixture;
  }

  const global: Codebook = { id: 1, name: 'Države', scope: 'GLOBAL', companyId: null, itemCount: 5 };
  const tenant: Codebook = { id: 2, name: 'Statusi', scope: 'TENANT', companyId: 7, itemCount: 3 };

  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('globalni administrator smije uređivati oba dosega', () => {
    const fixture = componentFor('ADMIN');
    const instance = fixture.componentInstance;

    expect(instance['canEdit'](global)).toBe(true);
    expect(instance['canEdit'](tenant)).toBe(true);
    expect(instance['scopeOptions']()).toEqual(['TENANT', 'GLOBAL']);

    fixture.destroy();
  });

  it('administrator firme smije firmin, ali ne globalni', () => {
    const fixture = componentFor('TENANT_ADMIN');
    const instance = fixture.componentInstance;

    expect(instance['canEdit'](tenant)).toBe(true);
    expect(instance['canEdit'](global)).toBe(false);
    // globalni doseg mu se ni ne nudi pri stvaranju
    expect(instance['scopeOptions']()).toEqual(['TENANT']);

    fixture.destroy();
  });

  it('obični korisnik ne smije uređivati ništa ni dodavati', () => {
    const fixture = componentFor('USER');
    const instance = fixture.componentInstance;

    expect(instance['canEdit'](tenant)).toBe(false);
    expect(instance['canEdit'](global)).toBe(false);
    expect(instance['canCreate']()).toBe(false);

    fixture.destroy();
  });
});

/**
 * Put do stavki šifrarnika.
 *
 * Sadržaj se prije otvarao klikom na NAZIV, a to se nije dalo naslutiti: naziv u tablici
 * izgleda kao podatak, ne kao put dalje. Ista lekcija kao sa strelicama „↕" na zaglavljima i
 * s retkom u dijalogu povezanih zapisa - radnja koja se ne vidi kao radnja ne postoji.
 */
describe('Codebooks - otvaranje stavki', () => {
  let http: HttpTestingController;
  let navigated: unknown[][];

  const tenant: Codebook = { id: 2, name: 'Statusi', scope: 'TENANT', companyId: 7, itemCount: 3 };

  function open(role: 'TENANT_ADMIN' | 'USER') {
    localStorage.setItem('authToken', validTestToken());
    localStorage.setItem('authUser',
      JSON.stringify({ username: 'netko', role, companyId: 7, companyName: 'Acme' }));

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
    const fixture = TestBed.createComponent(Codebooks);
    http = TestBed.inject(HttpTestingController);
    navigated = [];
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockImplementation((commands: readonly unknown[]) => {
      navigated.push([...commands]);
      return Promise.resolve(true);
    });

    fixture.detectChanges();
    http.match(() => true).forEach((request) => request.flush([tenant]));
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('svaki redak nudi gumb koji vodi na stavke', () => {
    const fixture = open('TENANT_ADMIN');

    const button: HTMLButtonElement | null = fixture.nativeElement.querySelector('.row-action-button');
    expect(button?.textContent?.trim()).toBe('Uredi stavke');

    button?.click();
    expect(navigated).toEqual([['/sifrarnici', 2]]);

    fixture.destroy();
  });

  /** Tko šifrarnik ne smije mijenjati, ne smije ni dobiti gumb koji obećava uređivanje. */
  it('bez prava uređivanja gumb nudi pregled', () => {
    const fixture = open('USER');

    const button: HTMLButtonElement | null = fixture.nativeElement.querySelector('.row-action-button');
    expect(button?.textContent?.trim()).toBe('Pogledaj stavke');

    fixture.destroy();
  });
});

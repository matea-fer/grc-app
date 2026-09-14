import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';
import { validTestToken } from './auth/auth.test-utils';

describe('App', () => {
  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [App],
      // App u konstruktoru dohvaća firme (HttpClient) i vodi rute
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render the router outlet', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('router-outlet')).toBeTruthy();
  });
});

/**
 * Prazan izbornik firmi ima dva posve različita uzroka - "firmi nema" i "popis se nije
 * učitao" - a izgledaju isto. Zato se ovdje provjerava da traka to razlikuje, i to kroz
 * DOM: poruka koja postoji samo u signalu korisniku ne pomaže.
 */
describe('App - traka kad učitavanje ne uspije', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    localStorage.setItem('authToken', validTestToken());
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'admin', role: 'ADMIN', companyId: null, companyName: null })
    );
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  /** Provjeru tokena (/api/auth/me) ovdje ne ispitujemo - samo je uredno zatvorimo. */
  function flushVerify(): void {
    http
      .expectOne('/api/auth/me')
      .flush({ username: 'admin', role: 'ADMIN', companyId: null, companyName: null });
  }

  async function render() {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture;
  }

  it('ugašen backend javi kao "server ne odgovara", ne kao prazan popis', async () => {
    const fixture = await render();
    flushVerify();
    // status 0 = odgovora nije ni bilo; zahtjev nije stigao do servera
    http
      .expectOne('/api/companies')
      .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();

    const banner = fixture.nativeElement.querySelector('.session-error');
    expect(banner?.textContent).toContain('Server ne odgovara');
    // i sam izbornik mora prestati tvrditi da se bira - nema se iz čega birati
    expect(fixture.nativeElement.querySelector('.tenant-picker option')?.textContent).toContain(
      'popis nije učitan'
    );
  });

  it('uredno prazan popis nije greška', async () => {
    const fixture = await render();
    flushVerify();
    http.expectOne('/api/companies').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.session-error')).toBeNull();
    expect(fixture.nativeElement.querySelector('.tenant-picker option')?.textContent).toContain(
      'nema firmi'
    );
  });

  it('ponovni pokušaj dohvaća popis i miče poruku', async () => {
    const fixture = await render();
    flushVerify();
    http
      .expectOne('/api/companies')
      .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.retry-button').click();
    flushVerify();
    http.expectOne('/api/companies').flush([{ id: 1, name: 'Acme' }]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.session-error')).toBeNull();
  });

  it('na 401 se ne javlja ništa - korisnika ionako vodi na prijavu', async () => {
    const fixture = await render();
    flushVerify();
    http
      .expectOne('/api/companies')
      .flush({ message: 'isteklo' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.session-error')).toBeNull();
  });
});

import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AuthService } from './auth.service';
import { tokenExpiringIn } from './auth.test-utils';
import { TenantService } from '../tenant/tenant.service';

describe('AuthService', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('kreće odjavljen kad u localStorage nema tokena', () => {
    const service = TestBed.inject(AuthService);
    expect(service.isLoggedIn()).toBe(false);
    expect(service.currentUser()).toBeNull();
  });

  it('prijava spremi token i korisnika te ih zapamti u localStorage', () => {
    const service = TestBed.inject(AuthService);
    service.login('ana', 'tajna123').subscribe();

    httpMock.expectOne('/api/auth/login').flush({
      token: 'token-abc',
      username: 'ana',
      role: 'USER',
      companyId: 7,
      companyName: 'Acme'
    });

    expect(service.isLoggedIn()).toBe(true);
    expect(service.currentUser()?.username).toBe('ana');
    expect(localStorage.getItem('authToken')).toBe('token-abc');
  });

  it('običnom korisniku postavi aktivnu firmu iz odgovora - on je nema gdje odabrati', () => {
    const service = TestBed.inject(AuthService);
    const tenant = TestBed.inject(TenantService);
    service.login('ana', 'tajna123').subscribe();

    httpMock.expectOne('/api/auth/login').flush({
      token: 'token-abc',
      username: 'ana',
      role: 'USER',
      companyId: 7,
      companyName: 'Acme'
    });

    expect(tenant.activeCompanyId()).toBe(7);
  });

  it('administratoru ne postavlja firmu - on je bira u traci', () => {
    const service = TestBed.inject(AuthService);
    const tenant = TestBed.inject(TenantService);
    service.login('admin', 'tajna123').subscribe();

    httpMock.expectOne('/api/auth/login').flush({
      token: 'token-abc',
      username: 'admin',
      role: 'ADMIN',
      companyId: null,
      companyName: null
    });

    expect(service.isAdmin()).toBe(true);
    expect(tenant.activeCompanyId()).toBeNull();
  });

  it('odjava očisti token, korisnika i odabir firme', () => {
    localStorage.setItem('authToken', tokenExpiringIn(3600));
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'ana', role: 'USER', companyId: 7, companyName: 'Acme' })
    );
    const service = TestBed.inject(AuthService);
    const tenant = TestBed.inject(TenantService);
    tenant.setActive(7);

    service.logout();

    expect(service.isLoggedIn()).toBe(false);
    expect(localStorage.getItem('authToken')).toBeNull();
    // odabir firme pripada prošloj sesiji - ne smije ga naslijediti sljedeći korisnik
    expect(tenant.activeCompanyId()).toBeNull();
  });

  it('pokvaren zapis korisnika u localStorage ne ruši aplikaciju', () => {
    localStorage.setItem('authToken', tokenExpiringIn(3600));
    localStorage.setItem('authUser', '{nije-json');

    const service = TestBed.inject(AuthService);

    expect(service.currentUser()).toBeNull();
    expect(service.isAdmin()).toBe(false);
  });

  it('token koji još vrijedi preživi ponovno otvaranje aplikacije', () => {
    localStorage.setItem('authToken', tokenExpiringIn(3600));
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'ana', role: 'USER', companyId: 7, companyName: 'Acme' })
    );

    const service = TestBed.inject(AuthService);

    expect(service.isLoggedIn()).toBe(true);
    expect(service.currentUser()?.username).toBe('ana');
  });

  it('istekao token se odbacuje odmah, bez čekanja na 401 sa servera', () => {
    localStorage.setItem('authToken', tokenExpiringIn(-60));
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'ana', role: 'USER', companyId: 7, companyName: 'Acme' })
    );

    const service = TestBed.inject(AuthService);
    const tenant = TestBed.inject(TenantService);

    // sučelje se ne smije nacrtati kao prijavljeno ni na trenutak
    expect(service.isLoggedIn()).toBe(false);
    expect(service.currentUser()).toBeNull();
    expect(localStorage.getItem('authToken')).toBeNull();
    expect(localStorage.getItem('authUser')).toBeNull();
    // istekla sesija je i dalje prošla sesija - odabir firme ne smije preživjeti
    expect(tenant.activeCompanyId()).toBeNull();
  });

  it('token koji se ne da pročitati vrijedi kao istekao', () => {
    localStorage.setItem('authToken', 'token-bez-ijedne-tocke');

    const service = TestBed.inject(AuthService);

    expect(service.isLoggedIn()).toBe(false);
    expect(localStorage.getItem('authToken')).toBeNull();
  });

  it('token bez polja exp vrijedi kao istekao', () => {
    localStorage.setItem('authToken', `${btoa('{}')}.${btoa('{"sub":"ana"}')}.potpis`);

    const service = TestBed.inject(AuthService);

    expect(service.isLoggedIn()).toBe(false);
  });
});

import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { Role } from '../auth/auth.model';
import { validTestToken } from '../auth/auth.test-utils';
import { tenantInterceptor } from './tenant.interceptor';
import { TenantService } from './tenant.service';

/**
 * TenantID se od uvođenja prijave šalje SAMO administratoru - običnom korisniku
 * firmu određuje token, pa mu backend ovo zaglavlje ionako ignorira.
 */
describe('tenantInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let tenant: TenantService;

  // AuthService čita localStorage pri stvaranju, pa se prijava mora "dogoditi" prije injekcije
  function signedInAs(role: Role): void {
    localStorage.setItem('authToken', validTestToken());
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'netko', role, companyId: role === 'ADMIN' ? null : 7, companyName: null })
    );
  }

  function setup(): void {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([tenantInterceptor])), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    tenant = TestBed.inject(TenantService);
  }

  beforeEach(() => localStorage.clear());

  afterEach(() => httpMock.verify());

  it('doda TenantID administratoru kad je firma odabrana', () => {
    signedInAs('ADMIN');
    setup();
    tenant.setActive(3);
    http.get('/api/templates').subscribe();

    const req = httpMock.expectOne('/api/templates');
    expect(req.request.headers.get('TenantID')).toBe('3');
    req.flush([]);
  });

  it('NE doda TenantID običnom korisniku - njegovu firmu određuje token', () => {
    signedInAs('USER');
    setup();
    tenant.setActive(7);
    http.get('/api/templates').subscribe();

    const req = httpMock.expectOne('/api/templates');
    expect(req.request.headers.has('TenantID')).toBe(false);
    req.flush([]);
  });

  it('NE doda zaglavlje na rutu firmi - ona nije vezana uz jednu firmu', () => {
    signedInAs('ADMIN');
    setup();
    tenant.setActive(3);
    http.get('/api/companies').subscribe();

    const req = httpMock.expectOne('/api/companies');
    expect(req.request.headers.has('TenantID')).toBe(false);
    req.flush([]);
  });

  it('NE doda zaglavlje kad administrator nije odabrao firmu', () => {
    signedInAs('ADMIN');
    setup();
    http.get('/api/templates').subscribe();

    const req = httpMock.expectOne('/api/templates');
    expect(req.request.headers.has('TenantID')).toBe(false);
    req.flush([]);
  });

  it('doda TenantID i na dnevnik - i on je vezan uz firmu', () => {
    signedInAs('ADMIN');
    setup();
    tenant.setActive(3);
    http.get('/api/logs').subscribe();

    const req = httpMock.expectOne('/api/logs');
    expect(req.request.headers.get('TenantID')).toBe('3');
    req.flush([]);
  });
});

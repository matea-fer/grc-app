import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { vi } from 'vitest';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { validTestToken } from './auth.test-utils';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;

  function setup(): void {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting()
      ]
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  }

  beforeEach(() => localStorage.clear());

  afterEach(() => httpMock.verify());

  it('doda Authorization: Bearer kad je korisnik prijavljen', () => {
    const token = validTestToken();
    localStorage.setItem('authToken', token);
    setup();
    http.get('/api/templates').subscribe();

    const req = httpMock.expectOne('/api/templates');
    expect(req.request.headers.get('Authorization')).toBe(`Bearer ${token}`);
    req.flush([]);
  });

  it('NE doda zaglavlje na samu prijavu', () => {
    localStorage.setItem('authToken', validTestToken());
    setup();
    http.post('/api/auth/login', {}).subscribe();

    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('NE doda zaglavlje kad tokena nema', () => {
    setup();
    http.get('/api/templates').subscribe();

    const req = httpMock.expectOne('/api/templates');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush([]);
  });

  it('na 401 odjavi korisnika i vodi ga na prijavu', () => {
    localStorage.setItem('authToken', validTestToken());
    setup();
    const authService = TestBed.inject(AuthService);
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate');

    http.get('/api/templates').subscribe({ error: () => {} });
    httpMock.expectOne('/api/templates').flush({ message: 'isteklo' }, { status: 401, statusText: 'Unauthorized' });

    expect(authService.isLoggedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/prijava']);
  });

  it('neuspjela prijava ne odjavljuje - tu poruku prikazuje sam ekran prijave', () => {
    setup();
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate');

    http.post('/api/auth/login', {}).subscribe({ error: () => {} });
    httpMock
      .expectOne('/api/auth/login')
      .flush({ message: 'kriva lozinka' }, { status: 401, statusText: 'Unauthorized' });

    expect(navigate).not.toHaveBeenCalled();
  });

  it('greške koje nisu 401 prolaze dalje netaknute', () => {
    localStorage.setItem('authToken', validTestToken());
    setup();
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate');
    let status: number | null = null;

    http.get('/api/templates').subscribe({ error: (error: { status: number }) => (status = error.status) });
    httpMock.expectOne('/api/templates').flush({ message: 'ups' }, { status: 500, statusText: 'Server Error' });

    expect(status).toBe(500);
    expect(navigate).not.toHaveBeenCalled();
  });
});

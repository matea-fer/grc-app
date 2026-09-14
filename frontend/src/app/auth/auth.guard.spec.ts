import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';

import { adminGuard, authGuard, schemaEditorGuard, userManagerGuard } from './auth.guard';
import { Role } from './auth.model';
import { validTestToken } from './auth.test-utils';

/**
 * Guardovi samo skrivaju ekrane - podatke i dalje brani backend. Zato se ovdje
 * provjerava kamo korisnika preusmjeravaju, a ne "je li spriječen pristup".
 */
describe('auth guards', () => {
  function signedInAs(role: Role): void {
    localStorage.setItem('authToken', validTestToken());
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'netko', role, companyId: role === 'ADMIN' ? null : 7, companyName: null })
    );
  }

  function setup(): void {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
  }

  // guardovi koriste inject(), pa moraju trčati unutar injekcijskog konteksta
  function run(guard: typeof authGuard): boolean | UrlTree {
    return TestBed.runInInjectionContext(
      () => guard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot) as boolean | UrlTree
    );
  }

  beforeEach(() => localStorage.clear());

  it('authGuard propušta prijavljenog korisnika', () => {
    signedInAs('USER');
    setup();
    expect(run(authGuard)).toBe(true);
  });

  it('authGuard neprijavljenog vodi na prijavu', () => {
    setup();
    const result = run(authGuard);
    expect(result instanceof UrlTree).toBe(true);
    expect((result as UrlTree).toString()).toBe('/prijava');
  });

  it('adminGuard propušta administratora', () => {
    signedInAs('ADMIN');
    setup();
    expect(run(adminGuard)).toBe(true);
  });

  it('adminGuard običnog korisnika vraća na Podatke', () => {
    signedInAs('USER');
    setup();
    const result = run(adminGuard);
    expect(result instanceof UrlTree).toBe(true);
    expect((result as UrlTree).toString()).toBe('/podaci');
  });

  /** Organizacije se tiču svih firmi - administrator jedne firme ondje nema što raditi. */
  it('adminGuard administratora firme vraća na Podatke', () => {
    signedInAs('TENANT_ADMIN');
    setup();
    const result = run(adminGuard);
    expect(result instanceof UrlTree).toBe(true);
    expect((result as UrlTree).toString()).toBe('/podaci');
  });

  it('schemaEditorGuard propušta oba administratora', () => {
    signedInAs('TENANT_ADMIN');
    setup();
    expect(run(schemaEditorGuard)).toBe(true);
  });

  /** Shema je konfiguracija: obični korisnik po njoj unosi zapise, ali je ne mijenja. */
  it('schemaEditorGuard običnog korisnika vraća na Podatke', () => {
    signedInAs('USER');
    setup();
    const result = run(schemaEditorGuard);
    expect(result instanceof UrlTree).toBe(true);
    expect((result as UrlTree).toString()).toBe('/podaci');
  });

  it('userManagerGuard propušta i globalnog administratora i administratora firme', () => {
    signedInAs('ADMIN');
    setup();
    expect(run(userManagerGuard)).toBe(true);

    localStorage.clear();
    TestBed.resetTestingModule();
    signedInAs('TENANT_ADMIN');
    setup();
    expect(run(userManagerGuard)).toBe(true);
  });

  it('userManagerGuard običnog korisnika vraća na Podatke', () => {
    signedInAs('USER');
    setup();
    const result = run(userManagerGuard);
    expect(result instanceof UrlTree).toBe(true);
    expect((result as UrlTree).toString()).toBe('/podaci');
  });
});

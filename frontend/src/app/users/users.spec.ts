import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { Users } from './users';
import { validTestToken } from '../auth/auth.test-utils';

/**
 * Provjere unosa novog korisnika moraju javiti U DIJALOGU. Poruka na stranici iza
 * zatamnjene pozadine je poruka koju korisnik ne vidi, a "klik ne radi ništa" je
 * najgori mogući ishod - korisnik ne zna ni da je nešto krivo.
 */
describe('Users - provjere unosa', () => {
  let component: Users;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    component = TestBed.createComponent(Users).componentInstance;
    http = TestBed.inject(HttpTestingController);
    // konstruktor dohvaća korisnike i firme; ovdje nas zanima samo forma
    http.match(() => true).forEach((request) => request.flush([]));
  });

  /** Popuni formu ispravno, pa neka test pokvari samo ono što ispituje. */
  function fillValidForm(): void {
    component['newUsername'].set('novi');
    component['newPassword'].set('lozinka123');
    component['newRole'].set('USER');
    component['newCompanyId'].set(1);
  }

  function add(): void {
    component['add']();
  }

  it('bez odabrane firme javlja grešku u dijalogu i ne šalje zahtjev', () => {
    fillValidForm();
    component['newCompanyId'].set(null);

    add();

    expect(component['dialogError']()).toBe('Korisnik mora pripadati firmi.');
    expect(component['error']()).toBeNull();
    http.expectNone(() => true);
  });

  it('bez korisničkog imena javlja grešku u dijalogu', () => {
    fillValidForm();
    component['newUsername'].set('   ');

    add();

    expect(component['dialogError']()).toBe('Korisničko ime je obavezno.');
    http.expectNone(() => true);
  });

  it('prekratka lozinka javlja grešku u dijalogu', () => {
    fillValidForm();
    component['newPassword'].set('kratka');

    add();

    expect(component['dialogError']()).toBe('Lozinka mora imati najmanje 8 znakova.');
    http.expectNone(() => true);
  });

  /** ADMIN ne pripada firmi, pa mu izostanak firme nije greška. */
  it('administrator prolazi bez firme', () => {
    fillValidForm();
    component['newRole'].set('ADMIN');
    component['newCompanyId'].set(null);

    add();

    expect(component['dialogError']()).toBeNull();
    http.expectOne('/api/users');
  });

  it('ispravan unos šalje zahtjev i ne javlja grešku', () => {
    fillValidForm();

    add();

    expect(component['dialogError']()).toBeNull();
    http.expectOne('/api/users');
  });

  /**
   * Postaviti poruku nije dovoljno - mora se i vidjeti, i to unutar dijaloga.
   * Zato ovaj test ide do DOM-a i klika pravi gumb, umjesto da zove metodu.
   */
  it('poruka se stvarno ispiše unutar dijaloga', () => {
    const fixture = TestBed.createComponent(Users);
    const instance = fixture.componentInstance;
    fixture.detectChanges();
    http.match(() => true).forEach((request) => request.flush([]));

    instance['openAddDialog']();
    instance['newUsername'].set('novi');
    instance['newPassword'].set('lozinka123');
    fixture.detectChanges();

    const dialog: HTMLElement = fixture.nativeElement.querySelector('.modal-dialog');
    const save = [...dialog.querySelectorAll('button')].find((b) => b.textContent?.includes('Dodaj korisnika'));
    save!.click();
    fixture.detectChanges();

    expect(dialog.querySelector('.dialog-error')?.textContent).toContain('Korisnik mora pripadati firmi');
    // dijalog ostaje otvoren da korisnik može ispraviti unos
    expect(fixture.nativeElement.querySelector('.modal-dialog')).not.toBeNull();
    http.expectNone(() => true);
  });

  afterEach(() => http.verify());
});

/**
 * Administrator firme nema birač firme - jedina moguća je njegova. Forma zato mora
 * poslati NJEGOVU firmu i kad je korisnik nigdje nije odabrao, inače bi svaki unos
 * pao na "korisnik mora pripadati firmi" bez ijednog polja koje bi to ispravilo.
 */
describe('Users - administrator firme', () => {
  let component: Users;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('authToken', validTestToken());
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'sef', role: 'TENANT_ADMIN', companyId: 7, companyName: 'Acme' })
    );
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    component = TestBed.createComponent(Users).componentInstance;
    http = TestBed.inject(HttpTestingController);
    http.match(() => true).forEach((request) => request.flush([]));
  });

  it('šalje svoju firmu iako je nigdje nije odabrao', () => {
    component['newUsername'].set('ana');
    component['newPassword'].set('lozinka123');
    component['newRole'].set('USER');
    component['newCompanyId'].set(null);

    component['add']();

    expect(component['dialogError']()).toBeNull();
    expect(http.expectOne('/api/users').request.body.companyId).toBe(7);
  });

  it('ne nudi ulogu globalnog administratora', () => {
    expect(component['roleOptions']()).toEqual(['USER', 'TENANT_ADMIN']);
  });

  it('smije imenovati administratora svoje firme', () => {
    component['newUsername'].set('zamjenik');
    component['newPassword'].set('lozinka123');
    component['newRole'].set('TENANT_ADMIN');

    component['add']();

    const request = http.expectOne('/api/users').request;
    expect(request.body.role).toBe('TENANT_ADMIN');
    expect(request.body.companyId).toBe(7);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });
});

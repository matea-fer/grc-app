import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { CodebookEditor } from './codebook-editor';
import { validTestToken } from '../auth/auth.test-utils';

/**
 * Spremanje je PUNA ZAMJENA popisa, pa greška ovdje ne kvari jedan redak nego cijeli
 * šifrarnik. Zato se provjerava troje: da se neispravan popis uopće ne pošalje, da se
 * uklonjen redak stvarno izostavi, i da se sortOrder NE šalje (dodjeljuje ga server).
 */
describe('CodebookEditor', () => {
  let http: HttpTestingController;

  function setup(scope: 'GLOBAL' | 'TENANT' = 'TENANT', role: 'ADMIN' | 'TENANT_ADMIN' | 'USER' = 'ADMIN') {
    localStorage.setItem('authToken', validTestToken());
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'netko', role, companyId: role === 'ADMIN' ? null : 7, companyName: 'Acme' })
    );

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: '1' }) } }
        }
      ]
    });

    const fixture = TestBed.createComponent(CodebookEditor);
    http = TestBed.inject(HttpTestingController);

    http.expectOne('/api/codebooks/1').flush({
      id: 1,
      name: 'Statusi',
      scope,
      companyId: scope === 'TENANT' ? 7 : null,
      itemCount: 2
    });
    http.expectOne('/api/codebooks/1/items').flush([
      { id: 10, code: 'OPEN', name: 'Otvoren', active: true, sortOrder: 0 },
      { id: 11, code: 'CLOSED', name: 'Zatvoren', active: true, sortOrder: 1 }
    ]);

    return fixture;
  }

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('učitane stavke dolaze u popis, bez nespremljenih izmjena', () => {
    const fixture = setup();
    const instance = fixture.componentInstance;

    expect(instance['items']().map((i) => i.code)).toEqual(['OPEN', 'CLOSED']);
    expect(instance['dirty']()).toBe(false);

    fixture.destroy();
  });

  it('šalje id, šifru, naziv i aktivnost - ali NE sortOrder', () => {
    const fixture = setup();
    const instance = fixture.componentInstance;

    instance['updateName'](instance['items']()[0].key, 'Otvoren zahtjev');
    instance['save']();

    const request = http.expectOne('/api/codebooks/1/items');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.items[0]).toEqual({
      id: 10,
      code: 'OPEN',
      name: 'Otvoren zahtjev',
      active: true
    });
    request.flush([{ id: 10, code: 'OPEN', name: 'Otvoren zahtjev', active: true, sortOrder: 0 }]);

    fixture.destroy();
  });

  it('uklonjen redak se izostavlja iz popisa koji se šalje', () => {
    const fixture = setup();
    const instance = fixture.componentInstance;

    // potvrda brisanja se u testu preskače
    const originalConfirm = window.confirm;
    window.confirm = () => true;
    instance['removeRow'](instance['items']()[1]);
    window.confirm = originalConfirm;

    instance['save']();

    const request = http.expectOne('/api/codebooks/1/items');
    expect(request.request.body.items).toHaveLength(1);
    expect(request.request.body.items[0].code).toBe('OPEN');
    request.flush([{ id: 10, code: 'OPEN', name: 'Otvoren', active: true, sortOrder: 0 }]);

    fixture.destroy();
  });

  it('ista šifra dvaput blokira spremanje, bez obzira na velika slova', () => {
    const fixture = setup();
    const instance = fixture.componentInstance;

    instance['addRow']();
    const newRow = instance['items']()[2];
    instance['updateCode'](newRow.key, 'open');
    instance['updateName'](newRow.key, 'Duplikat');

    instance['save']();

    expect(instance['error']()).toContain('više puta');
    http.expectNone('/api/codebooks/1/items');

    fixture.destroy();
  });

  it('prazna šifra blokira spremanje', () => {
    const fixture = setup();
    const instance = fixture.componentInstance;

    instance['addRow']();
    instance['updateName'](instance['items']()[2].key, 'Bez šifre');

    instance['save']();

    expect(instance['error']()).toBe('Šifra je obavezna u svakom retku.');
    http.expectNone('/api/codebooks/1/items');

    fixture.destroy();
  });

  it('nova stavka nema id - server ga dodjeljuje', () => {
    const fixture = setup();
    const instance = fixture.componentInstance;

    instance['addRow']();
    const newRow = instance['items']()[2];
    instance['updateCode'](newRow.key, 'PENDING');
    instance['updateName'](newRow.key, 'Na čekanju');

    instance['save']();

    const request = http.expectOne('/api/codebooks/1/items');
    expect(request.request.body.items[2]).toEqual({
      id: null,
      code: 'PENDING',
      name: 'Na čekanju',
      active: true
    });
    request.flush([]);

    fixture.destroy();
  });

  /**
   * Redoslijed se ne šalje kao brojevi nego kao redoslijed u nizu - server ga prenumerira
   * po položaju. Zato je jedino što se ovdje mora provjeriti da niz ode zamijenjen.
   */
  it('pomicanje retka mijenja redoslijed u popisu koji se šalje', () => {
    const fixture = setup();
    const instance = fixture.componentInstance;

    instance['moveBy'](instance['items']()[1], -1);

    expect(instance['items']().map((i) => i.code)).toEqual(['CLOSED', 'OPEN']);
    expect(instance['dirty']()).toBe(true);

    instance['save']();
    const request = http.expectOne('/api/codebooks/1/items');
    expect(request.request.body.items.map((i: { code: string }) => i.code)).toEqual(['CLOSED', 'OPEN']);
    request.flush([]);

    fixture.destroy();
  });

  it('strelica koja nema kamo ne pomiče ništa', () => {
    const fixture = setup();
    const instance = fixture.componentInstance;

    instance['moveBy'](instance['items']()[0], -1);

    expect(instance['items']().map((i) => i.code)).toEqual(['OPEN', 'CLOSED']);
    // popis se nije promijenio, pa nema ni što spremati
    expect(instance['dirty']()).toBe(false);

    fixture.destroy();
  });

  it('ispuštanje retka na drugi ga premješta na to mjesto', () => {
    const fixture = setup();
    const instance = fixture.componentInstance;
    const drop = new Event('drop') as DragEvent;

    instance['onDragStart'](instance['items']()[1]);
    instance['onDrop'](drop, instance['items']()[0]);

    expect(instance['items']().map((i) => i.code)).toEqual(['CLOSED', 'OPEN']);
    // povlačenje je gotovo - inače bi sljedeći klik nastavio premještati isti redak
    expect(instance['draggedKey']()).toBeNull();

    fixture.destroy();
  });

  it('ispuštanje retka na samog sebe ne mijenja ništa', () => {
    const fixture = setup();
    const instance = fixture.componentInstance;
    const drop = new Event('drop') as DragEvent;

    instance['onDragStart'](instance['items']()[0]);
    instance['onDrop'](drop, instance['items']()[0]);

    expect(instance['items']().map((i) => i.code)).toEqual(['OPEN', 'CLOSED']);
    expect(instance['dirty']()).toBe(false);

    fixture.destroy();
  });

  it('administrator firme ne smije mijenjati globalni šifrarnik', () => {
    const fixture = setup('GLOBAL', 'TENANT_ADMIN');

    expect(fixture.componentInstance['canEdit']()).toBe(false);

    fixture.destroy();
  });

  it('globalni administrator smije mijenjati globalni šifrarnik', () => {
    const fixture = setup('GLOBAL', 'ADMIN');

    expect(fixture.componentInstance['canEdit']()).toBe(true);

    fixture.destroy();
  });
});

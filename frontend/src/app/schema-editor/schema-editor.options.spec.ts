import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { SchemaEditor } from './schema-editor';

/**
 * Postavke stupca koje ovise o TIPU.
 *
 * Glavno pravilo koje se ovdje brani: u zahtjev odlazi samo ono što odabranom tipu
 * pripada. Backend postavku koja se tipu ne tiče odbija, pa bi bez ovog filtriranja
 * korisnik dobio grešku o polju koje uopće nije vidio - ono je ostalo od prethodnog tipa.
 */
describe('SchemaEditor - postavke po tipu stupca', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('activeCompanyId', '2');
    localStorage.setItem('activeTemplateId', '21');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
  });

  afterEach(() => {
    // Odabir firme povuče i popis šifrarnika (za birač na stupcu tipa "Iz šifrarnika"), a
    // uspješno spremanje stupca povuče osvježen popis stupaca. Ni jedno ni drugo nije tema
    // ovih testova, pa se ovdje samo namiruje - inače bi verify() prijavio otvoren zahtjev.
    http.match('/api/codebooks').forEach((request) => request.flush([]));
    http.match('/api/templates/21/columns').forEach((request) => request.flush([]));
    http.verify();
    localStorage.clear();
  });

  /** Editor s odabranom firmom i obrascem, spreman za dodavanje stupca. */
  function openedEditor() {
    const fixture = TestBed.createComponent(SchemaEditor);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    http.expectOne('/api/templates').flush([{ id: 21, name: 'Obrazac' }]);
    fixture.detectChanges();
    http.expectOne('/api/templates/21/columns').flush([]);
    fixture.detectChanges();

    return fixture;
  }

  /**
   * Tijelo zahtjeva koji je otišao pri spremanju stupca.
   *
   * Traži se po metodi, a ne samo po putanji: nakon uspješnog spremanja Editor osvježava
   * popis stupaca, pa na istoj putanji stoje dva zahtjeva.
   */
  function sentColumn(): Record<string, unknown> {
    const request = http.expectOne(
      (candidate) => candidate.url === '/api/templates/21/columns' && candidate.method === 'POST'
    );
    const body = request.request.body as Record<string, unknown>;
    request.flush(body);
    return body;
  }

  it('u zahtjev ide samo ono što odabranom tipu pripada', () => {
    const fixture = openedEditor();
    const component = fixture.componentInstance;

    component['openColumnDialog']();
    component['newName'].set('naziv');
    component['onTypeChange']('string');
    component['newPattern'].set('^[A-Z]+$');
    component['submit']();

    const options = sentColumn()['options'] as Record<string, unknown>;
    expect(options['pattern']).toBe('^[A-Z]+$');
    // raspon i format broja se tekstu ne tiču - backend bi takav stupac odbio
    expect(options['min']).toBeNull();
    expect(options['numberFormat']).toBeNull();
    expect(options['autoIncrement']).toBe(false);

    fixture.destroy();
  });

  /**
   * Bez ovoga bi raspon upisan dok je stupac bio broj otputovao i nakon prebacivanja na
   * tekst - a korisnik u tom trenutku to polje uopće ne vidi.
   */
  it('promjena tipa briše postavke prethodnog tipa', () => {
    const fixture = openedEditor();
    const component = fixture.componentInstance;

    component['openColumnDialog']();
    component['newName'].set('polje');
    component['onTypeChange']('number');
    component['newMin'].set(1);
    component['newMax'].set(10);

    component['onTypeChange']('date');

    expect(component['newMin']()).toBeNull();
    expect(component['newMax']()).toBeNull();

    fixture.destroy();
  });

  it('automatski redni broj nameće cijeli broj i samo za čitanje', () => {
    const fixture = openedEditor();
    const component = fixture.componentInstance;

    component['openColumnDialog']();
    component['newName'].set('rbr');
    component['onTypeChange']('number');
    component['onAutoIncrementChange'](true);
    component['submit']();

    const body = sentColumn();
    expect(body['readOnly']).toBe(true);
    expect((body['options'] as Record<string, unknown>)['numberMode']).toBe('int');

    fixture.destroy();
  });

  it('više odgovora isključuje padajući izbornik i jedinstvenost', () => {
    const fixture = openedEditor();
    const component = fixture.componentInstance;

    component['openColumnDialog']();
    component['onTypeChange']('codebook');
    component['newUnique'].set(true);
    component['onMultipleChange'](true);

    // padajući izbornik ne prikazuje više odabranih vrijednosti, a niz ne može biti jedinstven
    expect(component['newPickerMode']()).toBe('checkbox');
    expect(component['newUnique']()).toBe(false);

    fixture.destroy();
  });

  /**
   * Slobodan unos je dodatak načinu prikaza, a ne zamjena za njega: potvrdni okviri i dijalog
   * uz popis dobiju polje za novu vrijednost. Zato se ni s čim ne isključuje.
   */
  it('slobodan unos ide zajedno s više odgovora i s odabranim načinom prikaza', () => {
    const fixture = openedEditor();
    const component = fixture.componentInstance;

    component['openColumnDialog']();
    component['newName'].set('gradovi');
    component['onTypeChange']('codebook');
    component['newCodebookId'].set(4);
    component['onMultipleChange'](true);
    component['newAllowNewValues'].set(true);
    component['newPickerMode'].set('popup');
    component['submit']();

    const options = sentColumn()['options'] as Record<string, unknown>;
    expect(options['multiple']).toBe(true);
    expect(options['allowNewValues']).toBe(true);
    expect(options['pickerMode']).toBe('popup');

    fixture.destroy();
  });

  /**
   * Uzorak se ovdje provjerava pravilima JEZIKA PREGLEDNIKA, a backend Javinima - i ona se
   * ne poklapaju u svemu (npr. "^\d{5" je u JavaScriptu ispravan, u Javi nije). Zato ovdje
   * stoji uzorak koji je neispravan u oba: provjera u pregledniku hvata očito, a backend
   * ostaje mjerodavan.
   */
  it('neispravan uzorak zaustavlja slanje uz poruku', () => {
    const fixture = openedEditor();
    const component = fixture.componentInstance;

    component['openColumnDialog']();
    component['newName'].set('posta');
    component['onTypeChange']('string');
    component['newPattern'].set('^[A-Z');
    component['submit']();

    expect(component['dialogError']()).toContain('nije ispravan regularni izraz');
    http.expectNone('/api/templates/21/columns');

    fixture.destroy();
  });

  it('formula bez izraza zaustavlja slanje', () => {
    const fixture = openedEditor();
    const component = fixture.componentInstance;

    component['openColumnDialog']();
    component['newName'].set('ukupno');
    component['onTypeChange']('formula');
    component['submit']();

    expect(component['dialogError']()).toContain('formulu');
    http.expectNone('/api/templates/21/columns');

    fixture.destroy();
  });

  it('besmislena širina zaustavlja slanje', () => {
    const fixture = openedEditor();
    const component = fixture.componentInstance;

    component['openColumnDialog']();
    component['newName'].set('naziv');
    component['newWidth'].set(5);
    component['submit']();

    expect(component['dialogError']()).toContain('Širina');
    http.expectNone('/api/templates/21/columns');

    fixture.destroy();
  });
});

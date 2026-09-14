import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { SchemaEditor } from './schema-editor';
import { EMPTY_COLUMN_OPTIONS } from '../column-definition/column-definition.model';
import { TenantService } from '../tenant/tenant.service';

/**
 * Promjena firme ostavlja u zraku zahtjev za obrazac koji je pripadao PRETHODNOJ firmi.
 * Taj zahtjev završi kao 404 (tuđi obrazac se ne otkriva), i to ponekad TEK NAKON što
 * je ispravan obrazac već učitan.
 *
 * Ako se takav odgovor pusti u stanje ekrana, korisnik gleda točne stupce uz poruku da
 * dohvaćanje nije uspjelo - greška koja opisuje zahtjev koji više nikoga ne zanima.
 */
describe('SchemaEditor - zastarjeli odgovori pri promjeni firme', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
  });

  /**
   * Odabir firme povuče i popis šifrarnika (za birač na stupcu tipa "Iz šifrarnika").
   * Ovaj spec je o zastarjelim odgovorima za OBRASCE, pa se ti zahtjevi ovdje samo
   * namire - inače bi verify() prijavio otvoren zahtjev u svakom testu.
   */
  afterEach(() => {
    http.match('/api/codebooks').forEach((request) => request.flush([]));
    http.verify();
  });

  it('404 za obrazac prethodne firme ne prijavljuje grešku ni ne briše učitane stupce', () => {
    // zapamćen odabir iz prethodne sesije: obrazac 11 pripada firmi 1, a aktivna je firma 2
    localStorage.setItem('activeCompanyId', '2');
    localStorage.setItem('activeTemplateId', '11');

    const fixture = TestBed.createComponent(SchemaEditor);
    const component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    // krenula su oba: popis obrazaca nove firme i stupci zastarjelog obrasca
    const staleColumns = http.expectOne('/api/templates/11/columns');
    http.expectOne('/api/templates').flush([{ id: 21, name: 'Obrazac firme 2' }]);
    fixture.detectChanges();

    // popis je uskladio odabir na obrazac nove firme i njegovi stupci su stigli
    http.expectOne('/api/templates/21/columns').flush([
      { columnKey: 'grad', columnType: 'string', codebookId: null, label: null, required: false, unique: false, defaultValue: null, readOnly: false }
    ]);
    fixture.detectChanges();

    // tek sad stiže 404 zastarjelog zahtjeva
    staleColumns.flush({ message: 'not found' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(component['error']()).toBeNull();
    expect(component['columns']()).toHaveLength(1);
  });

  it('greška za obrazac koji JEST odabran se i dalje prijavljuje', () => {
    localStorage.setItem('activeCompanyId', '2');
    localStorage.setItem('activeTemplateId', '21');

    const fixture = TestBed.createComponent(SchemaEditor);
    const component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    http.expectOne('/api/templates').flush([{ id: 21, name: 'Obrazac firme 2' }]);
    fixture.detectChanges();

    http.expectOne('/api/templates/21/columns')
      .flush({ message: 'puklo' }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(component['error']()).toBe('Dohvaćanje stupaca nije uspjelo.');
  });

  /**
   * Isti korijen, teža posljedica: stigne li popis obrazaca STARE firme zadnji, pod novom
   * bi se firmom prikazali tuđi obrasci - a odabir bi se uskladio s njima.
   */
  it('popis obrazaca prethodne firme se odbacuje', () => {
    localStorage.setItem('activeCompanyId', '1');

    const fixture = TestBed.createComponent(SchemaEditor);
    const component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    const tenant = TestBed.inject(TenantService);
    fixture.detectChanges();

    const forCompany1 = http.expectOne('/api/templates');

    // korisnik prebaci firmu prije nego što je prvi popis stigao
    tenant.setActive(2);
    fixture.detectChanges();
    http.expectOne('/api/templates').flush([{ id: 21, name: 'Obrazac firme 2' }]);
    fixture.detectChanges();
    http.expectOne('/api/templates/21/columns').flush([]);
    fixture.detectChanges();

    // tek sad stiže popis firme 1
    forCompany1.flush([{ id: 11, name: 'Obrazac firme 1' }]);
    fixture.detectChanges();

    expect(component['templates']()).toEqual([{ id: 21, name: 'Obrazac firme 2' }]);
    expect(component['activeTemplateId']()).toBe(21);
  });
});

/**
 * Zadana vrijednost šifrarničkog stupca je ŠIFRA, pa se bira iz popisa umjesto da se tipka.
 * Ručno upisan naziv stavke backend odbija - i to tek pri spremanju stupca.
 */
describe('SchemaEditor - zadana vrijednost iz šifrarnika', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('activeCompanyId', '2');
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
  });

  afterEach(() => {
    http.match('/api/codebooks').forEach((request) => request.flush([]));
    http.verify();
  });

  /** Otvoren ekran s praznim popisom obrazaca - dalje nas zanima samo dijalog stupca. */
  function openedEditor() {
    const fixture = TestBed.createComponent(SchemaEditor);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/templates').flush([]);
    fixture.detectChanges();
    return fixture;
  }

  it('nudi samo AKTIVNE šifre odabranog šifrarnika', () => {
    const fixture = openedEditor();
    const component = fixture.componentInstance;

    component['openColumnDialog']();
    component['onTypeChange']('codebook');
    component['onCodebookChange'](5);
    fixture.detectChanges();

    http.expectOne('/api/codebooks/5/items').flush([
      { id: 1, code: 'OPEN', name: 'Otvoren', active: true, sortOrder: 0 },
      { id: 2, code: 'OLD', name: 'Ukinuto', active: false, sortOrder: 1 }
    ]);
    fixture.detectChanges();

    expect(component['defaultValueOptions']().map((item) => item.code)).toEqual(['OPEN']);
  });

  /**
   * Inače bi otvaranje dijaloga nad takvim stupcem prikazalo prazno i tiho obrisalo
   * zadanu vrijednost pri prvom spremanju, iako je korisnik uopće nije dirao.
   */
  it('već spremljena isključena šifra ostaje vidljiva pri uređivanju', () => {
    const fixture = openedEditor();
    const component = fixture.componentInstance;

    component['startEditColumn']({
      columnKey: 'status',
      columnType: 'codebook',
      codebookId: 5,
      label: null,
      required: false,
      unique: false,
      defaultValue: 'OLD',
      readOnly: false,
      width: null,
      options: EMPTY_COLUMN_OPTIONS
    });
    fixture.detectChanges();

    http.expectOne('/api/codebooks/5/items').flush([
      { id: 1, code: 'OPEN', name: 'Otvoren', active: true, sortOrder: 0 },
      { id: 2, code: 'OLD', name: 'Ukinuto', active: false, sortOrder: 1 }
    ]);
    fixture.detectChanges();

    expect(component['defaultValueOptions']().map((item) => item.code)).toEqual(['OPEN', 'OLD']);
  });

  it('promjena šifrarnika briše zadanu vrijednost koja je pripadala prethodnom', () => {
    const fixture = openedEditor();
    const component = fixture.componentInstance;

    component['openColumnDialog']();
    component['onTypeChange']('codebook');
    component['onCodebookChange'](5);
    fixture.detectChanges();
    http.expectOne('/api/codebooks/5/items').flush([
      { id: 1, code: 'OPEN', name: 'Otvoren', active: true, sortOrder: 0 }
    ]);
    fixture.detectChanges();

    component['newDefaultValue'].set('OPEN');
    component['onCodebookChange'](6);
    fixture.detectChanges();
    http.expectOne('/api/codebooks/6/items').flush([]);
    fixture.detectChanges();

    expect(component['newDefaultValue']()).toBe('');
  });
});

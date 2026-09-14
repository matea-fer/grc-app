import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { SchemaEditor } from './schema-editor';
import { ColumnDefinition, EMPTY_COLUMN_OPTIONS } from '../column-definition/column-definition.model';

/**
 * Redoslijed stupaca u Editoru obrazaca.
 *
 * Redoslijed je oduvijek odlučivao kako izgleda tablica podataka (stupci se crtaju redom
 * kojim stoje u shemi), ali se mogao mijenjati samo brisanjem i ponovnim dodavanjem stupca -
 * dakle uz gubitak podataka. Ovdje se brani ono što je zbog toga bitno: da premještanje ne
 * dira ništa osim popisa, i da se ne pošalje prije nego je red gotov.
 */
describe('SchemaEditor - redoslijed stupaca', () => {
  let fixture: ComponentFixture<SchemaEditor>;
  let component: SchemaEditor;
  let http: HttpTestingController;

  function column(key: string): ColumnDefinition {
    return {
      columnKey: key, columnType: 'string', codebookId: null, label: null,
      required: false, unique: false, defaultValue: null, readOnly: false, width: null,
      options: { ...EMPTY_COLUMN_OPTIONS }
    };
  }

  function keys(): string[] {
    return component['columns']().map((c) => c.columnKey);
  }

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('activeCompanyId', '2');
    localStorage.setItem('activeTemplateId', '21');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    fixture = TestBed.createComponent(SchemaEditor);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    http.expectOne('/api/templates').flush([{ id: 21, name: 'Kontrole' }]);
    fixture.detectChanges();
    http.expectOne('/api/templates/21/columns')
      .flush([column('naziv'), column('vlasnik'), column('rok')]);
    fixture.detectChanges();
  });

  afterEach(() => {
    http.match('/api/codebooks').forEach((request) => request.flush([]));
    http.verify();
    localStorage.clear();
  });

  it('strelica premješta stupac, ali ništa ne šalje', () => {
    component['moveBy'](component['columns']()[2], -1);

    expect(keys()).toEqual(['naziv', 'rok', 'vlasnik']);
    // premještanje pet stupaca ne smije biti pet poziva ni pet redaka u dnevniku
    http.expectNone((r) => r.url.includes('/columns'));
    expect(component['orderChanged']()).toBe(true);
  });

  it('strelica na rubu ne radi ništa', () => {
    component['moveBy'](component['columns']()[0], -1);
    component['moveBy'](component['columns']()[2], 1);

    expect(keys()).toEqual(['naziv', 'vlasnik', 'rok']);
    expect(component['orderChanged']()).toBe(false);
  });

  it('povlačenje na drugi redak premješta stupac na to mjesto', () => {
    component['onDragStart'](component['columns']()[0]);
    // DragEvent u testnom okruženju ne postoji; komponenti treba samo preventDefault
    component['onDrop'](new Event('drop') as DragEvent, component['columns']()[2]);

    expect(keys()).toEqual(['vlasnik', 'rok', 'naziv']);
  });

  /** Šalju se SVI ključevi, da server može provjeriti da je skup ostao isti. */
  it('spremanje šalje cijeli redoslijed i preuzima odgovor servera', () => {
    component['moveBy'](component['columns']()[2], -1);

    component['saveOrder']();

    const request = http.expectOne((r) => r.url === '/api/templates/21/columns/order' && r.method === 'PUT');
    expect(request.request.body).toEqual({ columnKeys: ['naziv', 'rok', 'vlasnik'] });

    request.flush([column('naziv'), column('rok'), column('vlasnik')]);
    expect(component['orderChanged']()).toBe(false);
    expect(component['success']()).toContain('Redoslijed');
  });

  it('odustajanje vraća redoslijed sa servera, bez ijednog poziva', () => {
    component['moveBy'](component['columns']()[2], -1);

    component['cancelOrder']();

    expect(keys()).toEqual(['naziv', 'vlasnik', 'rok']);
    expect(component['orderChanged']()).toBe(false);
    http.expectNone((r) => r.url.includes('/columns'));
  });

  /**
   * Odbijenicu servera („netko je u međuvremenu dodao stupac") mora vidjeti korisnik -
   * inače bi mu popis ostao u stanju koje nigdje ne postoji.
   */
  it('odbijeno spremanje javlja poruku servera', () => {
    component['moveBy'](component['columns']()[2], -1);

    component['saveOrder']();
    http.expectOne((r) => r.url === '/api/templates/21/columns/order')
      .flush({ message: 'Redoslijed se ne poklapa sa stupcima obrasca - osvježite stranicu.' },
        { status: 400, statusText: 'Bad Request' });

    expect(component['error']()).toContain('ne poklapa');
    expect(component['savingOrder']()).toBe(false);
  });

  it('traka za spremanje se vidi tek kad je nešto premješteno', () => {
    expect(fixture.nativeElement.textContent).not.toContain('Spremi redoslijed');

    component['moveBy'](component['columns']()[2], -1);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Spremi redoslijed');
  });
});

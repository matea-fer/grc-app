import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { SchemaEditor } from './schema-editor';
import { EMPTY_COLUMN_OPTIONS } from '../column-definition/column-definition.model';

/**
 * Izmjena stupca koja zapisima UZIMA vrijednosti.
 *
 * Povod je stvarna šteta (12.08.2026.): promjena tipa i uzorka na stupcu `oib` ostavila je
 * 120 zapisa bez vrijednosti u jednom kliku - bez pitanja i bez traga. Zato backend takvu
 * izmjenu prvo odbije s brojem zahvaćenih zapisa, a Editor pita i poziv ponovi s potvrdom.
 *
 * Pitanje se prepoznaje po strojnoj oznaci `COLUMN_DATA_LOSS`, ne po statusu: 409 vraća i
 * duplikat naziva stupca, gdje „Nastaviti?" nema smisla.
 */
describe('SchemaEditor - potvrda gubitka vrijednosti', () => {
  let http: HttpTestingController;

  const existingColumn = {
    columnKey: 'oib',
    columnType: 'string',
    codebookId: null,
    label: 'OIB',
    required: false,
    unique: false,
    defaultValue: null,
    readOnly: false,
    width: null,
    options: { ...EMPTY_COLUMN_OPTIONS }
  };

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
    http.match('/api/codebooks').forEach((request) => request.flush([]));
    http.match('/api/templates/21/columns').forEach((request) => request.flush([]));
    http.verify();
    localStorage.clear();
  });

  /** Editor s jednim postojećim stupcem, otvorenim na uređivanje. */
  function editorEditingOib() {
    const fixture = TestBed.createComponent(SchemaEditor);
    const component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    http.expectOne('/api/templates').flush([{ id: 21, name: 'Obrazac' }]);
    fixture.detectChanges();
    http.expectOne('/api/templates/21/columns').flush([existingColumn]);
    fixture.detectChanges();

    component['startEditColumn'](component['columns']()[0]);
    // ista izmjena kao u stvarnom slučaju: tekst -> broj
    component['newType'].set('number');
    return { fixture, component };
  }

  /** Zahtjev izmjene stupca; vraća poslano tijelo. */
  function takeUpdate(): Record<string, unknown> {
    const request = http.expectOne(
      (candidate) => candidate.url === '/api/templates/21/columns/oib' && candidate.method === 'PUT'
    );
    const body = request.request.body as Record<string, unknown>;
    request.flush({ ...existingColumn, columnType: 'number' });
    return body;
  }

  /** Odgovor kojim backend traži potvrdu. */
  function refuseWithDataLoss(): void {
    http.expectOne(
      (candidate) => candidate.url === '/api/templates/21/columns/oib' && candidate.method === 'PUT'
    ).flush(
      { message: 'Ovom izmjenom 120 zapisa ostaje bez vrijednosti stupca "oib".', code: 'COLUMN_DATA_LOSS' },
      { status: 409, statusText: 'Conflict' }
    );
  }

  function withConfirm(answer: boolean, action: () => void): string | null {
    const original = window.confirm;
    let asked: string | null = null;
    window.confirm = (message?: string) => {
      asked = message ?? '';
      return answer;
    };
    try {
      action();
    } finally {
      window.confirm = original;
    }
    return asked;
  }

  it('prvi zahtjev ide bez potvrde - Editor ne pretpostavlja pristanak', () => {
    const { component } = editorEditingOib();

    component['submit']();

    expect(takeUpdate()['confirmDataLoss']).toBe(false);
  });

  it('na odbijenicu pita korisnika i poziv ponovi s potvrdom', () => {
    const { component } = editorEditingOib();

    const asked = withConfirm(true, () => {
      component['submit']();
      refuseWithDataLoss();
    });

    // pitanje nosi broj koji je izračunao server, a ne procjenu iz preglednika
    expect(asked).toContain('120');
    expect(takeUpdate()['confirmDataLoss']).toBe(true);
  });

  it('odustajanje ne šalje ništa i podaci ostaju nedirnuti', () => {
    const { component } = editorEditingOib();

    withConfirm(false, () => {
      component['submit']();
      refuseWithDataLoss();
    });

    http.expectNone((candidate) => candidate.method === 'PUT');
    expect(component['dialogError']()).toContain('nedirnuti');
  });

  /** Duplikat naziva je također 409, ali ondje pitanje nema smisla. */
  it('drugi 409 se samo ispiše, bez pitanja', () => {
    const { component } = editorEditingOib();

    const asked = withConfirm(true, () => {
      component['submit']();
      http.expectOne(
        (candidate) => candidate.url === '/api/templates/21/columns/oib' && candidate.method === 'PUT'
      ).flush({ message: 'Stupac "oib" već postoji.' }, { status: 409, statusText: 'Conflict' });
    });

    expect(asked).toBeNull();
    expect(component['dialogError']()).toBe('Stupac "oib" već postoji.');
  });
});

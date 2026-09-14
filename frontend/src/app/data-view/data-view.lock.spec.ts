import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { DataView } from './data-view';
import { EMPTY_COLUMN_OPTIONS } from '../column-definition/column-definition.model';
import { validTestToken } from '../auth/auth.test-utils';
/** Odgovor servera na dohvat zapisa - jedna stranica, kakvu ekran sada očekuje. */
function page(content: object[], totalElements = content.length) {
  return { content, page: 0, size: 50, totalElements, totalPages: Math.ceil(totalElements / 50) };
}

/** Zahtjev za zapisima razlikuje se od unosa po metodi - URL im je isti. */
const SURVEYS_GET = (url: string) => (r: { url: string; method: string }) => r.url === url && r.method === 'GET';
const SURVEYS_POST = (url: string) => (r: { url: string; method: string }) => r.url === url && r.method === 'POST';


/**
 * Gumb koji zaključava zapis.
 *
 * Isti gumb radi oba smjera, pa je pitanje koje se ovdje brani: radi li on ono što u tom
 * trenutku piše na njemu, i staje li pred korisnikom koji zapis ne smije otključati.
 * Sučelje pritom nije zaštita - backend odbija sam - ali gumb koji izgleda kao da radi,
 * a vraća grešku, gori je od gumba kojeg nema.
 */
describe('DataView - zaključavanje zapisa', () => {
  let component: DataView;
  let http: HttpTestingController;

  const schema = [
    {
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
    },
    {
      columnKey: 'status',
      columnType: 'button',
      codebookId: null,
      label: 'Status',
      required: false,
      unique: false,
      defaultValue: null,
      readOnly: false,
      width: null,
      options: { ...EMPTY_COLUMN_OPTIONS, buttonLabel: 'Pošalji na validaciju', buttonAction: 'lock' }
    }
  ];

  const unlockedRow = { id: 1, companyId: 2, templateId: 21, data: { oib: '111' }, locked: false, lockedAt: null, lockedBy: null };
  const lockedRow = {
    id: 1, companyId: 2, templateId: 21, data: { oib: '111' },
    locked: true, lockedAt: '2026-08-12T09:00:00Z', lockedBy: 'mhorvat'
  };

  /** Prijavljen korisnik zadane uloge - o njoj ovisi smije li se otključavati. */
  function signIn(role: 'USER' | 'TENANT_ADMIN'): void {
    localStorage.setItem('authToken', validTestToken());
    localStorage.setItem('authUser', JSON.stringify({ username: 'mhorvat', role, companyId: 2, companyName: 'Acme' }));
  }

  /** Ekran s jednim zapisom u zadanom stanju. */
  function open(row: object): void {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    const fixture = TestBed.createComponent(DataView);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    http.expectOne('/api/templates').flush([{ id: 21, name: 'Obrazac' }]);
    fixture.detectChanges();
    http.expectOne('/api/templates/21/columns').flush(schema);
    // prilozi se ne dohvaćaju - shema nema stupac tipa "file"
    http.expectOne(SURVEYS_GET('/api/templates/21/surveys')).flush(page([row]));
    fixture.detectChanges();
  }

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('activeCompanyId', '2');
    localStorage.setItem('activeTemplateId', '21');
  });

  afterEach(() => http.verify());

  function withConfirm(answer: boolean, action: () => void): void {
    const original = window.confirm;
    window.confirm = () => answer;
    try {
      action();
    } finally {
      window.confirm = original;
    }
  }

  it('klik na gumb zaključava zapis i redak se osvježava odgovorom servera', () => {
    signIn('USER');
    open(unlockedRow);

    withConfirm(true, () => component['onCellButton'](component['fields']()[1], component['surveys']()[0]));

    http.expectOne({ url: '/api/templates/21/surveys/1/lock', method: 'POST' }).flush(lockedRow);

    expect(component['surveys']()[0].locked).toBe(true);
    expect(component['surveys']()[0].lockedBy).toBe('mhorvat');
  });

  it('odustajanje od potvrde ne šalje ništa - zaključavanje se ne poništava jednim klikom', () => {
    signIn('USER');
    open(unlockedRow);

    withConfirm(false, () => component['onCellButton'](component['fields']()[1], component['surveys']()[0]));

    http.expectNone(() => true);
    expect(component['surveys']()[0].locked).toBe(false);
  });

  it('običnom korisniku gumb na zaključanom zapisu ne radi ništa, uz objašnjenje', () => {
    signIn('USER');
    open(lockedRow);

    withConfirm(true, () => component['onCellButton'](component['fields']()[1], component['surveys']()[0]));

    http.expectNone(() => true);
    expect(component['error']()).toBe('Zaključani zapis može otključati samo administrator.');
  });

  it('administrator firme otključava zapis', () => {
    signIn('TENANT_ADMIN');
    open(lockedRow);

    withConfirm(true, () => component['onCellButton'](component['fields']()[1], component['surveys']()[0]));

    http.expectOne({ url: '/api/templates/21/surveys/1/lock', method: 'DELETE' }).flush(unlockedRow);

    expect(component['surveys']()[0].locked).toBe(false);
  });

  /** Forma nad zaključanim zapisom bi se ispunila i pala tek na spremanju. */
  it('zaključan zapis se ne otvara na uređivanje ni brisanje', () => {
    signIn('TENANT_ADMIN');
    open(lockedRow);

    component['startEdit'](component['surveys']()[0]);
    withConfirm(true, () => component['deleteSurvey'](component['surveys']()[0]));

    expect(component['editingId']()).toBeNull();
    http.expectNone(() => true);
  });
});

import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { RecordHistoryDialog } from './record-history-dialog';
import { validTestToken } from '../auth/auth.test-utils';

/**
 * Dijalog „Povijest" je dokazni prikaz, pa se provjerava ono što bi ga učinilo neistinitim:
 * da se povijest dohvaća tek na otvaranje (a ne uz tablicu), i da se „nema vrijednosti"
 * vidi kao podatak, a ne kao prazna ćelija koja izgleda kao greška.
 */
describe('RecordHistoryDialog', () => {
  let http: HttpTestingController;

  const CHANGES = [
    {
      changedAt: '2026-08-10T11:00:38Z',
      username: 'mhorvat',
      columnKey: 'ocjena',
      columnLabel: 'Ocjena kontrole',
      oldValue: '3',
      newValue: '4'
    },
    {
      changedAt: '2026-08-10T10:00:00Z',
      username: 'mhorvat',
      columnKey: 'komentar',
      columnLabel: 'Komentar',
      oldValue: null,
      newValue: 'prvi unos'
    }
  ];

  function setup() {
    localStorage.setItem('authToken', validTestToken());
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'mhorvat', role: 'USER', companyId: 7, companyName: 'Acme' })
    );

    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    const fixture = TestBed.createComponent(RecordHistoryDialog);
    fixture.componentRef.setInput('templateId', 18);
    fixture.componentRef.setInput('surveyId', 42);
    http = TestBed.inject(HttpTestingController);
    return fixture;
  }

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('povijest se dohvaća s rute ugniježđene pod zapisom', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();

    const request = http.expectOne('/api/templates/18/surveys/42/history');
    expect(request.request.method).toBe('GET');
    request.flush(CHANGES);
  });

  it('prikazuje sve promjene i njihov broj', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne('/api/templates/18/surveys/42/history').flush(CHANGES);
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('Ocjena kontrole');
    expect(fixture.nativeElement.textContent).toContain('Broj zapisa: 2');
  });

  it('vrijednost koje nije bilo prikazuje se kao crtica, a ne kao prazna ćelija', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne('/api/templates/18/surveys/42/history').flush(CHANGES);
    fixture.detectChanges();

    const secondRow = fixture.nativeElement.querySelectorAll('tbody tr')[1];
    const cells = secondRow.querySelectorAll('td');
    // stupci: datum, korisnik, polje, stara, nova
    expect(cells[3].textContent.trim()).toBe('-');
    expect(cells[4].textContent.trim()).toBe('prvi unos');
  });

  it('zapis bez ijedne promjene daje poruku, a ne praznu tablicu', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne('/api/templates/18/surveys/42/history').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('još nema zabilježenih promjena');
    expect(fixture.nativeElement.querySelector('tbody')).toBeNull();
  });
});

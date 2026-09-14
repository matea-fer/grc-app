import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { RelatedRecordsDialog } from './related-records-dialog';
import { RelatedGroup } from '../survey/survey.model';
import { validTestToken } from '../auth/auth.test-utils';

/**
 * Dijalog „Povezani zapisi”.
 *
 * Dvije stvari koje ga čine ispravnim, a lako se pokvare:
 *
 * 1. Dohvat je LIJEN u dva koraka - na otvaranje ide samo pitanje „tko me dodiruje”, a zapisi
 *    tek za odabranu skupinu. Da se učita sve odjednom, klik na gumb bi povukao zapise svih
 *    obrazaca koji zapis dodiruju, a gleda se jedan.
 * 2. Dva smjera se traže na DVA načina, i to nije nedosljednost: dolazni je filtar („tko
 *    pokazuje na mene”), izlazni je popis id-eva koji već stoje u našem retku.
 */
describe('RelatedRecordsDialog', () => {
  let http: HttpTestingController;

  const INCOMING: RelatedGroup = {
    templateId: 30,
    templateName: 'Rizici',
    columnKey: 'proces',
    columnLabel: 'Proces',
    direction: 'INCOMING',
    total: 3,
    recordIds: []
  };

  const OUTGOING: RelatedGroup = {
    templateId: 40,
    templateName: 'Osobe',
    columnKey: 'vlasnik',
    columnLabel: 'Vlasnik',
    direction: 'OUTGOING',
    total: 2,
    recordIds: [7, 9]
  };

  const EMPTY_PAGE = { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 };

  function setup() {
    localStorage.setItem('authToken', validTestToken());
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'mhorvat', role: 'USER', companyId: 7, companyName: 'Acme' })
    );

    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    const fixture = TestBed.createComponent(RelatedRecordsDialog);
    fixture.componentRef.setInput('templateId', 20);
    fixture.componentRef.setInput('recordId', 12);
    http = TestBed.inject(HttpTestingController);
    return fixture;
  }

  /** Odgovori na dohvat sheme i zapisa koje dijalog pošalje za odabranu skupinu. */
  function answerGroupLoad(templateId: number) {
    http.expectOne(`/api/templates/${templateId}/columns`).flush([]);
    return http.expectOne((r) => r.url === `/api/templates/${templateId}/surveys`);
  }

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('na otvaranje pita samo tko dodiruje zapis, bez ijednog dohvata zapisa', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();

    const request = http.expectOne('/api/templates/20/surveys/12/related');
    expect(request.request.method).toBe('GET');
    request.flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('ne pokazuje nijedan zapis');
  });

  it('dolazna skupina se traži filtrom po stupcu koji pokazuje ovamo', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne('/api/templates/20/surveys/12/related').flush([INCOMING]);
    await fixture.whenStable();

    const request = answerGroupLoad(30);
    expect(request.request.params.getAll('filter')).toEqual(['proces:12']);
    expect(request.request.params.has('ids')).toBe(false);
    request.flush(EMPTY_PAGE);
  });

  /** Izlazni smjer ne pita ništa o sadržaju - id-evi su već poznati iz našeg retka. */
  it('izlazna skupina se traži po id-evima, ne filtrom', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne('/api/templates/20/surveys/12/related').flush([OUTGOING]);
    await fixture.whenStable();

    const request = answerGroupLoad(40);
    expect(request.request.params.get('ids')).toBe('7,9');
    // getAll vraća null kad parametra uopće nema - a upravo to se ovdje i traži
    expect(request.request.params.getAll('filter')).toBeNull();
    request.flush(EMPTY_PAGE);
  });

  it('oba smjera stoje odvojeno, sa svojim naslovima', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne('/api/templates/20/surveys/12/related').flush([OUTGOING, INCOMING]);
    await fixture.whenStable();
    // prva skupina se otvara sama - ovdje je to izlazna
    answerGroupLoad(40).flush(EMPTY_PAGE);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Pokazuje na');
    expect(text).toContain('Pokazuju na njega');
    expect(text).toContain('Vlasnik');
    expect(text).toContain('Rizici');
  });

  /**
   * Bez ovoga je dijalog slijepa ulica: vidi se „#12", ali se taj zapis u tablici ne može ni
   * naći (broj zapisa nije stupac sheme, pa se po njemu ne da filtrirati), a bez retka u
   * tablici nema uređivanja, brisanja ni priloga. Upravo to je bila njezina primjedba nakon
   * ručne provjere 13.08.2026.
   */
  it('klik na redak traži prikaz tog zapisa u tablici', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne('/api/templates/20/surveys/12/related').flush([INCOMING]);
    await fixture.whenStable();
    answerGroupLoad(30).flush({
      ...EMPTY_PAGE,
      content: [{ id: 5, companyId: 7, templateId: 30, data: { naziv: 'Rizik A' }, locked: false }],
      totalElements: 1,
      totalPages: 1
    });
    fixture.detectChanges();

    const opened: { templateId: number; recordId: number }[] = [];
    fixture.componentInstance.opened.subscribe((event) => opened.push(event));
    fixture.nativeElement.querySelector('tbody tr').click();

    // obrazac dolazi iz samog zapisa, ne iz odabrane skupine
    expect(opened).toEqual([{ templateId: 30, recordId: 5 }]);
  });

  /** Kartica bez klika koji nešto otvara je šum - prva skupina se zato otvara sama. */
  it('prva skupina se otvara bez klika', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne('/api/templates/20/surveys/12/related').flush([INCOMING]);
    await fixture.whenStable();

    answerGroupLoad(30).flush(EMPTY_PAGE);
    fixture.detectChanges();

    expect(fixture.componentInstance['activeGroup']()).toEqual(INCOMING);
  });
});

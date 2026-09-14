import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { vi } from 'vitest';

import { RecordPickerDialog } from './record-picker-dialog';
import { EMPTY_COLUMN_OPTIONS } from '../column-definition/column-definition.model';
import { validTestToken } from '../auth/auth.test-utils';

/**
 * Dijalog za odabir povezanog zapisa.
 *
 * Provjerava se ono što bi ga učinilo neupotrebljivim na obrascu s tisuću zapisa: da pretraga
 * ide na SERVER (a ne filtrira dohvaćeno), da se upit šalje tek kad tipkanje stane, i da
 * promjena upita vraća na prvu stranicu - inače bi ostala tražena stranica prošlog upita, na
 * kojoj novih rezultata najčešće nema.
 */
describe('RecordPickerDialog', () => {
  let http: HttpTestingController;

  /** Stupci ciljanog obrasca - dijalog ih traži zasebno, da može prikazati cijeli redak. */
  const COLUMNS = ['naziv', 'vlasnik', 'grad'].map((key) => ({
    columnKey: key, columnType: 'string', codebookId: null, label: key.toUpperCase(),
    required: false, unique: false, defaultValue: null, readOnly: false, width: null,
    options: { ...EMPTY_COLUMN_OPTIONS }
  }));

  const PAGE = {
    content: [
      { id: 12, label: 'Nabava', data: { naziv: 'Nabava', vlasnik: 'Ana', grad: 'Zagreb' } },
      { id: 15, label: 'Prodaja', data: { naziv: 'Prodaja', vlasnik: 'Ivo', grad: 'Split' } }
    ],
    page: 0,
    size: 50,
    totalElements: 2,
    totalPages: 1
  };

  function setup(multiple = false, selected: number[] = []) {
    localStorage.setItem('authToken', validTestToken());
    localStorage.setItem(
      'authUser',
      JSON.stringify({ username: 'mhorvat', role: 'USER', companyId: 7, companyName: 'Acme' })
    );

    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    const fixture = TestBed.createComponent(RecordPickerDialog);
    fixture.componentRef.setInput('templateId', 20);
    fixture.componentRef.setInput('displayKey', 'naziv');
    fixture.componentRef.setInput('multiple', multiple);
    fixture.componentRef.setInput('selected', selected);
    http = TestBed.inject(HttpTestingController);
    return fixture;
  }

  /**
   * Shema ciljanog obrasca stiže vlastitim zahtjevom. Testovi koji je ne provjeravaju je samo
   * namire - inače bi verify() u svakom od njih prijavio otvoren zahtjev.
   */
  function answerColumns(columns: object[] = COLUMNS): void {
    http.match((r) => r.url === '/api/templates/20/columns').forEach((request) => request.flush(columns));
  }

  beforeEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  afterEach(() => {
    answerColumns();
    http.verify();
    localStorage.clear();
  });

  it('ponuda se dohvaća s rute obrasca, uz stupac za naziv', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();

    const request = http.expectOne((r) => r.url === '/api/templates/20/options');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('displayKey')).toBe('naziv');
    expect(request.request.params.get('page')).toBe('0');
    // bez upisanog teksta se `q` ne šalje - server bi prazan upit ionako zanemario
    expect(request.request.params.has('q')).toBe(false);
    request.flush(PAGE);
  });

  it('prikazuje broj zapisa uz naziv', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/templates/20/options').flush(PAGE);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Nabava');
    expect(fixture.nativeElement.textContent).toContain('12');
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(2);
  });

  /**
   * Jedan stupac često ne razlikuje dva zapisa („Nabava" i „Nabava" iz različitih godina), pa
   * se u dijalogu vidi cijeli redak.
   */
  it('pokazuje cijeli redak ciljanog obrasca, ne samo stupac za naziv', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/templates/20/options').flush(PAGE);
    answerColumns();
    fixture.detectChanges();

    const headers = Array.from(fixture.nativeElement.querySelectorAll('thead th'))
      .map((th) => (th as HTMLElement).textContent?.trim());
    expect(headers).toEqual(['', '#', 'NAZIV', 'VLASNIK', 'GRAD']);
    expect(fixture.nativeElement.textContent).toContain('Ana');
    expect(fixture.nativeElement.textContent).toContain('Zagreb');
  });

  /** Odabirom se sprema veza koja se POSLIJE prikazuje tim stupcem - mora se znati kojim. */
  it('stupac koji ide u ćeliju je istaknut', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/templates/20/options').flush(PAGE);
    answerColumns();
    fixture.detectChanges();

    const marked = Array.from(fixture.nativeElement.querySelectorAll('thead .picker-display'))
      .map((th) => (th as HTMLElement).textContent?.trim());
    expect(marked).toEqual(['NAZIV']);
    // i u svakom retku, ne samo u zaglavlju
    expect(fixture.nativeElement.querySelectorAll('tbody .picker-display').length).toBe(2);
  });

  /**
   * Stupac za naziv je zajamcen: kad bi ispao izvan granice broja stupaca, dijalog bi skrivao
   * bas ono sto odabirom ulazi u celiju.
   */
  it('stupac za naziv se prikaže i kad je u shemi daleko iza', async () => {
    const many = Array.from({ length: 8 }, (_, i) => ({
      columnKey: `s${i}`, columnType: 'string', codebookId: null, label: `S${i}`,
      required: false, unique: false, defaultValue: null, readOnly: false, width: null,
      options: { ...EMPTY_COLUMN_OPTIONS }
    }));
    many[7].columnKey = 'naziv';
    many[7].label = 'NAZIV';

    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/templates/20/options').flush(PAGE);
    answerColumns(many);
    fixture.detectChanges();

    const headers = Array.from(fixture.nativeElement.querySelectorAll('thead th'))
      .map((th) => (th as HTMLElement).textContent?.trim());
    expect(headers).toContain('NAZIV');
    // granica se postuje - dva su sluzbena stupca (odabir i broj) plus sest iz sheme
    expect(headers).toHaveLength(8);
  });

  /**
   * Bez sheme se ne zna koji stupci postoje, ali se zna naziv - i on ostaje, onako kako je
   * dijalog izgledao i prije. Inače bi se vidjeli samo brojevi zapisa, po kojima se ne bira
   * ništa; upravo je to i pokazao zatečeni test kad je shema izostala.
   */
  it('bez sheme ostaje naziv, a ne samo broj zapisa', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/templates/20/options').flush(PAGE);
    http.match((r) => r.url === '/api/templates/20/columns')
      .forEach((request) => request.flush({ message: 'puklo' }, { status: 500, statusText: 'Server Error' }));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('12');
    expect(fixture.nativeElement.textContent).toContain('Nabava');
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(2);
  });

  /**
   * Upisani tekst mora završiti u zahtjevu, a ne u filtriranju već dohvaćenog: dohvaćeno je
   * samo prva stranica, pa bi pretraga "u pregledniku" tražila po pedeset od tisuću zapisa.
   */
  it('pretraga ide na server, s odgodom nakon tipkanja', async () => {
    vi.useFakeTimers();
    try {
      const fixture = setup();
      fixture.detectChanges();
      await fixture.whenStable();
      http.expectOne((r) => r.url === '/api/templates/20/options').flush(PAGE);

      fixture.componentInstance['onSearch']('nab');
      // prije isteka odgode ne smije biti novog zahtjeva
      http.expectNone((r) => r.url === '/api/templates/20/options');

      vi.advanceTimersByTime(300);
      fixture.detectChanges();
      await fixture.whenStable();

      const request = http.expectOne((r) => r.url === '/api/templates/20/options');
      expect(request.request.params.get('q')).toBe('nab');
      request.flush(PAGE);
    } finally {
      vi.useRealTimers();
    }
  });

  it('promjena upita vraća na prvu stranicu', async () => {
    vi.useFakeTimers();
    try {
      const fixture = setup();
      fixture.detectChanges();
      await fixture.whenStable();
      http.expectOne((r) => r.url === '/api/templates/20/options').flush(PAGE);

      fixture.componentInstance['goToPage'](3);
      fixture.detectChanges();
      await fixture.whenStable();
      http.expectOne((r) => r.params.get('page') === '3').flush(PAGE);

      fixture.componentInstance['onSearch']('nab');
      vi.advanceTimersByTime(300);
      fixture.detectChanges();
      await fixture.whenStable();

      const request = http.expectOne((r) => r.url === '/api/templates/20/options');
      expect(request.request.params.get('page')).toBe('0');
      request.flush(PAGE);
    } finally {
      vi.useRealTimers();
    }
  });

  /** Jedna veza - nema se što više birati, pa odabir odmah i potvrđuje. */
  it('kod jedne veze odabir odmah potvrđuje', async () => {
    const fixture = setup();
    const confirmed: number[][] = [];
    fixture.componentInstance.confirmed.subscribe((ids) => confirmed.push(ids));
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/templates/20/options').flush(PAGE);

    fixture.componentInstance['toggle']({ id: 15, label: 'Prodaja' });

    expect(confirmed).toEqual([[15]]);
  });

  /** Više veza - odabir se skuplja i potvrđuje tek na „Gotovo". */
  it('kod više veza odabir se skuplja do potvrde', async () => {
    const fixture = setup(true, [12]);
    const confirmed: number[][] = [];
    fixture.componentInstance.confirmed.subscribe((ids) => confirmed.push(ids));
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/templates/20/options').flush(PAGE);

    fixture.componentInstance['toggle']({ id: 15, label: 'Prodaja' });
    expect(confirmed).toEqual([]);

    fixture.componentInstance['confirm']();
    expect(confirmed).toEqual([[12, 15]]);
  });

  it('prazan obrazac daje poruku, a ne praznu tablicu bez objašnjenja', async () => {
    const fixture = setup();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/templates/20/options')
      .flush({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('još nema zapisa');
  });
});

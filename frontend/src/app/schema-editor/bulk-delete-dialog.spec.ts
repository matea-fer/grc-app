import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { BulkDeleteDialog } from './bulk-delete-dialog';

/**
 * Dijalog skupnog brisanja.
 *
 * Najvažnije što se ovdje brani nije izgled nego jedno pravilo: **promjena odabira baca
 * izračunatu najavu**. Bez toga bi se moglo potvrditi brisanje izračunato za DRUGI odabir -
 * korisnica označi još jedan obrazac i klikne „Obriši", a ono što je pročitala odnosi se na
 * ono što je bilo prije. Brisanje je nepovratno i odnosi zapise, pa je to najskuplja moguća
 * greška u ovom ekranu, a na zaslonu se ne bi vidjela.
 */
describe('BulkDeleteDialog', () => {
  let fixture: ComponentFixture<BulkDeleteDialog>;
  let component: BulkDeleteDialog;
  let http: HttpTestingController;

  const PREVIEW = '/api/templates/bulk-delete/preview';
  const DELETE = '/api/templates/bulk-delete';

  function plan(blockers: string[] = []) {
    return {
      templates: [{ id: 20, name: 'Odgovorne osobe', recordCount: 12, attachmentCount: 1 }],
      blockers
    };
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    fixture = TestBed.createComponent(BulkDeleteDialog);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('templates', [
      { id: 20, name: 'Odgovorne osobe' },
      { id: 21, name: 'Rizici' }
    ]);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  function chooseAndPreview(ids: number[], blockers: string[] = []) {
    ids.forEach((id) => component['toggle'](id));
    component['showPlan']();
    http.expectOne(PREVIEW).flush(plan(blockers));
  }

  it('najava se traži za točno one obrasce koji su označeni', () => {
    component['toggle'](20);
    component['toggle'](21);
    component['showPlan']();

    const request = http.expectOne(PREVIEW);
    expect(request.request.body).toEqual({ templateIds: [20, 21] });
    request.flush(plan());
  });

  /** Srž ovog testa - vidi opis razreda. */
  it('promjena odabira baca izračunatu najavu', () => {
    chooseAndPreview([20]);
    expect(component['plan']()).not.toBeNull();

    component['toggle'](21);

    expect(component['plan']()).toBeNull();
  });

  it('odznačavanje jednako baca najavu', () => {
    chooseAndPreview([20, 21]);

    component['toggle'](21);

    expect(component['plan']()).toBeNull();
  });

  /**
   * Prepreka znači da brisanje nije moguće. Gumb se tada ne prikazuje (vidi predložak), pa
   * ovdje se brani ono što je ispod njega: da se poziv brisanja ne pošalje.
   */
  it('brisanje se ne šalje dok postoji prepreka', () => {
    chooseAndPreview([20], ['"Rizici" (stupac "odgovorna") pokazuje na "Odgovorne osobe"']);

    expect(component['plan']()!.blockers).toHaveLength(1);
    http.expectNone(DELETE);
  });

  it('uspješno brisanje javlja koliko ih je otišlo i zatvara dijalog', () => {
    const deleted: number[] = [];
    const closed: boolean[] = [];
    component.deleted.subscribe((count) => deleted.push(count));
    component.closed.subscribe(() => closed.push(true));

    chooseAndPreview([20, 21]);
    component['apply']();
    http.expectOne(DELETE).flush(null);

    expect(deleted).toEqual([2]);
    expect(closed).toHaveLength(1);
  });

  /** Poruka poslužitelja je jedino mjesto koje kaže ŠTO je zapelo, pa se ne smije zamijeniti. */
  it('poruka poslužitelja se prikazuje umjesto zamjenske', () => {
    chooseAndPreview([20]);

    component['apply']();
    http.expectOne(DELETE).flush(
      { message: 'Obrasci se ne mogu obrisati jer na njih pokazuju stupci.' },
      { status: 409, statusText: 'Conflict' }
    );

    expect(component['error']()).toContain('ne mogu obrisati');
  });
});

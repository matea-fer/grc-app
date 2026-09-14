import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { TemplateTransferDialog } from './template-transfer-dialog';

/**
 * Dijalog prijenosa obrazaca.
 *
 * Dvije stvari koje se ovdje brane, obje otkrivene tek u radu:
 *
 * 1. **Promjena odabira baca izračunatu najavu** - inače bi se potvrdilo ono što je
 *    izračunato prije promjene, a najava je jedino mjesto na kojem se posljedice vide.
 * 2. **Prepoznavanje ponovljenog prijenosa** - kad SVAKI označeni obrazac u ciljnoj firmi
 *    već postoji pod istim nazivom, to gotovo nikad nije namjera nego drugi klik na istu
 *    radnju. Prvi put kad je prijenos pušten u rad, upravo se to i dogodilo.
 */
describe('TemplateTransferDialog', () => {
  let fixture: ComponentFixture<TemplateTransferDialog>;
  let component: TemplateTransferDialog;
  let http: HttpTestingController;

  const GOOGLE = 1;
  const CARNET = 2;
  const TEMPLATES_OF = (id: number) => `/api/template-transfer/templates?companyId=${id}`;
  const PREVIEW = '/api/template-transfer/preview';
  const TRANSFER = '/api/template-transfer';

  function plan(renamed: boolean[]) {
    return {
      templates: renamed.map((wasRenamed, i) => ({
        sourceId: 10 + i,
        sourceName: `Obrazac ${i}`,
        targetName: wasRenamed ? `Obrazac ${i} (kopija)` : `Obrazac ${i}`,
        renamed: wasRenamed,
        columnCount: 3
      })),
      codebooks: [],
      brokenReferences: []
    };
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    fixture = TestBed.createComponent(TemplateTransferDialog);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('companies', [
      { id: GOOGLE, name: 'Google' },
      { id: CARNET, name: 'Carnet' }
    ]);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  /** Odabir izvora povlači njegove obrasce; ciljna firma se bira zasebno. */
  function selectSourceAndTemplates(ids: number[]) {
    component['onSourceChange'](GOOGLE);
    http.expectOne(TEMPLATES_OF(GOOGLE)).flush([
      { id: 10, name: 'Obrazac 0' },
      { id: 11, name: 'Obrazac 1' }
    ]);
    component['onTargetChange'](CARNET);
    ids.forEach((id) => component['toggle'](id));
  }

  it('obrasci se dohvaćaju za odabranu izvornu firmu, ne za aktivnu', () => {
    component['onSourceChange'](GOOGLE);

    http.expectOne(TEMPLATES_OF(GOOGLE)).flush([]);
  });

  it('najava nosi obje firme i točno označene obrasce', () => {
    selectSourceAndTemplates([10, 11]);

    component['showPlan']();

    const request = http.expectOne(PREVIEW);
    expect(request.request.body).toEqual({
      sourceCompanyId: GOOGLE,
      targetCompanyId: CARNET,
      templateIds: [10, 11]
    });
    request.flush(plan([false, false]));
  });

  it('promjena odabira baca izračunatu najavu', () => {
    selectSourceAndTemplates([10]);
    component['showPlan']();
    http.expectOne(PREVIEW).flush(plan([false]));
    expect(component['plan']()).not.toBeNull();

    component['toggle'](11);

    expect(component['plan']()).toBeNull();
  });

  /** Promjena ciljne firme mijenja sve zaključke najave, pa je ni ona ne smije preživjeti. */
  it('promjena ciljne firme baca najavu', () => {
    selectSourceAndTemplates([10]);
    component['showPlan']();
    http.expectOne(PREVIEW).flush(plan([false]));

    component['onTargetChange'](GOOGLE);

    expect(component['plan']()).toBeNull();
  });

  // ===================== ponovljeni prijenos =====================

  it('kad su SVI obrasci preimenovani, to se prepoznaje kao ponovljeni prijenos', () => {
    selectSourceAndTemplates([10, 11]);
    component['showPlan']();
    http.expectOne(PREVIEW).flush(plan([true, true]));

    expect(component['looksLikeRepeat']()).toBe(true);
  });

  /** Jedan preimenovan je uredna stvar - netko svjesno radi drugu inačicu. */
  it('preimenovan samo jedan obrazac nije ponovljeni prijenos', () => {
    selectSourceAndTemplates([10, 11]);
    component['showPlan']();
    http.expectOne(PREVIEW).flush(plan([true, false]));

    expect(component['looksLikeRepeat']()).toBe(false);
  });

  it('bez najave se ništa ne tvrdi', () => {
    expect(component['looksLikeRepeat']()).toBe(false);
  });

  // ===================== izvršenje =====================

  it('uspješan prijenos javlja izvještaj i zatvara dijalog', () => {
    const reports: unknown[] = [];
    const closed: boolean[] = [];
    component.transferred.subscribe((report) => reports.push(report));
    component.closed.subscribe(() => closed.push(true));

    selectSourceAndTemplates([10]);
    component['showPlan']();
    http.expectOne(PREVIEW).flush(plan([false]));

    component['apply']();
    http.expectOne(TRANSFER).flush(plan([false]));

    expect(reports).toHaveLength(1);
    expect(closed).toHaveLength(1);
  });

  it('ista firma s obje strane se ne nudi kao cilj', () => {
    component['onSourceChange'](GOOGLE);
    http.expectOne(TEMPLATES_OF(GOOGLE)).flush([]);

    expect(component['targetChoices']().map((c) => c.id)).toEqual([CARNET]);
  });
});

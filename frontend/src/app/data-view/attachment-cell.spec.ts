import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AttachmentCell } from './attachment-cell';
import { Attachment } from '../attachment/attachment.model';
import { EMPTY_COLUMN_OPTIONS } from '../column-definition/column-definition.model';
import { SchemaField } from './field';

/**
 * Ćelija za priloge.
 *
 * Najvažnije pravilo koje se ovdje brani: prilog se veže uz ZAPIS, pa se prije nego zapis
 * postoji ne može ni ponuditi. Uz to, prilog nije vrijednost zapisa - ne šalje se s ostalim
 * poljima nego vlastitim pozivom, pa se provjerava i da poziv ide na pravu adresu.
 */
describe('AttachmentCell', () => {
  let http: HttpTestingController;

  const TEMPLATE_ID = 21;
  const SURVEY_ID = 5;

  function field(buttonLabel: string | null = null): SchemaField {
    return {
      key: 'ponuda',
      label: 'Ponuda',
      type: 'file',
      codebookId: null,
      items: [],
      required: false,
      unique: false,
      defaultValue: null,
      readOnly: false,
      width: null,
      options: { ...EMPTY_COLUMN_OPTIONS, buttonLabel }
    };
  }

  function attachment(id: number, fileName: string): Attachment {
    return {
      id,
      surveyId: SURVEY_ID,
      columnKey: 'ponuda',
      fileName,
      contentType: 'application/pdf',
      sizeBytes: 2048,
      uploadedBy: 'ana',
      uploadedAt: '2026-08-06T10:00:00Z'
    };
  }

  function setup(surveyId: number | null, attachments: Attachment[] = [], buttonLabel: string | null = null) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    const fixture = TestBed.createComponent(AttachmentCell);
    http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('templateId', TEMPLATE_ID);
    fixture.componentRef.setInput('surveyId', surveyId);
    fixture.componentRef.setInput('field', field(buttonLabel));
    fixture.componentRef.setInput('attachments', attachments);
    fixture.detectChanges();
    return fixture;
  }

  /** Polje za odabir datoteke, onakvo kakvo komponenta dobiva iz predloška. */
  function picked(file: File | null): HTMLInputElement {
    return { files: file ? [file] : [], value: 'C:\\fakepath\\x' } as unknown as HTMLInputElement;
  }

  afterEach(() => http.verify());

  it('bez spremljenog zapisa ne nudi prilaganje nego uputu', () => {
    const fixture = setup(null);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Spremite zapis');
    expect((fixture.nativeElement as HTMLElement).querySelector('input[type="file"]')).toBeNull();

    fixture.destroy();
  });

  it('prilaganje ide kao multipart na adresu zapisa i stupca', () => {
    const fixture = setup(SURVEY_ID);
    const instance = fixture.componentInstance;
    let changed = 0;
    instance.changed.subscribe(() => changed++);

    instance['onFilePicked'](picked(new File(['sadrzaj'], 'ponuda.pdf')));

    const request = http.expectOne(`/api/templates/21/surveys/5/attachments/ponuda`);
    expect(request.request.method).toBe('POST');
    // FormData, a ne JSON: Content-Type postavlja preglednik jer mora dodati granicu dijelova
    expect(request.request.body instanceof FormData).toBe(true);
    request.flush(attachment(77, 'ponuda.pdf'));

    // popis priloga drži ekran Podaci, pa mu se promjena javlja
    expect(changed).toBe(1);
    expect(instance['busy']()).toBe(false);

    fixture.destroy();
  });

  it('neuspjelo prilaganje javlja poruku sa servera i ne javlja promjenu', () => {
    const fixture = setup(SURVEY_ID);
    const instance = fixture.componentInstance;
    let changed = 0;
    instance.changed.subscribe(() => changed++);

    instance['onFilePicked'](picked(new File(['x'], 'veliko.pdf')));

    http.expectOne(`/api/templates/21/surveys/5/attachments/ponuda`)
      .flush({ message: 'Datoteka je prevelika (najviše 5 MB).' }, { status: 400, statusText: 'Bad Request' });

    expect(instance['error']()).toContain('prevelika');
    expect(instance['busy']()).toBe(false);
    expect(changed).toBe(0);

    fixture.destroy();
  });

  it('odustajanje od odabira ne šalje ništa', () => {
    const fixture = setup(SURVEY_ID);

    fixture.componentInstance['onFilePicked'](picked(null));

    http.expectNone(`/api/templates/21/surveys/5/attachments/ponuda`);

    fixture.destroy();
  });

  it('brisanje traži potvrdu i javlja promjenu', () => {
    const fixture = setup(SURVEY_ID, [attachment(77, 'ponuda.pdf')]);
    const instance = fixture.componentInstance;
    let changed = 0;
    instance.changed.subscribe(() => changed++);

    const originalConfirm = window.confirm;
    window.confirm = () => true;
    instance['remove'](attachment(77, 'ponuda.pdf'));
    window.confirm = originalConfirm;

    const request = http.expectOne('/api/templates/21/attachments/77');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);

    expect(changed).toBe(1);

    fixture.destroy();
  });

  it('odbijena potvrda ne briše ništa', () => {
    const fixture = setup(SURVEY_ID, [attachment(77, 'ponuda.pdf')]);

    const originalConfirm = window.confirm;
    window.confirm = () => false;
    fixture.componentInstance['remove'](attachment(77, 'ponuda.pdf'));
    window.confirm = originalConfirm;

    http.expectNone('/api/templates/21/attachments/77');

    fixture.destroy();
  });

  it('natpis gumba dolazi iz sheme, uz razuman zadani', () => {
    const withLabel = setup(SURVEY_ID, [], 'Priloži ponudu');
    expect(withLabel.componentInstance['buttonLabel']()).toBe('Priloži ponudu');
    withLabel.destroy();

    const withoutLabel = setup(SURVEY_ID);
    expect(withoutLabel.componentInstance['buttonLabel']()).toBe('Učitaj dokument');
    withoutLabel.destroy();
  });
});

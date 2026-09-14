import { ComponentFixture, TestBed } from '@angular/core/testing';

import { RecordLinksDialog } from './record-links-dialog';
import { RecordLink } from './field';

/**
 * Dijalog s poveznicama zapisa.
 *
 * Dijalog ništa ne sprema - vrijednost prima izvana i vraća promijenjenu. Zato se ovdje
 * provjerava upravo to: što javi van, i što odbije prije nego išta javi.
 */
describe('RecordLinksDialog', () => {
  let fixture: ComponentFixture<RecordLinksDialog>;
  let component: RecordLinksDialog;
  let emitted: RecordLink[][];

  function open(links: RecordLink[], readOnly = false): void {
    fixture = TestBed.createComponent(RecordLinksDialog);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('links', links);
    fixture.componentRef.setInput('readOnly', readOnly);
    emitted = [];
    component.changed.subscribe((value) => emitted.push(value));
    fixture.detectChanges();
  }

  function type(url: string, name = ''): void {
    component['newUrl'].set(url);
    component['newName'].set(name);
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
  });

  it('dodana poveznica javlja cijeli novi popis, s nazivom', () => {
    open([{ url: 'https://a' }]);

    type('https://intranet/politika.pdf', 'Politika sigurnosti');
    component['add']();

    expect(emitted).toEqual([[
      { url: 'https://a' },
      { url: 'https://intranet/politika.pdf', name: 'Politika sigurnosti' }
    ]]);
  });

  /** Naziv je neobavezan - tjerati na njega značilo bi izmišljene nazive. */
  it('bez naziva se sprema samo adresa', () => {
    open([]);

    type('https://intranet/x');
    component['add']();

    expect(emitted).toEqual([[{ url: 'https://intranet/x' }]]);
  });

  /**
   * Adresu upisuje jedan korisnik, a klikne je drugi. Bez ove granice bi „javascript:…"
   * upisan u redak postao način da se izvrši kod kod kolege koji zapis otvori. Backend
   * odbija isto - ovo je samo brži odgovor.
   */
  it('adresa koja nije http(s) se ne prima', () => {
    open([]);

    type('javascript:alert(1)');
    component['add']();

    expect(emitted).toEqual([]);
    expect(component['error']()).toContain('http://');
  });

  it('ista adresa se ne dodaje dvaput', () => {
    open([{ url: 'https://a' }]);

    type('https://a');
    component['add']();

    expect(emitted).toEqual([]);
    expect(component['error']()).toContain('već');
  });

  it('uklanjanje javlja popis bez te poveznice', () => {
    open([{ url: 'https://a' }, { url: 'https://b' }]);

    component['remove'](0);

    expect(emitted).toEqual([[{ url: 'https://b' }]]);
  });

  it('nakon dodavanja se polja prazne - sljedeća se upisuje odmah', () => {
    open([]);

    type('https://a', 'Prva');
    component['add']();

    expect(component['newUrl']()).toBe('');
    expect(component['newName']()).toBe('');
  });

  /** Zaključan zapis se čita, ali ne mijenja - isto pravilo kao kod priloga. */
  it('nad zaključanim zapisom nema ni dodavanja ni uklanjanja', () => {
    open([{ url: 'https://a', name: 'Dokaz' }], true);

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Dokaz');
    expect(text).toContain('zaključan');
    expect(fixture.nativeElement.querySelector('.link-remove')).toBeNull();
    expect(fixture.nativeElement.querySelector('.link-add')).toBeNull();
  });

  it('prazan popis to i kaže', () => {
    open([]);

    expect(fixture.nativeElement.textContent).toContain('još nema nijednu poveznicu');
  });

  /** Adresa je nečitljiva, pa se pokazuje naziv - ali samo ako ga ima. */
  it('poveznica se pokazuje nazivom, a bez njega adresom', () => {
    open([{ url: 'https://intranet/dms?id=91827', name: 'Politika' }, { url: 'https://b' }]);

    const shown = Array.from(fixture.nativeElement.querySelectorAll('.link-name'))
      .map((a) => (a as HTMLElement).textContent?.trim());
    expect(shown[0]).toContain('Politika');
    expect(shown[1]).toContain('https://b');
  });
});

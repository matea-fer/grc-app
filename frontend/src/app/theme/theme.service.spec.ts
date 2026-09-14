import { TestBed } from '@angular/core/testing';

import { ThemeService } from './theme.service';

/**
 * Tema se ne provjerava po bojama - one su u CSS-u i ovdje ih nema. Provjerava se ono
 * jedino što servis radi: koja vrijednost završi u `data-tema` na `<html>` i preživi li
 * odabir osvježavanje stranice.
 */
describe('ThemeService', () => {
  function stvori(): ThemeService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    return TestBed.inject(ThemeService);
  }

  function primijenjenaNaDokumentu(): string | null {
    return document.documentElement.getAttribute('data-tema');
  }

  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-tema');
  });

  it('bez zapamćenog odabira kreće od sustava, a crta svijetlu', () => {
    const service = stvori();

    expect(service.izbor()).toBe('sustav');
    // jsdom nema tamni način, pa „kao sustav" ovdje znači svijetlu
    expect(service.primijenjena()).toBe('svijetla');
    expect(primijenjenaNaDokumentu()).toBe('svijetla');
  });

  it('odabir se odmah upisuje na <html>', () => {
    const service = stvori();

    service.postavi('tamna');

    expect(service.primijenjena()).toBe('tamna');
    expect(primijenjenaNaDokumentu()).toBe('tamna');
  });

  /** Bez ovoga bi svako osvježavanje stranice vratilo temu na početnu. */
  it('odabir preživljava novo pokretanje', () => {
    stvori().postavi('ljubicasta');

    const nakonOsvjezavanja = stvori();

    expect(nakonOsvjezavanja.izbor()).toBe('ljubicasta');
    expect(primijenjenaNaDokumentu()).toBe('ljubicasta');
  });

  /**
   * Zapamćena vrijednost dolazi iz localStorage, dakle izvana - može ostati od teme koja
   * je u međuvremenu maknuta, ili je netko upisao ručno. Tada se pada na `sustav`, a ne
   * u `data-tema="mornarskaa"` na koju nijedan blok u CSS-u ne odgovara.
   */
  it('nepoznata zapamćena tema se ignorira', () => {
    localStorage.setItem('tema', 'neonska');

    const service = stvori();

    expect(service.izbor()).toBe('sustav');
    expect(primijenjenaNaDokumentu()).toBe('svijetla');
  });

  it('„kao sustav" se pamti kao izbor, ne kao tema koja je iz njega ispala', () => {
    const service = stvori();
    service.postavi('tamna');

    service.postavi('sustav');

    expect(service.izbor()).toBe('sustav');
    expect(localStorage.getItem('tema')).toBe('sustav');
    expect(service.primijenjena()).toBe('svijetla');
  });
});

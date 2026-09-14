import { Injectable, computed, signal } from '@angular/core';

/**
 * Tema koju je korisnik odabrao.
 *
 * `sustav` nije tema nego uputa: „pitaj operativni sustav je li u tamnom načinu".
 * Zato se odvojeno drži ODABIR i ono što je iz njega ispalo ({@link PrimijenjenaTema}) -
 * korisnik koji je izabrao „kao sustav" ne želi da mu se odabir pretvori u „tamna" čim
 * jednom padne mrak.
 */
export type Tema = 'sustav' | 'svijetla' | 'tamna' | 'ljubicasta' | 'mornarska';

/** Teme koje stvarno postoje kao skup vrijednosti tokena u `styles.css`. */
export type PrimijenjenaTema = Exclude<Tema, 'sustav'>;

/** Ponuda u traci. Ključ ide u `data-tema`, naziv se vidi u izborniku. */
export const TEME: ReadonlyArray<{ kljuc: Tema; naziv: string }> = [
  { kljuc: 'sustav', naziv: 'Kao sustav' },
  { kljuc: 'svijetla', naziv: 'Svijetla' },
  { kljuc: 'tamna', naziv: 'Tamna' },
  { kljuc: 'ljubicasta', naziv: 'Ljubičasta' },
  { kljuc: 'mornarska', naziv: 'Mornarska' }
];

const KLJUCEVI = new Set<string>(TEME.map((t) => t.kljuc));

/**
 * Odabrana tema sučelja.
 *
 * Tema je samo drugi skup vrijednosti za iste tokene - servis ne zna nijednu boju, samo
 * upisuje `data-tema` na `<html>`, a `styles.css` na to odgovara. Zato dodavanje teme ne
 * dira ovaj razred: dopiše se blok u CSS i jedan redak u {@link TEME}.
 *
 * Odabir se pamti u localStorage, po pregledniku - nije podatak o korisniku nego o tome
 * kako mu odgovara gledati u ovaj zaslon, a to zna biti različito na stolnom računalu i
 * na prijenosniku.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private static readonly STORAGE_KEY = 'tema';

  private readonly odabrana = signal<Tema>(this.readStored());

  /** Je li sam sustav u tamnom načinu; ima smisla samo dok je odabir `sustav`. */
  private readonly tamniSustav = signal(false);

  /** Što je korisnik izabrao - to stoji u izborniku, uključujući „kao sustav". */
  readonly izbor = this.odabrana.asReadonly();

  /** Što se stvarno crta. */
  readonly primijenjena = computed<PrimijenjenaTema>(() => {
    const izbor = this.odabrana();
    if (izbor !== 'sustav') {
      return izbor;
    }
    return this.tamniSustav() ? 'tamna' : 'svijetla';
  });

  constructor() {
    const upit = ThemeService.upitTamnogSustava();
    if (upit !== null) {
      this.tamniSustav.set(upit.matches);
      // Sustav se zna prebaciti dok je aplikacija otvorena (ručno ili po satu), a odabir
      // „kao sustav" bez ovoga bi vrijedio samo do prvog učitavanja stranice.
      upit.addEventListener('change', (dogadaj) => {
        this.tamniSustav.set(dogadaj.matches);
        this.primijeni();
      });
    }
    this.primijeni();
  }

  postavi(tema: Tema): void {
    this.odabrana.set(tema);
    localStorage.setItem(ThemeService.STORAGE_KEY, tema);
    this.primijeni();
  }

  /**
   * Upis na `<html>`, a ne na `<body>` ili korijensku komponentu: tokeni su deklarirani na
   * `:root`, a i preglednik po `color-scheme` na tom elementu boja klizače i vlastite
   * kontrole (padajući izbornik, birač datuma) - one nisu u dosegu našeg CSS-a.
   */
  private primijeni(): void {
    document.documentElement.setAttribute('data-tema', this.primijenjena());
  }

  /** Zapamćen odabir; sve što nije poznata tema tretira se kao da ga nema. */
  private readStored(): Tema {
    const raw = localStorage.getItem(ThemeService.STORAGE_KEY);
    return raw !== null && KLJUCEVI.has(raw) ? (raw as Tema) : 'sustav';
  }

  /**
   * `matchMedia` ne postoji u svakoj okolini (jsdom u testovima ga zna nemati), a tema bez
   * njega mora i dalje raditi - samo bez praćenja sustava.
   */
  private static upitTamnogSustava(): MediaQueryList | null {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return null;
    }
    return window.matchMedia('(prefers-color-scheme: dark)');
  }
}

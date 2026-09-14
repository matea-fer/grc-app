import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { CurrentUser, LoginResponse } from './auth.model';
import { TenantService } from '../tenant/tenant.service';

/**
 * Prijavljeni korisnik i njegov token.
 *
 * Token stoji u localStorage da prijava preživi osvježavanje stranice. To ga izlaže
 * XSS-u: kod koji se uspije izvršiti unutar stranice može ga pročitati i odnijeti.
 * Alternativa je httpOnly kolačić, koji token skriva od JavaScripta, ali traži CSRF
 * zaštitu i CORS s credentials - svjesna zamjena, zapisana u specu.
 *
 * Uz token se pamti i tko je prijavljen, da se sučelje ne mora crtati iz sadržaja
 * JWT-a. Sadržaj tokena bez provjere potpisa ionako nije dokaz ni o čemu - to što
 * ovdje piše "ADMIN" ne otvara nijedna vrata, jer backend gleda potpisani token.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private static readonly TOKEN_KEY = 'authToken';
  private static readonly USER_KEY = 'authUser';

  private readonly http = inject(HttpClient);
  private readonly tenantService = inject(TenantService);

  private readonly _token = signal<string | null>(localStorage.getItem(AuthService.TOKEN_KEY));
  private readonly _user = signal<CurrentUser | null>(AuthService.readStoredUser());

  readonly token = this._token.asReadonly();
  readonly currentUser = this._user.asReadonly();
  readonly isLoggedIn = computed(() => this._token() !== null);
  /** Globalni administrator - jedini koji nije vezan uz firmu. */
  readonly isAdmin = computed(() => this._user()?.role === 'ADMIN');
  /** Administrator jedne firme - one iz `currentUser().companyId`. */
  readonly isTenantAdmin = computed(() => this._user()?.role === 'TENANT_ADMIN');
  /** Smije li vidjeti ekran "Korisnici". Nad kojima - odlučuje backend iz tokena. */
  readonly canManageUsers = computed(() => this.isAdmin() || this.isTenantAdmin());
  /**
   * Smije li mijenjati shemu - obrasce i njihove stupce.
   *
   * Isto pravilo kao za šifrarnike: shema je konfiguracija, a ne podatak. Obični
   * korisnik po njoj unosi zapise, ali je ne mijenja - brisanje stupca briše
   * vrijednosti tog stupca iz svih zapisa obrasca.
   */
  readonly canEditSchema = computed(() => this.isAdmin() || this.isTenantAdmin());
  /**
   * Smije li otključati zaključan zapis.
   *
   * Zaključava svatko - to je „gotov sam, šaljem na validaciju". Otključava samo
   * administrator: da otključa i onaj tko je zaključao, zaključavanje ne bi bilo zaštita
   * nego neugodnost. Ovo crta sučelje; odluku donosi backend (403 za ostale).
   */
  readonly canUnlockRecords = computed(() => this.isAdmin() || this.isTenantAdmin());

  constructor() {
    // Token preživi zatvaranje preglednika, ali ne i vlastiti istek: aplikacija zna
    // stajati zatvorena danima, a token traje 8 sati (app.jwt.expiry-hours).
    //
    // Bez ove provjere sučelje se nacrta kao prijavljeno - jer `isLoggedIn` znači samo
    // "u localStorage nešto piše" - i sruši ga tek prvi odgovor sa servera (401 koji
    // hvata authInterceptor). Taj odgovor zna kasniti sekundama ako se backend tek
    // digao, a ako backend ne radi, ne stigne uopće: traka tada pokazuje prijavljenog
    // korisnika, izbornik firmi je prazan i ništa ne objašnjava zašto.
    const token = this._token();
    if (token !== null && AuthService.isExpired(token)) {
      this.logout();
    }
  }

  login(username: string, password: string): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>('/api/auth/login', { username, password })
      .pipe(tap((response) => this.store(response)));
  }

  /** Provjera kod pokretanja: vrijedi li token iz localStorage još uvijek. */
  verify(): Observable<CurrentUser> {
    return this.http.get<CurrentUser>('/api/auth/me').pipe(
      tap((user) => {
        this._user.set(user);
        localStorage.setItem(AuthService.USER_KEY, JSON.stringify(user));
      })
    );
  }

  /**
   * Odjava je isključivo na klijentu - token ostaje valjan do isteka jer backend ne
   * vodi popis povučenih tokena. Za ovaj opseg je prihvatljivo i zapisano je u
   * ograničenjima speca.
   */
  logout(): void {
    localStorage.removeItem(AuthService.TOKEN_KEY);
    localStorage.removeItem(AuthService.USER_KEY);
    this._token.set(null);
    this._user.set(null);
    // odabir firme pripada prošloj sesiji; bez ovoga bi ga naslijedio sljedeći korisnik
    this.tenantService.setActive(null);
  }

  private store(response: LoginResponse): void {
    const { token, ...user } = response;
    localStorage.setItem(AuthService.TOKEN_KEY, token);
    localStorage.setItem(AuthService.USER_KEY, JSON.stringify(user));
    this._token.set(token);
    this._user.set(user);

    // Običnom korisniku firmu određuje token, pa se aktivna firma postavlja odmah -
    // inače bi ekrani tražili odabir koji on nema gdje napraviti. ADMIN-u se ne dira
    // zapamćeni odabir; on firmu bira sam u traci.
    if (user.companyId !== null) {
      this.tenantService.setActive(user.companyId);
    }
  }

  /**
   * Je li token istekao - ili se uopće ne da pročitati.
   *
   * Ovo NIJE sigurnosna provjera. Sadržaj tokena se ovdje čita bez provjere potpisa, pa
   * se svejedno može krivotvoriti; jedini sudac je backend (JwtAuthFilter), koji potpis
   * provjerava. Ovdje se štedi samo prazan krug kroz sučelje.
   *
   * Nečitljiv token se broji kao istekao: takav bi na serveru ionako završio kao 401.
   *
   * Pomaknut sat na računalu mijenja samo TKO prvi primijeti istek - kod sata koji
   * zaostaje token prođe ovuda i odbije ga server, točno kao i dosad.
   */
  private static isExpired(token: string): boolean {
    const expiry = AuthService.expiryOf(token);
    return expiry === null || expiry <= Date.now();
  }

  /**
   * Trenutak isteka tokena u milisekundama, ili null ako ga nema.
   *
   * JWT su tri dijela odvojena točkom: zaglavlje, podaci, potpis. Zanima nas srednji,
   * zapisan kao base64url, a u njemu polje `exp` - vrijeme isteka u SEKUNDAMA od
   * 1.1.1970., dok JavaScript vrijeme mjeri u milisekundama (otud * 1000).
   */
  private static expiryOf(token: string): number | null {
    const payload = token.split('.')[1];
    if (payload === undefined) {
      return null;
    }
    try {
      // base64url je base64 u kojem '-' i '_' stoje umjesto '+' i '/', jer ta dva znaka
      // u URL-u imaju svoje značenje; atob zna samo za obični base64. Nadopunu na
      // višekratnik od 4 ('=') atob smije primiti izostavljenu, pa se ne dodaje.
      const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
      // Čita se isključivo `exp`. Ostatak (npr. korisničko ime sa š/ž) bio bi izobličen
      // jer atob vraća bajtove, a ne UTF-8 tekst - za broj to ne smeta, a token se ovdje
      // ionako ne koristi za ispis.
      const exp: unknown = (JSON.parse(json) as { exp?: unknown }).exp;
      return typeof exp === 'number' ? exp * 1000 : null;
    } catch {
      // pokvaren token nije razlog za pad aplikacije - ponaša se kao istekao
      return null;
    }
  }

  private static readStoredUser(): CurrentUser | null {
    const raw = localStorage.getItem(AuthService.USER_KEY);
    if (raw === null) {
      return null;
    }
    try {
      return JSON.parse(raw) as CurrentUser;
    } catch {
      // pokvaren zapis nije razlog za pad aplikacije - ponaša se kao da nema prijave
      return null;
    }
  }
}

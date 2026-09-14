/**
 * Pomoćnici za testove koji simuliraju prijavljenog korisnika.
 *
 * Dok AuthService nije provjeravao istek, u testovima je bilo dovoljno upisati bilo
 * kakav string kao token. Sada se čita polje `exp`, pa token mora imati pravi oblik -
 * inače ga AuthService pri stvaranju odbaci i test ispadne odjavljen.
 *
 * Ovo NISU pravi tokeni: potpis je izmišljen i ne bi prošao na backendu. Za testove
 * u pregledniku je dovoljan, jer se ondje potpis ionako ne provjerava.
 */

/** base64url: '+' i '/' se zamjenjuju s '-' i '_', a nadopuna '=' se izostavlja. */
function encodeSegment(value: object): string {
  return btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Token koji ističe za `seconds` sekundi; negativan broj daje već istekao token. */
export function tokenExpiringIn(seconds: number): string {
  const exp = Math.floor(Date.now() / 1000) + seconds;
  return `${encodeSegment({ alg: 'HS256' })}.${encodeSegment({ sub: 'netko', exp })}.potpis`;
}

/** Token koji sigurno vrijedi za vrijeme trajanja testa. */
export function validTestToken(): string {
  return tokenExpiringIn(3600);
}

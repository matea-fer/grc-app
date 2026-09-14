/**
 * Uloge, od šire prema užoj:
 *   ADMIN        - globalni administrator; nije vezan uz firmu, bira je u traci
 *   TENANT_ADMIN - administrator jedne firme; u njoj upravlja korisnicima
 *   USER         - obični korisnik jedne firme
 *
 * TENANT_ADMIN je ovdje kao i USER: firmu ima i dobiva je iz tokena. Šire ovlasti
 * koje ima tiču se korisničkih računa, a ne dosega po firmama.
 */
export type Role = 'ADMIN' | 'TENANT_ADMIN' | 'USER';

/** Tko je prijavljen. `companyId` je null samo za globalnog ADMIN-a - on firmu bira u traci. */
export interface CurrentUser {
  username: string;
  role: Role;
  companyId: number | null;
  companyName: string | null;
}

export interface LoginResponse extends CurrentUser {
  token: string;
}

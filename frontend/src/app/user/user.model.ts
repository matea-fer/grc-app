import { Role } from '../auth/auth.model';

export interface User {
  id: number;
  username: string;
  role: Role;
  companyId: number | null;
  companyName: string | null;
}

/** Lozinka postoji samo na putu prema backendu - natrag se nikad ne vraća. */
export interface CreateUserInput {
  username: string;
  password: string;
  role: Role;
  companyId: number | null;
}

import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CreateUserInput, User } from './user.model';

/**
 * Korisnici - rute su samo za ADMIN-a i nisu vezane uz aktivnu firmu: ADMIN dodaje
 * korisnike bilo kojoj firmi, pa firma ide u tijelu zahtjeva.
 */
@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = '/api/users';

  getUsers(): Observable<User[]> {
    return this.http.get<User[]>(this.apiUrl);
  }

  create(payload: CreateUserInput): Observable<User> {
    return this.http.post<User>(this.apiUrl, payload);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}

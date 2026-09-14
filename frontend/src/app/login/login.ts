import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { AuthService } from '../auth/auth.service';

/**
 * Prijava. Jedini ekran dostupan bez tokena.
 *
 * Nakon prijave vodi na Podatke; ADMIN tamo prvo bira firmu, običnom korisniku je
 * ona već postavljena iz tokena.
 */
@Component({
  selector: 'app-login',
  imports: [FormsModule],
  templateUrl: './login.html',
  styleUrl: './login.css'
})
export class Login {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected submit(): void {
    const username = this.username().trim();
    const password = this.password();
    if (!username || !password) {
      this.error.set('Korisničko ime i lozinka su obavezni.');
      return;
    }

    this.error.set(null);
    this.busy.set(true);
    this.authService.login(username, password).subscribe({
      next: () => {
        this.busy.set(false);
        this.router.navigate(['/podaci']);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        const message = (error as { error?: { message?: string } })?.error?.message;
        this.error.set(message ?? 'Prijava nije uspjela.');
      }
    });
  }
}

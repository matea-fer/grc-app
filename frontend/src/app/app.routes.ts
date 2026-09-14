import { Routes } from '@angular/router';

import { adminGuard, authGuard, schemaEditorGuard, userManagerGuard } from './auth/auth.guard';
import { AuditLog } from './audit-log/audit-log';
import { CodebookEditor } from './codebook-editor/codebook-editor';
import { Codebooks } from './codebooks/codebooks';
import { KpiSamoprocjene } from './kpi-samoprocjene/kpi-samoprocjene';
import { Login } from './login/login';
import { Organizations } from './organizations/organizations';
import { SbomEval } from './sbom-eval/sbom-eval';
import { SchemaEditor } from './schema-editor/schema-editor';
import { DataView } from './data-view/data-view';
import { Users } from './users/users';
import { ZadaciPregled } from './zadaci-pregled/zadaci-pregled';

// Prijava je jedina ruta bez guarda. Organizacije su samo za globalnog administratora,
// Korisnici i za administratora firme - guardovi ih skrivaju, a backend ih neovisno o
// tome brani s 403 (odnosno 404 za tuđe račune).
export const routes: Routes = [
  { path: '', redirectTo: 'podaci', pathMatch: 'full' },
  { path: 'prijava', component: Login },
  { path: 'organizacije', component: Organizations, canActivate: [authGuard, adminGuard] },
  { path: 'korisnici', component: Users, canActivate: [authGuard, userManagerGuard] },
  // Editor mijenja shemu, a shema je konfiguracija - obični korisnik po njoj unosi
  // zapise na ekranu Podaci, ali je ne mijenja.
  { path: 'editor', component: SchemaEditor, canActivate: [authGuard, schemaEditorGuard] },
  // Šifrarnike čitaju svi; tko ih smije mijenjati odlučuje se u samom ekranu po dosegu
  // pojedinog šifrarnika, a backend to neovisno brani s 403.
  { path: 'sifrarnici', component: Codebooks, canActivate: [authGuard] },
  { path: 'sifrarnici/:id', component: CodebookEditor, canActivate: [authGuard] },
  { path: 'podaci', component: DataView, canActivate: [authGuard] },
  // Custom domenske ploče (nisu dio kostura) - prikaz nad postojećim generičkim API-jem.
  { path: 'kpi-samoprocjene', component: KpiSamoprocjene, canActivate: [authGuard] },
  { path: 'zadaci-pregled', component: ZadaciPregled, canActivate: [authGuard] },
  { path: 'sbom', component: SbomEval, canActivate: [authGuard] },
  { path: 'dnevnik', component: AuditLog, canActivate: [authGuard] },
  { path: '**', redirectTo: 'podaci' }
];

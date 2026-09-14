import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './auth/auth.interceptor';
import { tenantInterceptor } from './tenant/tenant.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // redoslijed je bitan: authInterceptor prvi, da odjava na 401 vrijedi i za
    // pozive kojima je tenantInterceptor usput dodao zaglavlje
    provideHttpClient(withInterceptors([authInterceptor, tenantInterceptor]))
  ]
};

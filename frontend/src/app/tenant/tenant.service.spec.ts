import { TestBed } from '@angular/core/testing';

import { TenantService } from './tenant.service';

describe('TenantService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
  });

  it('kreće bez odabrane firme kad u localStorage ništa nije spremljeno', () => {
    const service = TestBed.inject(TenantService);
    expect(service.activeCompanyId()).toBeNull();
  });

  it('setActive postavi firmu i zapamti je u localStorage', () => {
    const service = TestBed.inject(TenantService);
    service.setActive(3);

    expect(service.activeCompanyId()).toBe(3);
    expect(localStorage.getItem('activeCompanyId')).toBe('3');
  });

  it('učita zapamćenu firmu iz localStorage pri stvaranju', () => {
    localStorage.setItem('activeCompanyId', '7');
    const service = TestBed.inject(TenantService);
    expect(service.activeCompanyId()).toBe(7);
  });

  it('setActive(null) obriše odabir i iz localStorage', () => {
    const service = TestBed.inject(TenantService);
    service.setActive(3);
    service.setActive(null);

    expect(service.activeCompanyId()).toBeNull();
    expect(localStorage.getItem('activeCompanyId')).toBeNull();
  });

  it('clearIfMatches poništi samo ako je pogođena firma trenutno aktivna', () => {
    const service = TestBed.inject(TenantService);
    service.setActive(3);

    service.clearIfMatches(7); // druga firma - odabir ostaje
    expect(service.activeCompanyId()).toBe(3);

    service.clearIfMatches(3); // aktivna firma - odabir se poništava
    expect(service.activeCompanyId()).toBeNull();
  });
});

package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.repository.TemplateRepository;
import com.example.demo.service.AttachmentCleanup;
import com.example.demo.service.LogService;
import com.example.demo.service.TemplateService;
import com.example.demo.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - izolacija po firmi na razini templatea. Template tude firme se
 * tretira kao da ne postoji (404, ne 403) - preko njega ne smiju procuriti ni
 * njegovi stupci ni zapisi.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class TemplateIsolationTest {

    @Mock
    private TemplateRepository repository;

    @Mock
    private SurveyResultRepository surveyRepository;

    @Mock
    private TenantContext tenantContext;

    // vlasnistvo se provjerava prije ovlasti, pa ovaj kontekst ovdje nitko ne dira
    @Mock
    private AuthContext authContext;

    @Mock
    private LogService logService;

    @Mock
    private AttachmentCleanup attachmentCleanup;

    @InjectMocks
    private TemplateService service;

    private Template template(Long id, Long companyId) {
        Template template = new Template(companyId, "Obrazac");
        template.setId(id);
        return template;
    }

    @Test
    @DisplayName("requireOwned vraca template kad pripada firmi iz konteksta")
    void requireOwnedReturnsOwnTemplate() {
        when(tenantContext.require()).thenReturn(3L);
        when(repository.findById(1L)).thenReturn(Optional.of(template(1L, 3L)));

        assertThat(service.requireOwned(1L).getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("requireOwned baca 404 za template druge firme - ne otkriva da postoji")
    void requireOwnedHidesOtherCompanysTemplate() {
        // prijavljena firma 7, template pripada firmi 3
        when(tenantContext.require()).thenReturn(7L);
        when(repository.findById(1L)).thenReturn(Optional.of(template(1L, 3L)));

        assertThatThrownBy(() -> service.requireOwned(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

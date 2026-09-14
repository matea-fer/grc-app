package com.example.demo.core;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CreateTemplateRequest;
import com.example.demo.dto.TemplateResponse;
import com.example.demo.dto.UpdateTemplateRequest;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Role;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.repository.TemplateRepository;
import com.example.demo.service.ReferenceLookup;
import com.example.demo.service.AttachmentCleanup;
import com.example.demo.service.LogService;
import com.example.demo.service.RecordChangeService;
import com.example.demo.service.TemplateService;
import com.example.demo.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CORE test - generika nad TemplateService: mapiranje request -> entitet ->
 * response, firma iz konteksta, kaskadno brisanje zapisa i 404 na nepostojeci id.
 * Sama izolacija (tudi template) je domensko pravilo i ide u business test.
 */
@Tag("core")
@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    @Mock
    private TemplateRepository repository;

    @Mock
    private SurveyResultRepository surveyRepository;

    @Mock
    private TenantContext tenantContext;

    @Mock
    private AuthContext authContext;

    @Mock
    private LogService logService;

    @Mock
    private AttachmentCleanup attachmentCleanup;

    @Mock
    private RecordChangeService recordChangeService;

    /**
     * Ovdje samo da konstruktor dobije sve. Pravilo "obrazac koji je necemu cilj se ne brise"
     * je poslovno i testira se u {@code ReferenceDeleteRulesTest}; ovdje mock ne prigovara.
     */
    @Mock
    private ReferenceLookup referenceLookup;

    @InjectMocks
    private TemplateService service;

    /**
     * Tko je prijavljen ovdje nije predmet ispitivanja - generika pretpostavlja da
     * pozivatelj smije uredivati. Sama ovlast se ispituje u SchemaEditingAccessTest.
     */
    @BeforeEach
    void loggedInAsAdmin() {
        lenient().when(authContext.require())
                .thenReturn(new AuthenticatedUser(1L, "admin", Role.ADMIN, null));
    }

    private Template template(Long id, Long companyId) {
        Template template = new Template(companyId, "Obrazac " + id);
        template.setId(id);
        return template;
    }

    @Test
    @DisplayName("list vraca templatee firme iz konteksta")
    void listReturnsContextCompanysTemplates() {
        when(tenantContext.require()).thenReturn(3L);
        when(repository.findByCompanyId(3L)).thenReturn(List.of(template(1L, 3L), template(2L, 3L)));

        assertThat(service.list()).extracting(TemplateResponse::id).containsExactly(1L, 2L);
        verify(repository).findByCompanyId(3L);
    }

    @Test
    @DisplayName("create postavlja firmu iz konteksta")
    void createSetsCompanyFromContext() {
        when(tenantContext.require()).thenReturn(3L);
        when(repository.save(any(Template.class))).thenAnswer(invocation -> {
            Template toSave = invocation.getArgument(0);
            toSave.setId(5L);
            return toSave;
        });

        TemplateResponse response = service.create(new CreateTemplateRequest("Anketa"));

        ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCompanyId()).isEqualTo(3L);
        assertThat(captor.getValue().getName()).isEqualTo("Anketa");
        assertThat(response.id()).isEqualTo(5L);
    }

    @Test
    @DisplayName("rename mijenja naziv firminog templatea")
    void renameChangesName() {
        when(tenantContext.require()).thenReturn(3L);
        when(repository.findById(1L)).thenReturn(Optional.of(template(1L, 3L)));
        when(repository.save(any(Template.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TemplateResponse response = service.rename(1L, new UpdateTemplateRequest("Novi naziv"));

        assertThat(response.name()).isEqualTo("Novi naziv");
    }

    @Test
    @DisplayName("delete brise template i sve njegove zapise")
    void deleteRemovesTemplateAndItsSurveys() {
        when(tenantContext.require()).thenReturn(3L);
        Template template = template(1L, 3L);
        when(repository.findById(1L)).thenReturn(Optional.of(template));
        List<SurveyResult> surveys = List.of(new SurveyResult(), new SurveyResult());
        when(surveyRepository.findByTemplateId(1L)).thenReturn(surveys);

        service.delete(1L);

        verify(surveyRepository).deleteAll(surveys);
        verify(repository).delete(template);
    }

    @Test
    @DisplayName("requireOwned baca 404 kad template ne postoji")
    void requireOwnedThrowsWhenMissing() {
        when(tenantContext.require()).thenReturn(3L);
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireOwned(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

package com.example.demo.business;

import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookScope;
import com.example.demo.model.Role;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.model.User;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.CodebookRepository;
import com.example.demo.repository.ColumnSequenceRepository;
import com.example.demo.repository.LogEntryRepository;
import com.example.demo.repository.RecordChangeRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.repository.TemplateRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AttachmentCleanup;
import com.example.demo.service.CompanyPurgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - sto trajno praznjenje firme uklanja, a sto NAMJERNO ostavlja.
 *
 * Tezina ovog postupka nije u brisanju nego u dvije iznimke od njega. Obje su odluke, ne
 * previdi, pa moraju biti zapisane kao test: dnevnik prezivi (samo se odveze od firme), a
 * GLOBAL sifrarnici se ne diraju jer nisu ni bili ove firme.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanyPurgeTest {

    private static final Long COMPANY = 7L;

    @Mock
    private TemplateRepository templateRepository;

    @Mock
    private SurveyResultRepository surveyRepository;

    @Mock
    private ColumnSequenceRepository sequenceRepository;

    @Mock
    private CodebookRepository codebookRepository;

    @Mock
    private CodebookItemRepository codebookItemRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LogEntryRepository logEntryRepository;

    @Mock
    private RecordChangeRepository recordChangeRepository;

    @Mock
    private AttachmentCleanup attachmentCleanup;

    private CompanyPurgeService service() {
        return new CompanyPurgeService(templateRepository, surveyRepository, sequenceRepository,
                codebookRepository, codebookItemRepository, userRepository, logEntryRepository,
                recordChangeRepository, attachmentCleanup);
    }

    private static Template template(Long id) {
        Template template = new Template(COMPANY, "Obrazac " + id);
        template.setId(id);
        return template;
    }

    private static Codebook codebook(Long id) {
        Codebook codebook = new Codebook("Statusi", CodebookScope.TENANT, COMPANY);
        codebook.setId(id);
        return codebook;
    }

    @Test
    @DisplayName("uklanja priloge, zapise, brojace, obrasce, sifrarnike i korisnike te firme")
    void removesEverythingOwnedByTheCompany() {
        when(attachmentCleanup.forCompany(COMPANY)).thenReturn(3);
        when(surveyRepository.findByCompanyId(COMPANY)).thenReturn(List.of(new SurveyResult()));
        when(templateRepository.findByCompanyId(COMPANY)).thenReturn(List.of(template(1L), template(2L)));
        when(codebookRepository.findByCompanyId(COMPANY)).thenReturn(List.of(codebook(5L)));
        when(userRepository.findByCompanyId(COMPANY))
                .thenReturn(List.of(new User("ana", "$2a$hash", Role.USER, COMPANY)));

        CompanyPurgeService.PurgeSummary summary = service().purge(COMPANY);

        verify(attachmentCleanup).forCompany(COMPANY);
        // povijest promjena zivi u vlastitoj tablici i ne nestaje s zapisima
        verify(recordChangeRepository).deleteByCompanyId(COMPANY);
        verify(sequenceRepository).deleteByTemplateIdIn(List.of(1L, 2L));
        verify(codebookItemRepository).deleteByCodebookId(5L);
        // Brisanje ide UPITOM, ne ucitavanjem pa deleteAll(entiteti). To drugo je pri
        // praznjenju firme tiho preskakalo retke - obrazac i sifrarnik su ostajali u bazi
        // pokazujuci na firmu koje vise nema, a purge ih je pritom uredno prebrojao.
        // Nadeno u razvojnoj bazi za dvije firme; reproducirano u PurgeAfterTransferTest.
        verify(templateRepository).deleteByCompanyIdBulk(COMPANY);
        verify(surveyRepository).deleteByCompanyIdBulk(COMPANY);
        verify(codebookRepository).deleteByCompanyIdBulk(COMPANY);
        verify(userRepository).deleteByCompanyIdBulk(COMPANY);
        verify(templateRepository, never()).deleteAll(any());
        verify(codebookRepository, never()).deleteAll(any());

        assertThat(summary.templates()).isEqualTo(2);
        assertThat(summary.surveys()).isEqualTo(1);
        assertThat(summary.attachments()).isEqualTo(3);
        assertThat(summary.users()).isEqualTo(1);
    }

    @Test
    @DisplayName("dnevnik se NE brise nego samo odvezuje od firme")
    void logIsDetachedNotDeleted() {
        when(logEntryRepository.detachFromCompany(COMPANY)).thenReturn(42);

        CompanyPurgeService.PurgeSummary summary = service().purge(COMPANY);

        verify(logEntryRepository).detachFromCompany(COMPANY);
        verify(logEntryRepository, never()).deleteAll(any());
        assertThat(summary.detachedLogs()).isEqualTo(42);
        assertThat(summary.describe()).contains("42 zapis(a) dnevnika zadrzano");
    }

    @Test
    @DisplayName("bez ijednog obrasca se brojaci ni ne diraju - prazan IN popis je nevaljan upit")
    void withoutTemplatesSequencesAreLeftAlone() {
        when(templateRepository.findByCompanyId(COMPANY)).thenReturn(List.of());

        service().purge(COMPANY);

        verify(sequenceRepository, never()).deleteByTemplateIdIn(any());
    }

    @Test
    @DisplayName("sifrarnici se traze SAMO po firmi - GLOBAL nemaju company_id i ne mogu se zahvatiti")
    void globalCodebooksAreOutOfReach() {
        service().purge(COMPANY);

        verify(codebookRepository).findByCompanyId(COMPANY);
        // nijedan upit koji bi mogao vratiti tudi ili zajednicki sifrarnik
        verify(codebookRepository, never()).findAll();
        verify(codebookItemRepository, never()).deleteByCodebookId(anyLong());
    }
}

package com.example.demo.service;

import com.example.demo.dto.SbomVulnerability;
import com.example.demo.model.SbomEvaluation;
import com.example.demo.model.SbomStatus;
import com.example.demo.repository.SbomEvaluationRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Pokrece Grype nad uploadanim SBOM-om, u POZADINI, i upisuje rezultat u
 * {@link SbomEvaluation}.
 *
 * Zasto shell-out na {@code grype} CLI, a ne knjiznica: Grype je Go alat bez Java API-ja;
 * standardni put je pozvati ga kao proces i procitati mu JSON izlaz ({@code -o json}).
 *
 * Zasto {@code @Async}: analiza traje sekundama (pri velikom SBOM-u i dulje). HTTP nit koja
 * je primila upload vraca odgovor odmah (status PENDING), a ova metoda tece na zasebnom
 * bazenu niti ({@link com.example.demo.config.AsyncConfig}). Zbog toga ovdje NEMA tenant/
 * auth konteksta (oni zive na HTTP niti) - metoda radi iskljucivo preko id-a evaluacije.
 *
 * Sve greske zavrsavaju kao {@code FAILED} evaluacija s porukom, nikad kao srusen poziv:
 * pozadinska nit koja baci iznimku samo bi je zapisala u log i nitko je vise ne bi vidio.
 */
@Component
public class GrypeRunner {
    private static final Logger log = LoggerFactory.getLogger(GrypeRunner.class);

    /**
     * CLI nacin: lokalni grype vec ima bazu, pa je ne diramo ni ne skidamo (radi offline).
     * Grype inace odbija bazu stariju od 5 dana - VALIDATE_AGE=false to preskace.
     */
    private static final Map<String, String> GRYPE_ENV_CLI = Map.of(
            "GRYPE_DB_VALIDATE_AGE", "false",
            "GRYPE_DB_AUTO_UPDATE", "false"
    );

    /** Grype cache (baza) unutar kontejnera - mapiran na imenovani Docker volumen, da prezivi --rm. */
    private static final String DOCKER_DB_DIR = "/dbcache";

    /** Poredak ozbiljnosti - najgore prvo u spremljenom popisu. */
    private static final List<String> SEVERITY_ORDER =
            List.of("critical", "high", "medium", "low", "negligible", "unknown");

    private final SbomEvaluationRepository repository;
    private final ObjectMapper objectMapper;
    private final String grypePath;
    private final long timeoutSeconds;
    /** "cli" (lokalna grype binarka) ili "docker" (grype u kontejneru, bez lokalne instalacije). */
    private final String mode;
    private final String dockerPath;
    private final String dockerImage;
    private final String dockerDbVolume;

    public GrypeRunner(SbomEvaluationRepository repository,
                       ObjectMapper objectMapper,
                       @Value("${app.sbom.grype-path:grype}") String grypePath,
                       @Value("${app.sbom.timeout-seconds:300}") long timeoutSeconds,
                       @Value("${app.sbom.mode:cli}") String mode,
                       @Value("${app.sbom.docker-path:docker}") String dockerPath,
                       @Value("${app.sbom.docker-image:anchore/grype:latest}") String dockerImage,
                       @Value("${app.sbom.docker-db-volume:grc-grype-db}") String dockerDbVolume) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.grypePath = grypePath;
        this.timeoutSeconds = timeoutSeconds;
        this.mode = mode;
        this.dockerPath = dockerPath;
        this.dockerImage = dockerImage;
        this.dockerDbVolume = dockerDbVolume;
    }

    @Async("sbomExecutor")
    public void run(Long evaluationId, byte[] sbomBytes) {
        if (!markRunning(evaluationId)) {
            return; // evaluacija je u međuvremenu obrisana
        }

        Path workDir = null;
        Path outFile = null;
        Path errFile = null;
        try {
            // Zaseban direktorij (ne samo datoteka): u docker nacinu se mapira kao volumen,
            // pa u njemu smije stajati samo SBOM, a ne cijeli sistemski temp.
            workDir = Files.createTempDirectory("sbom-");
            Path sbomFile = workDir.resolve("sbom.json");
            Files.write(sbomFile, sbomBytes);
            outFile = Files.createTempFile("grype-out-", ".json");
            errFile = Files.createTempFile("grype-err-", ".txt");

            ProcessBuilder pb = buildProcess(workDir, sbomFile);
            pb.redirectOutput(outFile.toFile());
            pb.redirectError(errFile.toFile());

            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                fail(evaluationId, "Analiza je prekoračila vremensko ograničenje (" + timeoutSeconds + " s).");
                return;
            }
            int exit = process.exitValue();
            if (exit != 0) {
                fail(evaluationId, "Grype je vratio kod " + exit + ": " + tail(errFile));
                return;
            }

            String json = Files.readString(outFile, StandardCharsets.UTF_8);
            storeResult(evaluationId, objectMapper.readValue(json, GrypeReport.class));
        } catch (Exception e) {
            log.error("SBOM analiza (id={}) nije uspjela", evaluationId, e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
            fail(evaluationId, "Analiza nije uspjela: " + reason);
        } finally {
            deleteQuietly(outFile);
            deleteQuietly(errFile);
            deleteDirQuietly(workDir);
        }
    }

    /**
     * Naredba za pokretanje Grypea, ovisno o nacinu.
     *
     * CLI: lokalna binarka {@code grype} cita SBOM izravno s diska; bazu ima lokalno.
     *
     * DOCKER: {@code docker run --rm} nad slikom {@code anchore/grype}. SBOM ulazi kroz volumen
     * {@code /work}, a baza (cache) kroz IMENOVANI volumen na {@code /dbcache} - tako prvi put
     * povuce bazu (treba mreza), a svaki sljedeci put je koristi iz volumena (radi i offline,
     * jer VALIDATE_AGE=false ne odbija zatecenu bazu). Ne treba lokalna instalacija Grypea.
     */
    private ProcessBuilder buildProcess(Path workDir, Path sbomFile) {
        if ("docker".equalsIgnoreCase(mode)) {
            List<String> command = List.of(
                    dockerPath, "run", "--rm",
                    "-e", "GRYPE_DB_VALIDATE_AGE=false",
                    "-e", "GRYPE_DB_CACHE_DIR=" + DOCKER_DB_DIR,
                    "-v", dockerDbVolume + ":" + DOCKER_DB_DIR,
                    "-v", workDir.toAbsolutePath() + ":/work",
                    dockerImage,
                    "sbom:/work/" + sbomFile.getFileName(), "-o", "json");
            return new ProcessBuilder(command);
        }
        ProcessBuilder pb = new ProcessBuilder(
                grypePath, "sbom:" + sbomFile.toAbsolutePath(), "-o", "json");
        pb.environment().putAll(GRYPE_ENV_CLI);
        return pb;
    }

    private boolean markRunning(Long evaluationId) {
        Optional<SbomEvaluation> found = repository.findById(evaluationId);
        if (found.isEmpty()) {
            return false;
        }
        SbomEvaluation evaluation = found.get();
        evaluation.setStatus(SbomStatus.RUNNING);
        repository.save(evaluation);
        return true;
    }

    private void storeResult(Long evaluationId, GrypeReport report) {
        repository.findById(evaluationId).ifPresent(evaluation -> {
            List<SbomVulnerability> vulns = toVulnerabilities(report);
            applyCounts(evaluation, vulns);
            evaluation.setToolVersion(report.descriptor() == null ? null : report.descriptor().version());
            evaluation.setVulnerabilities(objectMapper.writeValueAsString(vulns));
            evaluation.setStatus(SbomStatus.DONE);
            evaluation.setErrorMessage(null);
            evaluation.setFinishedAt(Instant.now());
            repository.save(evaluation);
            log.info("SBOM analiza (id={}) gotova: {} ranjivosti ({} kritičnih)",
                    evaluationId, vulns.size(), evaluation.getCriticalCount());
        });
    }

    private void fail(Long evaluationId, String message) {
        repository.findById(evaluationId).ifPresent(evaluation -> {
            evaluation.setStatus(SbomStatus.FAILED);
            evaluation.setErrorMessage(clip(message, 1990));
            evaluation.setFinishedAt(Instant.now());
            repository.save(evaluation);
        });
        log.warn("SBOM analiza (id={}) FAILED: {}", evaluationId, message);
    }

    private List<SbomVulnerability> toVulnerabilities(GrypeReport report) {
        List<SbomVulnerability> result = new ArrayList<>();
        if (report.matches() == null) {
            return result;
        }
        for (GrypeMatch match : report.matches()) {
            if (match == null || match.vulnerability() == null) {
                continue;
            }
            GrypeVuln v = match.vulnerability();
            GrypeArtifact a = match.artifact();
            GrypeFix fix = v.fix();
            result.add(new SbomVulnerability(
                    v.id(),
                    v.severity() == null ? "Unknown" : v.severity(),
                    a == null ? null : a.name(),
                    a == null ? null : a.version(),
                    a == null ? null : a.type(),
                    fix == null ? null : fix.state(),
                    fix == null ? List.of() : (fix.versions() == null ? List.of() : fix.versions()),
                    v.dataSource()
            ));
        }
        result.sort(Comparator.comparingInt((SbomVulnerability v) -> severityRank(v.severity()))
                .thenComparing(v -> v.id() == null ? "" : v.id()));
        return result;
    }

    private void applyCounts(SbomEvaluation evaluation, List<SbomVulnerability> vulns) {
        int critical = 0, high = 0, medium = 0, low = 0, negligible = 0, unknown = 0;
        for (SbomVulnerability v : vulns) {
            switch (bucket(v.severity())) {
                case "critical" -> critical++;
                case "high" -> high++;
                case "medium" -> medium++;
                case "low" -> low++;
                case "negligible" -> negligible++;
                default -> unknown++;
            }
        }
        evaluation.setCriticalCount(critical);
        evaluation.setHighCount(high);
        evaluation.setMediumCount(medium);
        evaluation.setLowCount(low);
        evaluation.setNegligibleCount(negligible);
        evaluation.setUnknownCount(unknown);
        evaluation.setTotalCount(vulns.size());
    }

    private static String bucket(String severity) {
        String s = severity == null ? "unknown" : severity.toLowerCase();
        return SEVERITY_ORDER.contains(s) ? s : "unknown";
    }

    private static int severityRank(String severity) {
        int index = SEVERITY_ORDER.indexOf(bucket(severity));
        return index < 0 ? SEVERITY_ORDER.size() : index;
    }

    /** Zadnjih nekoliko stotina znakova stderr-a - dovoljno za razlog pada, bez romana. */
    private String tail(Path errFile) {
        try {
            String text = Files.readString(errFile, StandardCharsets.UTF_8).trim();
            return clip(text.length() > 600 ? text.substring(text.length() - 600) : text, 600);
        } catch (Exception e) {
            return "(stderr nedostupan)";
        }
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.debug("Nije obrisana privremena datoteka {}", path, e);
        }
    }

    /** Obrise privremeni radni direktorij zajedno sa sadrzajem (SBOM datotekom). */
    private void deleteDirQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // najbolji trud; zaostali temp OS ionako pocisti
                }
            });
        } catch (IOException e) {
            log.debug("Nije obrisan privremeni direktorij {}", dir, e);
        }
    }

    // --- Grype JSON, samo polja koja koristimo (ostalo Jackson preskace) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GrypeReport(List<GrypeMatch> matches, GrypeDescriptor descriptor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GrypeMatch(GrypeVuln vulnerability, GrypeArtifact artifact) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GrypeVuln(String id, String severity, String dataSource, GrypeFix fix) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GrypeFix(List<String> versions, String state) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GrypeArtifact(String name, String version, String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GrypeDescriptor(String name, String version) {
    }
}

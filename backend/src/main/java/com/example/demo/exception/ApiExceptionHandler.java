package com.example.demo.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(DuplicateColumnKeyException.class)
    public ResponseEntity<ApiError> handleDuplicateColumn(DuplicateColumnKeyException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(DuplicateUsernameException.class)
    public ResponseEntity<ApiError> handleDuplicateUsername(DuplicateUsernameException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidUserException.class)
    public ResponseEntity<ApiError> handleInvalidUser(InvalidUserException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidCompanyException.class)
    public ResponseEntity<ApiError> handleInvalidCompany(InvalidCompanyException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(DuplicateCodebookNameException.class)
    public ResponseEntity<ApiError> handleDuplicateCodebookName(DuplicateCodebookNameException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(DuplicateCodebookItemCodeException.class)
    public ResponseEntity<ApiError> handleDuplicateCodebookItemCode(DuplicateCodebookItemCodeException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(CodebookInUseException.class)
    public ResponseEntity<ApiError> handleCodebookInUse(CodebookInUseException ex) {
        log.warn("Codebook in use: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidCodebookException.class)
    public ResponseEntity<ApiError> handleInvalidCodebook(InvalidCodebookException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(SchemaValidationException.class)
    public ResponseEntity<ApiError> handleSchemaValidation(SchemaValidationException ex) {
        log.warn("Schema validation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidAttachmentException.class)
    public ResponseEntity<ApiError> handleInvalidAttachment(InvalidAttachmentException ex) {
        log.warn("Prilog odbijen: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidSbomException.class)
    public ResponseEntity<ApiError> handleInvalidSbom(InvalidSbomException ex) {
        log.warn("SBOM odbijen: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    /**
     * Datoteka veca od granice koju Spring pusti do kontrolera. Ovo se dogodi PRIJE nego
     * servis vidi zahtjev, pa se vlastita provjera velicine ovime ne zamjenjuje nego
     * nadopunjuje - poruka mora biti razumljiva u oba slucaja.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Prilog prelazi granicu koju posluzitelj prihvaca: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("Datoteka je prevelika."));
    }

    @ExceptionHandler(InvalidColumnDefinitionException.class)
    public ResponseEntity<ApiError> handleInvalidColumnDefinition(InvalidColumnDefinitionException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    // Dvije istovremene izmjene istog templatea (@Version): druga zatekne zastarjelu
    // verziju. Vrati 409 s jasnom porukom umjesto 500 - klijent neka osvjezi i pokusa ponovno.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic lock: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("Podaci su u međuvremenu promijenjeni. Osvježite i pokušajte ponovno."));
    }

    // Zadnja crta obrane: TenantFilter zasticene rute vec odbija prije ovdje, ali ako
    // servis zatrazi tenanta na putanji koja nije filtrirana, ne rusimo se u 500.
    @ExceptionHandler(MissingTenantException.class)
    public ResponseEntity<ApiError> handleMissingTenant(MissingTenantException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    // Filteri odbijaju neprijavljene zahtjeve prije ovoga; ovdje zavrsi samo ono sto
    // je proslo filter, a servis je ipak zatrazio prijavljenog korisnika.
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(ex.getMessage()));
    }

    // Prijavljen jest, ali njegova uloga ovo ne smije. Namjerno 403, a ne 404 kao kod
    // tudih redaka: da je ruta samo za ADMIN-a nije tajna koju treba skrivati.
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ex.getMessage()));
    }

    // Zapis je zakljucan. 409, a ne 403: nije stvar uloge nego stanja zapisa - dok je
    // zakljucan, izmjena ne prolazi nikome. Poruka nosi i tko ga je zakljucao.
    @ExceptionHandler(RecordLockedException.class)
    public ResponseEntity<ApiError> handleRecordLocked(RecordLockedException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    // Izmjena stupca bi zapisima uzela vrijednosti, a nitko to nije potvrdio. 409 (sukob
    // stanja), s brojem zahvacenih zapisa u poruci - klijent je pokazuje i pita, pa ponovi
    // poziv s potvrdom.
    @ExceptionHandler(ColumnDataLossException.class)
    public ResponseEntity<ApiError> handleColumnDataLoss(ColumnDataLossException ex) {
        log.warn("Izmjena stupca zaustavljena: {} zapis(a) bi ostalo bez vrijednosti", ex.getAffectedRecords());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(ex.getMessage(), ApiError.CODE_COLUMN_DATA_LOSS));
    }

    // Zapis se ne brise jer na njega pokazuju veze iz drugih obrazaca. 409 kao i kod
    // zakljucanog zapisa - stanje podataka, a ne ovlast; poruka nosi popis onih koji smetaju.
    @ExceptionHandler(RecordReferencedException.class)
    public ResponseEntity<ApiError> handleRecordReferenced(RecordReferencedException ex) {
        log.warn("Brisanje zaustavljeno zbog veza: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(ex.getMessage(), ApiError.CODE_RECORD_REFERENCED));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(message));
    }

    // Parametar u putanji ili upitu koji se ne da pretvoriti u ocekivani tip - npr.
    // ?scope=NESTO gdje se ocekuje enum. To je kriv zahtjev, a ne kvar servera, pa 400
    // umjesto da propadne u handleUnexpected i zavrsi kao 500.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Neispravna vrijednost parametra {}: {}", ex.getName(), ex.getValue());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("Neispravna vrijednost parametra \"" + ex.getName() + "\"."));
    }

    /**
     * Ogranicenje baze koje servis nije predvidio.
     *
     * Bez ovoga takav pad zavrsi u {@link #handleUnexpected} kao gola poruka "Unexpected
     * error" - a to je najgori mogući ishod: korisnik ne zna sto je krivo, a onaj tko trazi
     * uzrok ne zna ni gdje poceti. Ime ogranicenja u poruci ne otkriva nista osjetljivo
     * (nema ni podataka ni SQL-a), a jedino ono vodi ravno do mjesta.
     *
     * Poznata pravila (jedinstven naziv sifrarnika, duplo korisnicko ime...) i dalje hvataju
     * vlastite iznimke prije ove; ovdje zavrsava samo ono na sto se nitko nije sjetio.
     *
     * Cest uzrok u ovom projektu: {@code ddl-auto=update} dodaje tablice i stupce, ali
     * NIKAD ne mijenja postojeca ogranicenja. Nova vrijednost u enumu (npr. uloga) zato
     * ostaje odbijena starim CHECK-om sve dok se on rucno ne osvjezi.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        String constraint = constraintNameOf(ex);
        log.error("Ogranicenje baze odbilo je upis (constraint={})", constraint, ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                "Baza je odbila upis" + (constraint == null ? "" : " (ograničenje \"" + constraint + "\")")
                        + ". Podaci ne odgovaraju onome što shema baze dopušta."));
    }

    /** Ime prekrsenog ogranicenja, ako ga je Hibernate uspio izdvojiti. */
    private String constraintNameOf(DataIntegrityViolationException ex) {
        Throwable cause = ex.getCause();
        return cause instanceof ConstraintViolationException violation ? violation.getConstraintName() : null;
    }

    /**
     * Putanja koju nijedan kontroler ne pokriva je 404, ne 500.
     *
     * Bez ovoga bi je pokupio {@link #handleUnexpected} i vratio "Unexpected error",
     * pa bi tipfeler u adresi izgledao kao kvar posluzitelja. 5xx mora znaciti da je
     * nesto puklo - inace prestaje biti signal.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        log.warn("Nepoznata putanja: {} {}", ex.getHttpMethod(), ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("Putanja ne postoji"));
    }

    /**
     * Tijelo zahtjeva koje se ne da procitati je 400, ne 500 - pogresku je napravio
     * klijent, ne posluzitelj.
     *
     * Zamka koja je ovo iznudila: na Spring Bootu 4 Jackson 3 ukljucuje
     * {@code FAIL_ON_NULL_FOR_PRIMITIVES}, pa izostavljeno polje koje se mapira u
     * primitivni tip rusi rasclanjivanje prije ijedne validacije. Sami DTO-i su
     * popravljeni ({@code Boolean} umjesto {@code boolean}), ali odgovor mora biti
     * ispravan i za svaki drugi neispravan JSON.
     */
    /**
     * Putanja postoji, ali ne za ovu metodu - 405, ne 500.
     *
     * Bez ovoga svaki poziv krivom metodom izgleda kao kvar posluzitelja, a odgovor
     * pritom ne kaze klijentu ono jedino korisno: koje su metode dopustene.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Metoda {} nije dopustena na toj putanji", ex.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError("Metoda " + ex.getMethod() + " nije dopuštena na toj putanji."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Tijelo zahtjeva se ne da procitati: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(new ApiError(
                "Tijelo zahtjeva nije ispravno i ne može se pročitati."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("Unexpected error"));
    }
}

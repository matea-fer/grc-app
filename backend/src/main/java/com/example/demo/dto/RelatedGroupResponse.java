package com.example.demo.dto;

import java.util.List;

/**
 * Jedna skupina povezanih zapisa - jedna kartica u dijalogu "Povezani zapisi".
 *
 * Skupine, a ne jedan popis, jer povezani zapisi dolaze iz RAZLICITIH obrazaca, a svaki od njih
 * ima svoje stupce - "Potprocesi" i "Rizici" nemaju nista zajednicko osim toga da oba dodiruju
 * ovaj zapis. Spojeni u jednu tablicu ne bi imali zajednicko zaglavlje.
 *
 * Veza se gleda u OBA smjera, i to su dvije razlicite stvari:
 *
 *   {@code OUTGOING} - na koga OVAJ zapis pokazuje. Sam podatak vec stoji u celiji referentnog
 *   stupca, ali ondje stoji samo NAZIV; ovdje se vidi i sadrzaj tog zapisa (nositelj, status,
 *   rok...). To je razlika izmedu "rizik pripada procesu Nabava" i "evo tog procesa".
 *
 *   {@code INCOMING} - tko pokazuje na OVAJ zapis. Drugdje se ne moze dobiti, jer vezu drzi
 *   samo dijete: proces ne zna nista o svojim potprocesima.
 *
 * Skupina NE nosi same zapise nego samo koliko ih je; dohvacaju se tek kad se skupina odabere.
 * Bez toga bi klik na gumb povukao zapise svih obrazaca koji ovaj zapis dodiruju, a gleda se
 * najcesce jedan.
 *
 * @param templateId   obrazac ciji se zapisi u ovoj skupini prikazuju
 * @param templateName njegov naziv - za karticu u dijalogu
 * @param columnKey    stupac kroz koji veza ide. Kod {@code INCOMING} je to stupac TOG obrasca i
 *                     njime se filtrira; kod {@code OUTGOING} je stupac OVOG obrasca i sluzi
 *                     samo kao naziv kartice - zapisi se ondje traze po {@link #recordIds}.
 * @param columnLabel  naziv tog stupca za korisnika
 * @param direction    u kojem smjeru veza ide
 * @param total        koliko zapisa skupina ima
 * @param recordIds    kod {@code OUTGOING}: id-evi na koje ovaj zapis pokazuje; inace prazno
 */
public record RelatedGroupResponse(
        Long templateId,
        String templateName,
        String columnKey,
        String columnLabel,
        Direction direction,
        long total,
        List<Long> recordIds
) {

    /** Smjer veze u odnosu na zapis nad kojim je gumb pritisnut. */
    public enum Direction {
        /** Ovaj zapis pokazuje na njih. */
        OUTGOING,
        /** Oni pokazuju na ovaj zapis. */
        INCOMING
    }

    /** Skupina zapisa koji pokazuju na nas - trazi se filtrom po {@code columnKey}. */
    public static RelatedGroupResponse incoming(Long templateId, String templateName,
                                                String columnKey, String columnLabel, long total) {
        return new RelatedGroupResponse(templateId, templateName, columnKey, columnLabel,
                Direction.INCOMING, total, List.of());
    }

    /** Skupina zapisa na koje mi pokazujemo - traze se po id-evima koje nosimo u podacima. */
    public static RelatedGroupResponse outgoing(Long templateId, String templateName,
                                                String columnKey, String columnLabel, List<Long> recordIds) {
        return new RelatedGroupResponse(templateId, templateName, columnKey, columnLabel,
                Direction.OUTGOING, recordIds.size(), List.copyOf(recordIds));
    }
}

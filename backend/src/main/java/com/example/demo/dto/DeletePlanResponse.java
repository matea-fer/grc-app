package com.example.demo.dto;

import java.util.List;

/**
 * Sto ce skupno brisanje odnijeti.
 *
 * Najava je ovdje vaznija nego kod prijenosa: prijenos samo dodaje, a brisanje odnosi i sve
 * zapise i priloge obrasca, i to nepovratno. Zato se broji unaprijed - "3 obrasca" i "3
 * obrasca i 1.240 zapisa" su dvije razlicite odluke.
 *
 * @param templates obrasci koji nestaju, sa svojim brojevima
 * @param blockers  veze iz obrazaca koji OSTAJU; dok ih ima, brisanje se odbija
 */
public record DeletePlanResponse(List<TemplateDeletion> templates, List<String> blockers) {

    /**
     * @param id           obrazac koji nestaje
     * @param name         naziv
     * @param recordCount  koliko zapisa odlazi s njim
     * @param attachmentCount koliko prilozenih datoteka odlazi s njim
     */
    public record TemplateDeletion(Long id, String name, long recordCount, long attachmentCount) {
    }

    /** Smije li se brisanje uopce izvesti. */
    public boolean deletable() {
        return blockers.isEmpty();
    }
}

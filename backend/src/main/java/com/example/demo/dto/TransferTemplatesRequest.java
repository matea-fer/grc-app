package com.example.demo.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Zahtjev za prijenos obrazaca iz jedne firme u drugu.
 *
 * Obrasci se navode izrijekom, a ne "svi iz izvorne firme": prijenos je radnja koja prelazi
 * granicu izmedu firmi, pa mora reci sto tocno prelazi. Popis dolazi s ekrana, gdje ga
 * korisnik oznacava kvacicama.
 *
 * @param sourceCompanyId firma iz koje se uzima
 * @param targetCompanyId firma u koju se prenosi
 * @param templateIds     obrasci izvorne firme koji se prenose
 */
public record TransferTemplatesRequest(
        @NotNull Long sourceCompanyId,
        @NotNull Long targetCompanyId,
        @NotEmpty List<Long> templateIds
) {
}

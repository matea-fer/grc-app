package com.example.demo.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Nov redoslijed stupaca obrasca - popis kljuceva, od prvog do zadnjeg.
 *
 * Salju se SVI kljucevi, a ne samo premjesteni ("stupac X ide na 3. mjesto"). Razlog je
 * provjera koja time postaje moguca: popis koji se ne poklapa sa zatecenim stupcima znaci da
 * shema u meduvremenu vise nije ista - netko je u drugoj kartici dodao ili maknuo stupac.
 * Djelomicna uputa bi se u tom slucaju tiho primijenila na neceg drugog, a ovako se odbija.
 */
public record ReorderColumnsRequest(
        @NotEmpty(message = "Redoslijed mora sadržavati stupce obrasca.")
        List<String> columnKeys
) {
}

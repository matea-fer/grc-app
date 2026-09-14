import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { EMPTY, Observable, expand, forkJoin, map, of, reduce, switchMap } from 'rxjs';

import { ColumnDefinition } from '../column-definition/column-definition.model';
import { ColumnDefinitionService } from '../column-definition/column-definition.service';
import { CodebookService } from '../codebook/codebook.service';
import { Survey } from '../survey/survey.model';
import { SurveyFilter, SurveyService } from '../survey/survey.service';
import { Template } from '../template/template.model';

/** Stupci obrasca + preslikavanje šifra→naziv za svaki šifrarnički stupac. */
export interface TemplateCodeLabels {
  columns: ColumnDefinition[];
  /** columnKey -> (šifra -> naziv). Stupac koji nije šifrarnik ovdje nema unos. */
  labels: Map<string, Map<string, string>>;
}

/**
 * Zajednički dohvat za nadzorne ploče (custom prikaze nad domenom).
 *
 * Ploče su izvještaji: trebaju SVE zapise obrasca radi zbrajanja, a generički
 * `/surveys` vraća stranicu od najviše 50. Zato se ovdje strani prolazi do kraja
 * (`expand`). Za demo (desetci zapisa) to je jedan poziv; kod tisuća zapisa bolji bi
 * bio namjenski agregacijski endpoint na backendu - vidi napomenu u ploči.
 *
 * Ništa se ne dodaje u backend: koriste se postojeći generički endpointi, a
 * tenantInterceptor sam lijepi TenantID (za administratora) na svaki poziv.
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);
  private readonly surveyService = inject(SurveyService);
  private readonly columnService = inject(ColumnDefinitionService);
  private readonly codebookService = inject(CodebookService);

  /** Obrasci aktivne firme - bez nuspojava na odabir (ne dira TemplateService). */
  templates(): Observable<Template[]> {
    return this.http.get<Template[]>('/api/templates');
  }

  /**
   * SVI zapisi jednog obrasca (uz opcionalne filtre), skupljeni kroz sve stranice.
   *
   * Filtri idu na server istim putem kao pretraga u tablici; za referentni stupac to je
   * egzaktno podudaranje po id-u (vidi SurveyResultService.parseFilter).
   */
  allSurveys(templateId: number, filters: SurveyFilter[] = []): Observable<Survey[]> {
    return this.surveyService.getSurveys(templateId, { page: 0, filters }).pipe(
      expand((page) =>
        page.page + 1 < page.totalPages
          ? this.surveyService.getSurveys(templateId, { page: page.page + 1, filters })
          : EMPTY
      ),
      reduce((acc, page) => acc.concat(page.content), [] as Survey[])
    );
  }

  /** Stupci obrasca + mape šifra→naziv za sve njegove šifrarničke stupce (jednim potezom). */
  codeLabels(templateId: number): Observable<TemplateCodeLabels> {
    return this.columnService.getForTemplate(templateId).pipe(
      switchMap((columns) => {
        const codebookColumns = columns.filter(
          (c) => c.columnType === 'codebook' && c.codebookId !== null
        );
        if (codebookColumns.length === 0) {
          return of<TemplateCodeLabels>({ columns, labels: new Map() });
        }
        return forkJoin(
          codebookColumns.map((c) =>
            this.codebookService.getItems(c.codebookId as number).pipe(
              map((items) => {
                const codeToName = new Map<string, string>();
                for (const item of items) {
                  codeToName.set(item.code, item.name);
                }
                return [c.columnKey, codeToName] as const;
              })
            )
          )
        ).pipe(map((pairs) => ({ columns, labels: new Map(pairs) })));
      })
    );
  }
}

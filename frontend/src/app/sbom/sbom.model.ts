// Stanje jedne SBOM evaluacije - prati backend enum SbomStatus.
export type SbomStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';

/** Sažetak evaluacije (popis + polling statusa), bez samog popisa ranjivosti. */
export interface SbomEvaluation {
  id: number;
  /** Zapis-produkt uz koji je SBOM vezan; null za samostalnu evaluaciju. */
  productId: number | null;
  productName: string | null;
  fileName: string;
  status: SbomStatus;
  uploadedAt: string;
  finishedAt: string | null;
  uploadedBy: string | null;
  toolVersion: string | null;
  total: number;
  critical: number;
  high: number;
  medium: number;
  low: number;
  negligible: number;
  unknown: number;
  errorMessage: string | null;
}

/** Jedna ranjivost iz Grype izvještaja. */
export interface SbomVulnerability {
  id: string;
  severity: string;
  packageName: string | null;
  packageVersion: string | null;
  packageType: string | null;
  fixState: string | null;
  fixedInVersions: string[];
  dataSource: string | null;
}

/** Puni detalj: sažetak + popis ranjivosti. */
export interface SbomEvaluationDetail extends SbomEvaluation {
  vulnerabilities: SbomVulnerability[];
}

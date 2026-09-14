export interface Company {
  id: number;
  name: string;
  /** Vrijeme arhiviranja (ISO). Null za aktivne firme; popunjen samo na popisu arhive. */
  deletedAt: string | null;
}

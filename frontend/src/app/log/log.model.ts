/** Jedan redak dnevnika. `username` je null za zapise nastale prije uvođenja prijave. */
export interface LogEntry {
  id: number;
  username: string | null;
  action: string;
  detail: string;
  createdAt: string;
}

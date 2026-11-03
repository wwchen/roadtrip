export interface ExecOptions {
  input?: string;
  inputFile?: string;
  outputFile?: string;
  timeoutMs?: number;
}

export interface PostgisSession {
  containerName(): string;
  exec(argv: readonly string[], options?: ExecOptions): Promise<string>;
  createDatabase(name: string): Promise<void>;
  connectionString(name: string): string;
  migrateFlyway(name: string): Promise<void>;
  dump(name: string): Promise<string>;
}

export function dockerWithin(timeoutMs: number, args: readonly string[]): Promise<string>;
export function dockerTimeoutMs(env?: Record<string, string | undefined>): number;
export function normalize(sql: string): string;
export function assertPlausibleSchema(sql: string): void;
export function withPostgis<T>(use: (postgres: PostgisSession) => Promise<T>): Promise<T>;
export function writeReference(outPath: string): Promise<void>;

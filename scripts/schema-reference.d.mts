export interface PostgisSession {
  createDatabase(name: string): Promise<void>;
  connectionString(name: string): string;
  migrateFlyway(name: string): Promise<void>;
  dump(name: string): Promise<string>;
}

export function normalize(sql: string): string;
export function assertPlausibleSchema(sql: string): void;
export function withPostgis<T>(use: (postgres: PostgisSession) => Promise<T>): Promise<T>;
export function writeReference(outPath: string): Promise<void>;

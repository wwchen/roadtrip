export interface RunLog {
  info(message: string): void;
  warn(message: string): void;
}

export const STDERR_LOG: RunLog = { info: console.error, warn: console.error };

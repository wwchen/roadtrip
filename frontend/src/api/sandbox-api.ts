// Clients for the two endpoints the sandbox chrome reads.
//
// Both are anonymous and both are absent-by-design outside a sandbox:
// `/api/sandbox/users` answers 404 unless `assume-user` is enabled, and
// `/api/build-info` reports whatever env the build was stamped with. So a
// rejection here is a normal answer ("not a sandbox"), not a fault — see
// `app/sandbox/` for the callers, which swallow it.
import { jsonGetOk, type RequestOptions } from './http';

const BUILD_INFO_URL = '/api/build-info';
const SANDBOX_USERS_URL = '/api/sandbox/users';

/** `GET /api/build-info`. Mirrors BuildInfoDto. */
export interface BuildInfo {
  /** Deploy environment, e.g. `prod`, `local`, `sandbox`. */
  env: string;
  sha: string;
  branch: string;
}

/** One entry of `GET /api/sandbox/users`. Mirrors SandboxUserDto. */
export interface SandboxUser {
  id: number;
  /** Display name, falling back to the email server-side. */
  name: string;
  roles: string[];
}

export function fetchBuildInfo({ signal }: RequestOptions = {}): Promise<BuildInfo> {
  return jsonGetOk<BuildInfo>(BUILD_INFO_URL, { signal });
}

/** Seeded users a sandbox reviewer may assume. 404s when assume-user is off. */
export function fetchSandboxUsers({ signal }: RequestOptions = {}): Promise<SandboxUser[]> {
  return jsonGetOk<SandboxUser[]>(SANDBOX_USERS_URL, { signal });
}

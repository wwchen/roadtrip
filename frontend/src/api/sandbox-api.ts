// Client for the endpoint the sandbox build banner reads.
//
// `/api/build-info` reports whatever env the build was stamped with. A
// rejection here is a normal answer ("no deployment metadata"), not a fault —
// see `app/sandbox/` for the caller, which swallows it.
import { jsonGetOk, type RequestOptions } from './http';

const BUILD_INFO_URL = '/api/build-info';

/** `GET /api/build-info`. Mirrors BuildInfoDto. */
export interface BuildInfo {
  /** Deploy environment, e.g. `prod`, `local`, `sandbox`. */
  env: string;
  sha: string;
  branch: string;
}

export function fetchBuildInfo({ signal }: RequestOptions = {}): Promise<BuildInfo> {
  return jsonGetOk<BuildInfo>(BUILD_INFO_URL, { signal });
}

export const NEON_API_BASE = 'https://console.neon.tech/api/v2';

const READ_WRITE_ENDPOINT = 'read_write';

export function createNeonClient({ apiKey, projectId, fetch = globalThis.fetch }) {
  if (!apiKey) throw new Error('NEON_API_KEY is not set');
  if (!projectId) throw new Error('NEON_PROJECT_ID is not set');
  const projectUrl = `${NEON_API_BASE}/projects/${projectId}`;

  async function call(method, url, body) {
    const response = await fetch(url, {
      method,
      headers: {
        Authorization: `Bearer ${apiKey}`,
        Accept: 'application/json',
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (!response.ok) {
      throw new Error(`Neon ${method} ${url} failed with ${response.status}: ${await response.text()}`);
    }
    return response.json();
  }

  async function listBranches() {
    const { branches } = await call('GET', `${projectUrl}/branches`);
    return branches.map((branch) => ({ id: branch.id, name: branch.name }));
  }

  async function findBranch(name) {
    return (await listBranches()).find((branch) => branch.name === name);
  }

  async function requireBranch(name) {
    const branch = await findBranch(name);
    if (branch === undefined) throw new Error(`no Neon branch named '${name}'`);
    return branch;
  }

  async function createBranch(name, parentName) {
    const parent = await requireBranch(parentName);
    const { branch } = await call('POST', `${projectUrl}/branches`, {
      branch: { name, parent_id: parent.id },
      endpoints: [{ type: READ_WRITE_ENDPOINT }],
    });
    return { id: branch.id, name: branch.name };
  }

  async function deleteBranch(name) {
    const branch = await findBranch(name);
    if (branch === undefined) return false;
    await call('DELETE', `${projectUrl}/branches/${branch.id}`);
    return true;
  }

  async function connectionUri({ branchName, database, role, pooled }) {
    const branch = await requireBranch(branchName);
    const query = new URLSearchParams({
      branch_id: branch.id,
      database_name: database,
      role_name: role,
      pooled: String(pooled),
    });
    const { uri } = await call('GET', `${projectUrl}/connection_uri?${query}`);
    return uri;
  }

  return { listBranches, findBranch, createBranch, deleteBranch, connectionUri };
}

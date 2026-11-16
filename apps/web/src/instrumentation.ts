const NODE_RUNTIME = 'nodejs';
const CONFIG_LOG = 'config:';

export async function register(): Promise<void> {
  if (process.env.NEXT_RUNTIME !== NODE_RUNTIME) return;
  const { configProblems } = await import('./server/config');
  for (const problem of configProblems()) console.error(CONFIG_LOG, problem.group, problem.message);
}

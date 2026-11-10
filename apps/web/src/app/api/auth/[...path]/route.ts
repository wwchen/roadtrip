import { neonAuth } from '@/server/auth/neonAuth';
import { apiError } from '@/server/contractIds';

export const dynamic = 'force-dynamic';

const STATUS_NOT_FOUND = 404;
const NOT_FOUND = 'not_found';

type Handler = (request: Request, context?: unknown) => Promise<Response> | Response;

function delegate(method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'): Handler {
  return (request, context) => {
    const auth = neonAuth();
    if (auth === null) return apiError(STATUS_NOT_FOUND, NOT_FOUND);
    const handlers = auth.handler() as unknown as Record<string, Handler>;
    return (handlers[method] as Handler)(request, context);
  };
}

export const GET = delegate('GET');
export const POST = delegate('POST');
export const PUT = delegate('PUT');
export const PATCH = delegate('PATCH');
export const DELETE = delegate('DELETE');

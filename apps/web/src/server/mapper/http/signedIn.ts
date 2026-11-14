import { jsonBody } from '@/server/requestBody';
import type { ActiveUser } from '@/server/service/auth/model';
import { errorAnswer, STATUS_UNAUTHORIZED, UNAUTHENTICATED, type HttpAnswer } from './httpAnswer';

const CACHE_CONTROL = 'cache-control';
const NO_STORE = 'private, no-store';
const SET_COOKIE = 'set-cookie';

export interface SignedInInput {
  user: ActiveUser;
  readJson(): Promise<unknown>;
  request: Request | undefined;
}

export type SignedInHandler = (input: SignedInInput) => Promise<HttpAnswer<unknown>>;
export type CurrentUser = () => Promise<ActiveUser | null>;
export type RouteHandler = (request?: Request) => Promise<Response>;

function respond(answer: HttpAnswer<unknown>): Response {
  const headers = new Headers({ [CACHE_CONTROL]: NO_STORE });
  if (answer.cookie !== undefined) headers.append(SET_COOKIE, answer.cookie);
  return Response.json(answer.body, { status: answer.status, headers });
}

export function signedInRoutes(currentUser: CurrentUser): (handler: SignedInHandler) => RouteHandler {
  return (handler) => async (request) => {
    const user = await currentUser();
    if (user === null) return respond(errorAnswer(STATUS_UNAUTHORIZED, UNAUTHENTICATED));
    const readJson = async (): Promise<unknown> => (request === undefined ? undefined : jsonBody(request));
    return respond(await handler({ user, readJson, request }));
  };
}

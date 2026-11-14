import type { ApiErrorSchema } from '@/server/contract';

export const STATUS_OK = 200;
export const STATUS_BAD_REQUEST = 400;
export const STATUS_UNAUTHORIZED = 401;
export const STATUS_FORBIDDEN = 403;
export const STATUS_CONFLICT = 409;
export const STATUS_UNPROCESSABLE = 422;
export const STATUS_BAD_GATEWAY = 502;
export const STATUS_SERVICE_UNAVAILABLE = 503;

export const UNAUTHENTICATED = 'unauthenticated';
export const INVALID_BODY = 'invalid_body';
export const INVALID_FIELD = 'invalid_field';
export const INVALID_BODY_DETAIL = 'Request body is not valid JSON for this endpoint';

export interface HttpAnswer<T> {
  status: number;
  body: T | ApiErrorSchema;
  cookie?: string;
}

export type Parsed<T> = { kind: 'ok'; value: T } | { kind: 'invalid'; answer: HttpAnswer<never> };

export function okAnswer<T>(body: T, cookie?: string): HttpAnswer<T> {
  return cookie === undefined ? { status: STATUS_OK, body } : { status: STATUS_OK, body, cookie };
}

export function withDetail(detail: string | undefined): { detail?: string } {
  return detail === undefined ? {} : { detail };
}

export function errorAnswer(status: number, code: string, detail?: string): HttpAnswer<never> {
  return { status, body: { error: code, ...withDetail(detail) } };
}

export function isTextOrAbsent(value: unknown): value is string | null | undefined {
  return value == null || typeof value === 'string';
}

export function invalidBody(detail?: string): Parsed<never> {
  return { kind: 'invalid', answer: errorAnswer(STATUS_BAD_REQUEST, INVALID_BODY, detail) };
}

import { describe, expect, test } from 'vitest';
import { HttpError } from '@/api/http';
import { describeError, errorFactsOf, errorReport, rawErrorText } from './error-report';

function httpError(status: number, code?: string, detail?: string): HttpError {
  const err = new HttpError('/api/x', status);
  err.code = code;
  err.detail = detail;
  return err;
}

describe('errorFactsOf', () => {
  test('an HttpError keeps its code, detail and status', () => {
    expect(errorFactsOf(httpError(400, 'invalid_field', 'theme must be one of light, dark'))).toEqual({
      code: 'invalid_field',
      detail: 'theme must be one of light, dark',
      status: 400,
    });
  });

  test('an answer envelope reads its error field as the code', () => {
    expect(errorFactsOf({ status: 'failed', error: 'login_failed', detail: 'Invalid password' })).toEqual({
      code: 'login_failed',
      detail: 'Invalid password',
      status: null,
    });
  });

  test('a thrown error with no code keeps its class and message', () => {
    expect(errorFactsOf(new SyntaxError('Unexpected token < in JSON'))).toEqual({
      code: 'SyntaxError',
      detail: 'Unexpected token < in JSON',
      status: null,
    });
  });

  test('an HttpError whose body carried no code keeps its class, url and status', () => {
    expect(errorFactsOf(httpError(502))).toEqual({ code: 'HttpError', detail: '/api/x: HTTP 502', status: 502 });
  });

  test('a numeric legacy DOMException code is not taken for the error code', () => {
    const abort = new DOMException('The user aborted a request.', 'AbortError');
    expect(errorFactsOf(abort).code).toBe('AbortError');
  });

  test('an availability-style error reads httpStatus', () => {
    expect(errorFactsOf({ code: 'rate_limited', httpStatus: 429 }).status).toBe(429);
  });

  test('a bare string is the detail', () => {
    expect(errorFactsOf('boom')).toEqual({ code: null, detail: 'boom', status: null });
  });
});

describe('errorReport', () => {
  test('a known code shows its sentence and the raw code, detail and status', () => {
    expect(errorReport('Slack rejected this token.', errorFactsOf(httpError(400, 'slack_invalid_auth', 'invalid_auth')))).toBe(
      'Slack rejected this token. (slack_invalid_auth: invalid_auth · HTTP 400)',
    );
  });

  test('an unknown code renders verbatim with its detail', () => {
    expect(errorReport(undefined, errorFactsOf(httpError(502, 'brand_new_code', 'upstream said no')))).toBe(
      'brand_new_code: upstream said no · HTTP 502',
    );
  });

  test('a sentence with nothing raw behind it stands alone', () => {
    expect(errorReport('Done.', errorFactsOf(undefined))).toBe('Done.');
  });

  test('no sentence and nothing raw still says so rather than going blank', () => {
    expect(rawErrorText(errorFactsOf(undefined))).toBe('');
    expect(errorReport(undefined, errorFactsOf(undefined))).not.toBe('');
  });
});

describe('describeError', () => {
  test('looks the code up and keeps the raw facts', () => {
    const copy = new Map([['login_failed', 'Rejected.']]);
    expect(describeError(httpError(401, 'login_failed', 'bad password'), (code) => copy.get(code))).toBe(
      'Rejected. (login_failed: bad password · HTTP 401)',
    );
  });

  test('a TypeError from fetch is shown as itself', () => {
    expect(describeError(new TypeError('Failed to fetch'))).toBe('TypeError: Failed to fetch');
  });
});

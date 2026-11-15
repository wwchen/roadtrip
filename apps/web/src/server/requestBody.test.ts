import { expect, test } from 'vitest';
import { jsonBody } from './requestBody';

const URL_ = 'https://roadtrip.test/api/pois';
const BODY = { a: 1 };

function post(body: string, contentType?: string): Request {
  return new Request(URL_, { method: 'POST', body, headers: contentType === undefined ? {} : { 'content-type': contentType } });
}

test('a JSON body declared as JSON is parsed, with or without parameters', async () => {
  expect(await jsonBody(post(JSON.stringify(BODY), 'application/json'))).toEqual(BODY);
  expect(await jsonBody(post(JSON.stringify(BODY), 'Application/JSON; charset=utf-8'))).toEqual(BODY);
});

test('a body declared as anything but JSON is not read, so a plain form cannot reach a handler', async () => {
  for (const type of ['text/plain', 'application/x-www-form-urlencoded', 'multipart/form-data; boundary=x']) {
    const request = post(JSON.stringify(BODY), type);
    expect(await jsonBody(request)).toBeUndefined();
    expect(request.bodyUsed).toBe(false);
  }
});

test('a body with no declared type, or one that does not parse, is undefined', async () => {
  const undeclared = new Request(URL_, { method: 'POST', body: new Uint8Array(Buffer.from(JSON.stringify(BODY))) });
  expect(await jsonBody(undeclared)).toBeUndefined();
  expect(await jsonBody(post('{not json', 'application/json'))).toBeUndefined();
});

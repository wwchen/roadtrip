const BROWSER_HEADERS = { 'content-type': 'application/json', 'sec-fetch-site': 'same-origin' };

export function browserRequest(url: string, init: RequestInit = {}): Request {
  const headers = new Headers(BROWSER_HEADERS);
  new Headers(init.headers).forEach((value, name) => headers.set(name, value));
  return new Request(url, { ...init, headers });
}

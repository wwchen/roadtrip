export class HttpError extends Error {
  constructor(url, status) {
    super(`${url}: HTTP ${status}`);
    this.name = 'HttpError';
    this.url = url;
    this.status = status;
  }
}

export function jsonPost(url, body, { signal } = {}) {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });
}

export async function jsonPostOk(url, body, options = {}) {
  const response = await jsonPost(url, body, options);
  if (!response.ok) throw new HttpError(url, response.status);
  return response.json();
}

export async function jsonGetOk(url, { signal } = {}) {
  const response = await fetch(url, { signal });
  if (!response.ok) throw new HttpError(url, response.status);
  return response.json();
}

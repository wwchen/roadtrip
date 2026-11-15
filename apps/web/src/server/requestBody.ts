const CONTENT_TYPE = 'content-type';
const JSON_MEDIA_TYPE = 'application/json';
const PARAMETER_SEPARATOR = ';';

function declaresJson(request: Request): boolean {
  const declared = request.headers.get(CONTENT_TYPE)?.split(PARAMETER_SEPARATOR)[0]?.trim().toLowerCase();
  return declared === JSON_MEDIA_TYPE;
}

export async function jsonBody(request: Request): Promise<unknown> {
  if (!declaresJson(request)) return undefined;
  try {
    return JSON.parse(await request.text());
  } catch {
    return undefined;
  }
}

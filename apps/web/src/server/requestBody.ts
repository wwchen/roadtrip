export async function jsonBody(request: Request): Promise<unknown> {
  try {
    return JSON.parse(await request.text());
  } catch {
    return undefined;
  }
}

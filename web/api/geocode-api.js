export async function geocode(query, { autocomplete = true, limit = 5, proximity = null, signal } = {}) {
  const params = new URLSearchParams({
    q: query,
    autocomplete: autocomplete ? '1' : '0',
    limit: String(limit),
  });
  if (proximity) params.set('proximity', proximity);

  const response = await fetch(`/api/geocode?${params.toString()}`, { signal });
  return response.ok ? response.json() : { results: [] };
}


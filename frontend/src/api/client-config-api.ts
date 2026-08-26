import { jsonGetOk, type RequestOptions } from './http';

export const CLIENT_CONFIG_URL = '/api/client-config';

export interface ClientConfig {
  carto_basemaps_api_key?: string | null;
}

const DEFAULT_CLIENT_CONFIG: ClientConfig = {};

let currentConfig: ClientConfig = DEFAULT_CLIENT_CONFIG;

function normalizedApiKey(value: string | null | undefined): string | null {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

export function applyClientConfig(config: ClientConfig): void {
  const cartoApiKey = normalizedApiKey(config.carto_basemaps_api_key);
  currentConfig = cartoApiKey ? { carto_basemaps_api_key: cartoApiKey } : DEFAULT_CLIENT_CONFIG;
}

export function resetClientConfig(): void {
  currentConfig = DEFAULT_CLIENT_CONFIG;
}

export function cartoBasemapsApiKey(): string | null {
  return currentConfig.carto_basemaps_api_key ?? null;
}

export function fetchClientConfig(options: RequestOptions = {}): Promise<ClientConfig> {
  return jsonGetOk<ClientConfig>(CLIENT_CONFIG_URL, options);
}

export async function loadClientConfig(options: RequestOptions = {}): Promise<ClientConfig> {
  const config = await fetchClientConfig(options);
  applyClientConfig(config);
  return config;
}

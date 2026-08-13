import { jsonGetOk, type RequestOptions } from './http';

/**
 * A node in the Atlas index tree.
 *
 * The hierarchy is `region -> classification -> campground -> campsite`, addressed
 * by a dot-delimited `key` (`""` -> `"UT"` -> `"UT.national"` -> `"UT.national.8252"`).
 * Field names are snake_case to mirror the backend JSON exactly, matching the
 * convention `campsite-api.ts` already follows.
 */
export type AtlasNodeKind = 'region' | 'classification' | 'campground' | 'campsite';

export interface AtlasNode {
  /** Dot-delimited address; this is also the `path` used to expand the node. */
  key: string;
  label: string;
  kind: AtlasNodeKind;
  /** The true rolled-up total this node advertises; rendered as its count. */
  child_count: number;
  /** False for campsite leaves (and campgrounds with zero sites). */
  has_children: boolean;
  /** A short peek at child labels; may be empty. */
  teaser: string[];
  /** Present on some campground nodes; deep-links into the map page. */
  poi_id?: number;
}

/** Mirrors the `GET /api/atlas/node` response. */
export interface AtlasNodeResponse {
  /** Echoes the requested key (the root request uses the empty string). */
  path: string;
  children: AtlasNode[];
}

export function fetchAtlasNode(
  path: string,
  { signal }: RequestOptions = {},
): Promise<AtlasNodeResponse> {
  return jsonGetOk<AtlasNodeResponse>(atlasNodeUrl(path), { signal });
}

export function atlasNodeUrl(path: string): string {
  return `/api/atlas/node?path=${encodeURIComponent(path)}`;
}

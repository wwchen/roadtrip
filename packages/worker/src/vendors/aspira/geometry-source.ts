import type { NamedPoint } from './named-point.js';

export interface GeometrySource {
  points(): Iterable<NamedPoint>;
}

import type { Envelope } from './envelope.js';

export class InputBundle {
  constructor(private readonly rawCaptures: Map<string, Envelope[]>) {}

  envelopes(slug: string): Envelope[] {
    const found = this.rawCaptures.get(slug);
    if (found === undefined) throw new Error(`input '${slug}' is not a declared data_source input`);
    return found;
  }

  envelope(slug: string): Envelope {
    const envelopes = this.envelopes(slug);
    const [only] = envelopes;
    if (envelopes.length !== 1 || only === undefined) {
      throw new Error(`input '${slug}' is multi-part (${envelopes.length} pages); use envelopes(slug) instead`);
    }
    return only;
  }

  soleEnvelopes(): Envelope[] {
    if (this.rawCaptures.size !== 1) {
      throw new Error(`soleEnvelopes() requires exactly one data_source input; got ${this.rawCaptures.size}`);
    }
    const [sole] = this.rawCaptures.values();
    return sole as Envelope[];
  }

  soleEnvelope(): Envelope {
    const envelopes = this.soleEnvelopes();
    const [only] = envelopes;
    if (envelopes.length !== 1 || only === undefined) {
      throw new Error(`expected exactly one envelope, got ${envelopes.length}`);
    }
    return only;
  }

  dataSourceSlugs(): string[] {
    return [...this.rawCaptures.keys()];
  }
}

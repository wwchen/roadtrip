# Fonts

Three families, all under the SIL Open Font License 1.1. `OFL.txt` is the
licence as shipped by [Collletttivo](https://www.collletttivo.it) and covers
Ronzino and Coconat; `OFL-MartianMono.txt` is the one shipped by
[Evil Martians](https://evilmartians.com) and covers Martian Mono.

| Family | Foundry | Role | Weights here |
|---|---|---|---|
| **Ronzino** | Collletttivo | `--th-body`, `--th-ui` | 400 Regular · 500 Medium · 700 Bold |
| **Coconat** | Collletttivo | `--th-display` | 400 Regular · 600 Demi · 700 Bold |
| **Martian Mono** | Evil Martians | `--th-mono` | 400 Regular · 500 Medium · 600 SemiBold |

Obliques are not shipped. Core sets no italic anywhere, so loading them would
be four files nothing renders.

These are self-hosted rather than loaded from a font CDN. LDS installs over npm
from GitHub, and a stylesheet that names a face it does not carry renders
differently depending on whether the consumer happened to add the same
`<link>`. The OFL permits redistribution as long as the licence travels with
the files — hence the `OFL*.txt` sitting next to them rather than a line in a
README somewhere.

**Do not sell these files on their own.** That is the one thing the OFL forbids,
and it is the only restriction that applies to bundling them here.

export const SOURCE_EXTENSION = /\.[cm]?[jt]sx?$/;
export const SKIPPED_DIRECTORIES = new Set(['node_modules', 'dist', 'storybook-static']);

const BLOCK_COMMENT = String.raw`\/\*[^*]*\*+(?:[^/*][^*]*\*+)*\/`;
const GAP = String.raw`(?:\s|${BLOCK_COMMENT}|\/\/[^\r\n]*[\r\n])*`;
const QUOTED_SPECIFIER = "['\"`]([^'\"`\\r\\n]+)['\"`]";

const MODULE_SPECIFIER = new RegExp(
  [
    String.raw`\bfrom${GAP}${QUOTED_SPECIFIER}`,
    String.raw`\bimport${GAP}(?:\(${GAP})?${QUOTED_SPECIFIER}`,
    String.raw`\brequire${GAP}\(${GAP}${QUOTED_SPECIFIER}`,
  ].join('|'),
  'g',
);

export function findModuleSpecifiers(source) {
  return [...source.matchAll(MODULE_SPECIFIER)].map((match) => ({
    specifier: match[1] ?? match[2] ?? match[3],
    line: source.slice(0, match.index).split('\n').length,
  }));
}

export function isPackageSpecifier(specifier, packageName) {
  return (
    specifier === packageName ||
    specifier.startsWith(`${packageName}/`) ||
    specifier.startsWith(`${packageName}\${`)
  );
}

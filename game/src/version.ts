declare const __ASSET_VERSION__: string;
declare const __ASSET_VERSIONS__: Record<string, string>;

/**
 * Theme files and sounds are asked for with ?v=<fingerprint>, made when the game is built (vite.config.ts), so they
 * can be cached for a year and a changed file gets a new address (review 4.2). The fingerprint is per theme folder
 * and for the shared sounds (review N12): a new theme doesn't change the addresses of the others.
 */
const VERSIONS: Readonly<Record<string, string>> = __ASSET_VERSIONS__;

/** The address of a file in public/ with its folder's fingerprint, e.g. "/themes/x/logo.png?v=3f2a…". */
export function assetUrl(path: string): string {
  let folder = '';
  for (const prefix of Object.keys(VERSIONS)) {
    if (path.startsWith(prefix) && prefix.length > folder.length) folder = prefix;
  }
  return path + '?v=' + (folder ? VERSIONS[folder] : __ASSET_VERSION__);
}

/**
 * The fingerprint of everything in public/, for an address built as path + ASSET_VERSION. Any change in any theme
 * changes it, so prefer assetUrl(path).
 */
export const ASSET_VERSION = '?v=' + __ASSET_VERSION__;

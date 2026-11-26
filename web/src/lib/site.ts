import fs from 'node:fs';
import path from 'node:path';

/** A site path with the base prefix, for links in components such as `<LinkCard href={withBase('/minecraft/')} />`. */
export function withBase(target: string): string {
	const base = import.meta.env.BASE_URL.replace(/\/$/, '');
	return target.startsWith('/') ? base + target : target;
}

/**
 * A file elsewhere in the FilaSignal repository, read at build time. The site builds from `web/`, so the repository
 * root is its parent. Reference pages read the mod's recipes and the verified examples this way instead of copying them.
 */
export function repoFile(...parts: string[]): string {
	return fs.readFileSync(path.resolve(process.cwd(), '..', ...parts), 'utf-8').replace(/\r\n/g, '\n');
}

export function repoJson<T>(...parts: string[]): T {
	return JSON.parse(repoFile(...parts)) as T;
}

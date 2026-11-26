/**
 * Checks the built site in dist/ the way GitHub Pages will serve it under /filasignal/:
 * - every internal link and asset resolves to a built file, and every #fragment exists on its page;
 * - root-relative URLs carry the base path;
 * - the search index and the key pages exist;
 * - no placeholder text reached a page.
 * Run after `astro build`; `bun run build` does both.
 */
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, posix, relative, sep } from 'node:path';

const BASE = '/filasignal';
const DIST = join(import.meta.dir, '..', 'dist');
const REQUIRED = [
	'index.html',
	'language/index.html',
	'language/getting-started/install/index.html',
	'language/reference/standard-library/index.html',
	'minecraft/index.html',
	'minecraft/getting-started/first-automation/index.html',
	'minecraft/guides/editor/index.html',
	'minecraft/hardware/index.html',
	'minecraft/hardware/computer/index.html',
	'minecraft/api/index.html',
	'minecraft/tutorials/index.html',
	'404.html',
	'pagefind/pagefind.js',
];
// Pages the mod links to from its screens.
const HARDWARE = ['computer', 'network-repeater', 'network-interface', 'sensor', 'entity-detector', 'weather-station',
	'redstone-controller', 'inventory-module', 'environment-sensor', 'display-module', 'alarm-module', 'storage-server',
	'access-controller', 'control-panel', 'wireless-bridge'];
const PLACEHOLDERS = /\b(TODO|TBD|FIXME|lorem ipsum)\b/i;

const problems: string[] = [];

function walk(dir: string): string[] {
	return readdirSync(dir).flatMap((name) => {
		const path = join(dir, name);
		return statSync(path).isDirectory() ? walk(path) : [path];
	});
}

const pages = walk(DIST).filter((file) => file.endsWith('.html'));
const ids = new Map<string, Set<string>>();
for (const page of pages) {
	const html = readFileSync(page, 'utf-8');
	ids.set(page, new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((match) => match[1])));
}

/** The built file a site path points to, or null. */
function target(path: string): string | null {
	const clean = decodeURIComponent(path);
	const file = join(DIST, ...clean.split('/').filter(Boolean));
	if (clean.endsWith('/')) {
		return existsSync(join(file, 'index.html')) ? join(file, 'index.html') : null;
	}
	if (existsSync(file) && statSync(file).isFile()) return file;
	return existsSync(join(file, 'index.html')) ? join(file, 'index.html') : null;
}

for (const page of pages) {
	const html = readFileSync(page, 'utf-8');
	const where = relative(DIST, page).split(sep).join('/');
	const pageUrl = `${BASE}/${where.replace(/index\.html$/, '')}`;
	const text = html.replace(/<script[\s\S]*?<\/script>|<style[\s\S]*?<\/style>|<[^>]+>/g, ' ');
	if (PLACEHOLDERS.test(text)) problems.push(`${where}: placeholder text "${text.match(PLACEHOLDERS)?.[0]}"`);
	for (const match of html.matchAll(/\s(?:href|src)="([^"]+)"/g)) {
		const raw = match[1].replace(/&amp;/g, '&');
		if (/^(https?:|mailto:|data:|javascript:|#$)/.test(raw)) continue;
		const [pathPart, fragment] = raw.split('#');
		let path = pathPart.split('?')[0];
		if (path === '') path = pageUrl;
		if (!path.startsWith('/')) path = posix.join(posix.dirname(pageUrl + 'x'), path) + (path.endsWith('/') ? '/' : '');
		if (!path.startsWith(`${BASE}/`) && path !== BASE) {
			problems.push(`${where}: link without the ${BASE} base: ${raw}`);
			continue;
		}
		const file = target(path.slice(BASE.length) || '/');
		if (!file) {
			problems.push(`${where}: broken link ${raw}`);
		} else if (fragment && file.endsWith('.html') && !ids.get(file)?.has(decodeURIComponent(fragment))) {
			problems.push(`${where}: missing anchor ${raw}`);
		}
	}
}

for (const page of [...REQUIRED, ...HARDWARE.map((name) => `minecraft/hardware/${name}/index.html`)]) {
	if (!existsSync(join(DIST, page))) problems.push(`missing page ${page}`);
}

if (problems.length > 0) {
	console.error(`Site check failed with ${problems.length} problem(s):\n${problems.join('\n')}`);
	process.exit(1);
}
console.log(`Site check passed: ${pages.length} pages, links, anchors, base path and search index.`);

/**
 * Prefixes root-relative links in Markdown and MDX content with the site's base path, so authors write
 * [the editor](/minecraft/guides/editor/) and the page works under https://venuss1337.github.io/filasignal/.
 */
export function rehypeBaseLinks({ base }) {
	const prefix = base.replace(/\/$/, '');
	const visit = (node) => {
		if (node.type === 'element' && node.tagName === 'a' && typeof node.properties?.href === 'string') {
			const href = node.properties.href;
			if (href.startsWith('/') && !href.startsWith('//') && !href.startsWith(`${prefix}/`)) {
				node.properties.href = prefix + href;
			}
		}
		for (const child of node.children ?? []) visit(child);
	};
	return (tree) => visit(tree);
}

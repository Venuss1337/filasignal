// @ts-check
import fs from 'node:fs';
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightSidebarTopics from 'starlight-sidebar-topics';
import { unified } from '@astrojs/markdown-remark';
import { rehypeBaseLinks } from './src/plugins/rehype-base-links.mjs';

// GitHub Pages project site: https://venuss1337.github.io/filasignal/
const site = 'https://venuss1337.github.io';
const base = '/filasignal';
const fsig = JSON.parse(fs.readFileSync(new URL('./src/grammars/fsig.tmLanguage.json', import.meta.url), 'utf-8'));

export default defineConfig({
	site,
	base,
	trailingSlash: 'always',
	markdown: { processor: unified({ rehypePlugins: [[rehypeBaseLinks, { base }]] }) },
	integrations: [
		starlight({
			title: 'FilaSignal',
			description: 'Program Minecraft automation with FilaScript: guides, hardware, tutorials and the language reference.',
			logo: { src: './src/assets/logo.svg', alt: 'FilaSignal computer' },
			favicon: '/favicon.svg',
			social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/Venuss1337/filasignal' }],
			editLink: { baseUrl: 'https://github.com/Venuss1337/filasignal/edit/main/web/' },
			customCss: ['./src/styles/theme.css'],
			lastUpdated: false,
			expressiveCode: { shiki: { langs: [fsig] } },
			plugins: [
				starlightSidebarTopics([
					{
						label: 'Minecraft mod',
						link: '/minecraft/',
						icon: 'puzzle',
						items: [
							{
								label: 'Start here',
								items: [
									{ label: 'What is FilaSignal?', link: '/minecraft/' },
									'minecraft/getting-started/installation',
									'minecraft/getting-started/first-automation',
									'minecraft/getting-started/survival',
								],
							},
							{ label: 'Guides', items: [{ autogenerate: { directory: 'minecraft/guides' } }] },
							{ label: 'Hardware', collapsed: true, items: [{ autogenerate: { directory: 'minecraft/hardware' } }] },
							{
								label: 'Tutorials',
								items: [
									{ label: 'All tutorials', link: '/minecraft/tutorials/' },
									{ label: 'Beginner', items: [{ autogenerate: { directory: 'minecraft/tutorials/beginner' } }] },
									{ label: 'Intermediate', collapsed: true, items: [{ autogenerate: { directory: 'minecraft/tutorials/intermediate' } }] },
									{ label: 'Advanced', collapsed: true, items: [{ autogenerate: { directory: 'minecraft/tutorials/advanced' } }] },
								],
							},
							{ label: 'API reference', collapsed: true, items: [{ autogenerate: { directory: 'minecraft/api' } }] },
						],
					},
					{
						label: 'FilaScript language',
						link: '/language/',
						icon: 'pencil',
						items: [
							{
								label: 'Start here',
								items: [
									{ label: 'What is FilaScript?', link: '/language/' },
									'language/getting-started/install',
									'language/getting-started/first-program',
									'language/getting-started/reading-errors',
								],
							},
							{ label: 'Learn the language', items: [{ autogenerate: { directory: 'language/learn' } }] },
							{ label: 'Reference', items: [{ autogenerate: { directory: 'language/reference' } }] },
						],
					},
				]),
			],
		}),
	],
});

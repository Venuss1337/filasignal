/**
 * Puts the vanilla item icons used by the crafting recipes into public/textures/minecraft/ before a build.
 *
 * The icons are Mojang's textures, so they are not stored in this repository: they are read from the official
 * Minecraft client JAR for the mod's Minecraft version, downloaded from Mojang's launcher metadata (checked against
 * its SHA-1) and cached in web/.cache/. Only the handful of textures the recipes need are written. A recipe ingredient
 * without an icon below fails the build, so the documentation never shows a recipe it cannot draw.
 */
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { inflateRawSync } from 'node:zlib';
import sharp from 'sharp';

const web = join(import.meta.dir, '..');
const root = join(web, '..');
const out = join(web, 'public', 'textures', 'minecraft');
const cache = join(web, '.cache');

type Icon = { file: string } | { compose: (jar: Jar) => Promise<Buffer> };

/** Each ingredient's icon: its item texture, or for block-shaped items the face a player recognizes. */
const ICONS: Record<string, Icon> = {
	amethyst_shard: { file: 'item/amethyst_shard' },
	clock: { file: 'item/clock_00' },
	comparator: { file: 'item/comparator' },
	copper_ingot: { file: 'item/copper_ingot' },
	ender_pearl: { file: 'item/ender_pearl' },
	glass: { file: 'block/glass' },
	glass_pane: { file: 'block/glass' },
	gold_ingot: { file: 'item/gold_ingot' },
	gold_nugget: { file: 'item/gold_nugget' },
	hopper: { file: 'item/hopper' },
	iron_ingot: { file: 'item/iron_ingot' },
	lightning_rod: { file: 'block/lightning_rod' },
	note_block: { file: 'block/note_block' },
	observer: { file: 'block/observer_front' },
	redstone: { file: 'item/redstone' },
	repeater: { file: 'item/repeater' },
	stick: { file: 'item/stick' },
	tripwire_hook: { file: 'block/tripwire_hook' },
	// The chest item is a 3D model: draw its front from the chest texture (lid and base strips are stored flipped).
	chest: {
		compose: async (jar) => {
			const sheet = jar.read('entity/chest/normal');
			const part = (left: number, top: number, width: number, height: number) =>
				sharp(sheet).extract({ left, top, width, height }).flip().png().toBuffer();
			return sharp({ create: { width: 16, height: 16, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } } })
				.composite([
					{ input: await part(42, 14, 14, 5), left: 1, top: 1 },
					{ input: await part(42, 33, 14, 10), left: 1, top: 6 },
					{ input: await part(1, 1, 2, 4), left: 7, top: 4 },
				])
				.png()
				.toBuffer();
		},
	},
	// Any button: a small plank button, as in the recipe book's first choice.
	'tag/buttons': {
		compose: async (jar) => {
			const plank = await sharp(jar.read('block/oak_planks')).extract({ left: 5, top: 6, width: 6, height: 4 }).png().toBuffer();
			return sharp({ create: { width: 16, height: 16, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } } })
				.composite([{ input: plank, left: 5, top: 6 }])
				.png()
				.toBuffer();
		},
	},
};

/** The icon name for an ingredient ID, shared with Recipe.astro. */
function iconName(id: string): string {
	return id.startsWith('#minecraft:') ? `tag/${id.slice(11)}` : id.replace(/^minecraft:/, '');
}

class Jar {
	private readonly entries = new Map<string, { offset: number; method: number; size: number }>();

	constructor(private readonly data: Buffer) {
		// End of central directory, then the central directory itself.
		let end = data.length - 22;
		while (data.readUInt32LE(end) !== 0x06054b50) end--;
		let at = data.readUInt32LE(end + 16);
		for (let i = 0, n = data.readUInt16LE(end + 10); i < n; i++) {
			const nameLength = data.readUInt16LE(at + 28);
			const name = data.toString('utf8', at + 46, at + 46 + nameLength);
			this.entries.set(name, { method: data.readUInt16LE(at + 10), size: data.readUInt32LE(at + 20), offset: data.readUInt32LE(at + 42) });
			at += 46 + nameLength + data.readUInt16LE(at + 30) + data.readUInt16LE(at + 32);
		}
	}

	read(texture: string): Buffer {
		const entry = this.entries.get(`assets/minecraft/textures/${texture}.png`);
		if (!entry) throw new Error(`The Minecraft client has no texture ${texture}`);
		const start = entry.offset + 30 + this.data.readUInt16LE(entry.offset + 26) + this.data.readUInt16LE(entry.offset + 28);
		const raw = this.data.subarray(start, start + entry.size);
		return entry.method === 0 ? Buffer.from(raw) : inflateRawSync(raw);
	}
}

async function clientJar(version: string): Promise<Buffer> {
	const file = join(cache, `minecraft-client-${version}.jar`);
	if (existsSync(file)) return readFileSync(file);
	const manifest = await (await fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')).json();
	const entry = manifest.versions.find((v: { id: string }) => v.id === version);
	if (!entry) throw new Error(`Minecraft ${version} is not in Mojang's version manifest`);
	const client = (await (await fetch(entry.url)).json()).downloads.client;
	const data = Buffer.from(await (await fetch(client.url)).arrayBuffer());
	if (createHash('sha1').update(data).digest('hex') !== client.sha1) throw new Error('The Minecraft client download failed its checksum');
	mkdirSync(cache, { recursive: true });
	writeFileSync(file, data);
	return data;
}

const build = readFileSync(join(root, 'filasignal-fabric', 'build.gradle.kts'), 'utf8');
const version = build.match(/val minecraftVersion = "([^"]+)"/)?.[1];
if (!version) throw new Error('Could not read minecraftVersion from filasignal-fabric/build.gradle.kts');

const recipes = join(root, 'filasignal-fabric', 'src', 'main', 'resources', 'data', 'filasignal', 'recipe');
const needed = new Set<string>();
for (const name of readdirSync(recipes)) {
	const recipe = JSON.parse(readFileSync(join(recipes, name), 'utf8'));
	for (const id of [...Object.values<string>(recipe.key ?? {}), ...(recipe.ingredients ?? [])]) {
		if (!id.startsWith('filasignal:')) needed.add(iconName(id));
	}
}
const missing = [...needed].filter((name) => !ICONS[name]);
if (missing.length) throw new Error(`No icon defined for recipe ingredients: ${missing.join(', ')}`);

if ([...needed].every((name) => existsSync(join(out, `${name}.png`)))) {
	console.log(`Minecraft item icons: ${needed.size} already present.`);
} else {
	const jar = new Jar(await clientJar(version));
	for (const name of needed) {
		const icon = ICONS[name];
		const png = 'file' in icon ? jar.read(icon.file) : await icon.compose(jar);
		mkdirSync(join(out, name, '..'), { recursive: true });
		writeFileSync(join(out, `${name}.png`), png);
	}
	console.log(`Minecraft item icons: wrote ${needed.size} from the Minecraft ${version} client.`);
}

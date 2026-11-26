/**
 * The Minecraft API catalog, generated from the mod's host registries by DocumentationGameTests. Reference pages
 * render from it, so signatures, errors and events in the documentation match what programs actually run with.
 */
import catalog from '../data/generated/minecraft-api.json';

export interface Slot {
	name: string;
	types: string[];
	handleType: string | null;
}

export interface Fn {
	name: string;
	parameters: Slot[];
	returns: Slot[];
	valueOrError: boolean;
	documentation: string;
	capabilities: string[];
	cost: number;
	errors: string[];
	examples: string[];
	reference: boolean;
}

export interface Ev {
	name: string;
	filters: Slot[];
	parameters: string[];
	documentation: string;
}

export interface Obj {
	name: string;
	properties: Fn[];
	methods: Fn[];
	events: Ev[];
}

export interface Program {
	functions: Fn[];
	objects: Obj[];
	events: Ev[];
	enums: Record<string, string[]>;
}

export interface Kind {
	id: string;
	title: string;
	type: string | null;
	reference: string | null;
	capabilities: string[];
	programmable: boolean;
	events: Ev[];
	ownEvents: string[];
	localCapabilities: string[];
}

export const programs = catalog.programs as unknown as Record<string, Program>;
export const kinds = catalog.kinds as unknown as Kind[];
export const builtinEvents = catalog.builtinEvents as unknown as Ev[];
export const computerCapabilities = catalog.computerCapabilities as string[];

/** Documentation page for each device kind, as linked from the mod's help buttons. */
export const pages: Record<string, string> = {
	computer: 'computer',
	interface: 'network-interface',
	sensor: 'sensor',
	repeater: 'network-repeater',
	entity_detector: 'entity-detector',
	weather_station: 'weather-station',
	redstone_controller: 'redstone-controller',
	inventory_module: 'inventory-module',
	environment_sensor: 'environment-sensor',
	display: 'display-module',
	alarm: 'alarm-module',
	storage_server: 'storage-server',
	access_controller: 'access-controller',
	control_panel: 'control-panel',
	wireless_bridge: 'wireless-bridge',
};

/** Block or item registry ID for each kind, used for images and recipes. */
export const blockIds: Record<string, string> = {
	computer: 'programmable_computer',
	interface: 'network_interface',
	sensor: 'sensor',
	repeater: 'network_repeater',
	entity_detector: 'entity_detector',
	weather_station: 'weather_station',
	redstone_controller: 'redstone_controller',
	inventory_module: 'inventory_module',
	environment_sensor: 'environment_sensor',
	display: 'display_module',
	alarm: 'alarm_module',
	storage_server: 'storage_server',
	access_controller: 'access_controller',
	control_panel: 'control_panel',
	wireless_bridge: 'wireless_bridge',
};

export function kind(id: string): Kind {
	const found = kinds.find((entry) => entry.id === id);
	if (!found) throw new Error(`Unknown device kind ${id}`);
	return found;
}

export function program(id: string): Program {
	const found = programs[id];
	if (!found) throw new Error(`Unknown program target ${id}`);
	return found;
}

export function fn(programId: string, name: string): Fn {
	const found = program(programId).functions.find((entry) => entry.name === name);
	if (!found) throw new Error(`Unknown function ${name} for ${programId}`);
	return found;
}

export function object(programId: string, name: string): Obj {
	const found = program(programId).objects.find((entry) => entry.name === name);
	if (!found) throw new Error(`Unknown object ${name} for ${programId}`);
	return found;
}

/** The factory such as detectors.from for a kind's script type, from the computer's registry. */
export function factoryOf(type: string): Fn | undefined {
	return program('computer').functions.find(
		(entry) => !entry.reference && entry.returns[0]?.handleType === type && entry.name.split('.').length === 2,
	);
}

function typeName(slot: Slot): string {
	if (slot.handleType) return slot.handleType;
	const types = slot.types.filter((type) => type !== 'nil');
	if (types.length === 0) return 'nil';
	if (types.length > 4) return 'any value';
	return types.join(' or ');
}

/** `scan(filter)` for a method, `detectors.from(alias)` for a function. */
export function signature(entry: Fn, member: boolean): string {
	const parameters = member ? entry.parameters.slice(1) : entry.parameters;
	const short = member ? entry.name.slice(entry.name.indexOf('.') + 1) : entry.name;
	return `${short}(${parameters.map((slot) => slot.name).join(', ')})`;
}

/** A plain description of what a call gives back. */
export function resultText(entry: Fn): string {
	if (entry.returns.length === 0) return 'nothing';
	if (entry.valueOrError) return `${typeName(entry.returns[0])}, or an error`;
	return entry.returns.map(typeName).join(', ');
}

export function parameterText(entry: Fn, member: boolean): string {
	const parameters = member ? entry.parameters.slice(1) : entry.parameters;
	return parameters.map((slot) => `${slot.name}: ${typeName(slot)}`).join(', ');
}

/** `entityEnter(entity)` or `rainStart`. */
export function eventSignature(event: Ev): string {
	const filters = event.filters.map((slot) => `"${slot.name}"`);
	const all = [...filters, ...event.parameters];
	return all.length === 0 ? event.name : `${event.name}(${all.join(', ')})`;
}

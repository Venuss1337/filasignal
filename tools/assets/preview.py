"""Renders FilaSignal block and item models to PNG previews for visual review and the documentation site.

These are asset renders made outside Minecraft: an orthographic three-quarter view with Minecraft-style face
shading and nearest-texel sampling. They are not in-game screenshots.

    python tools/assets/preview.py OUTPUT_DIR [--size 256] [--sheet]
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "filasignal-fabric" / "src" / "main" / "resources" / "assets" / "filasignal"
SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "west": 0.6, "east": 0.6}
CUBE = [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {name: {"texture": f"#{name}"} for name in SHADE}}]


def load_model(reference: str) -> tuple[list, dict]:
    """Resolves a model's elements and textures through its parents. Vanilla cube parents are built in."""
    namespace, path = reference.split(":") if ":" in reference else ("minecraft", reference)
    if namespace == "minecraft":
        return (CUBE if path == "block/cube" else []), {}
    data = json.loads((ASSETS / "models" / f"{path}.json").read_text(encoding="utf-8"))
    elements, textures = load_model(data["parent"]) if "parent" in data else ([], {})
    textures = {**textures, **data.get("textures", {})}
    return data.get("elements", elements), textures


def resolve(textures: dict, key: str) -> np.ndarray:
    value = textures[key.lstrip("#")]
    while value.startswith("#"):
        value = textures[value[1:]]
    _, path = value.split(":")
    return np.asarray(Image.open(ASSETS / "textures" / f"{path}.png").convert("RGBA"), dtype=np.float32)


def corners(face: str, lo, hi):
    """Corners of a face as (top-left, top-right, bottom-left) seen from outside, with its default UV."""
    (x0, y0, z0), (x1, y1, z1) = lo, hi
    if face == "north":
        return (x1, y1, z0), (x0, y1, z0), (x1, y0, z0), (16 - x1, 16 - y1, 16 - x0, 16 - y0)
    if face == "south":
        return (x0, y1, z1), (x1, y1, z1), (x0, y0, z1), (x0, 16 - y1, x1, 16 - y0)
    if face == "west":
        return (x0, y1, z0), (x0, y1, z1), (x0, y0, z0), (z0, 16 - y1, z1, 16 - y0)
    if face == "east":
        return (x1, y1, z1), (x1, y1, z0), (x1, y0, z1), (16 - z1, 16 - y1, 16 - z0, 16 - y0)
    if face == "up":
        return (x0, y1, z0), (x1, y1, z0), (x0, y1, z1), (x0, z0, x1, z1)
    return (x0, y0, z1), (x1, y0, z1), (x0, y0, z0), (x0, 16 - z1, x1, 16 - z0)


def render(reference: str, size: int = 256, yaw: float = 35.0, pitch: float = 28.0, rotation: int = 0) -> Image.Image:
    elements, textures = load_model(reference)
    yaw, pitch = math.radians(yaw), math.radians(pitch)
    spin = math.radians(rotation)

    def project(point):
        x, y, z = (p - 8 for p in point)
        # Blockstate y rotation turns the model clockwise seen from above.
        x, z = x * math.cos(spin) - z * math.sin(spin), x * math.sin(spin) + z * math.cos(spin)
        # Camera: north-west of the block and above it, looking south-east. Smaller depth is nearer.
        right, ahead = -x * math.cos(yaw) + z * math.sin(yaw), x * math.sin(yaw) + z * math.cos(yaw)
        up, near = y * math.cos(pitch) + ahead * math.sin(pitch), -y * math.sin(pitch) + ahead * math.cos(pitch)
        scale = size / 28.0
        return np.array([size / 2 + right * scale, size / 2 - up * scale]), near

    image = np.zeros((size, size, 4), dtype=np.float32)
    depth = np.full((size, size), np.inf)
    ys, xs = np.mgrid[0:size, 0:size]
    pixels = np.stack([xs + 0.5, ys + 0.5], axis=-1)
    for element in elements:
        for name, spec in element["faces"].items():
            a, b, c, auto = corners(name, element["from"], element["to"])
            (pa, da), (pb, db), (pc, dc) = project(a), project(b), project(c)
            u0, v0, u1, v1 = spec.get("uv", auto)
            texture = resolve(textures, spec["texture"])
            # Affine inverse: pixel = pa + s * (pb - pa) + t * (pc - pa).
            basis = np.array([pb - pa, pc - pa]).T
            if abs(np.linalg.det(basis)) < 1e-6:
                continue
            st = (pixels - pa) @ np.linalg.inv(basis).T
            s, t = st[..., 0], st[..., 1]
            inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
            z = da + s * (db - da) + t * (dc - da)
            tex_h, tex_w = texture.shape[:2]
            u = np.clip(((u0 + s * (u1 - u0)) * tex_w / 16).astype(int), 0, tex_w - 1)
            v = np.clip(((v0 + t * (v1 - v0)) * tex_h / 16).astype(int), 0, tex_h - 1)
            sample = texture[v, u]
            visible = inside & (sample[..., 3] > 0) & (z < depth)
            shaded = sample.copy()
            shaded[..., :3] *= SHADE[name]
            image[visible] = shaded[visible]
            depth[visible] = z[visible]
    return Image.fromarray(image.clip(0, 255).astype(np.uint8))


def item_icon(name: str, size: int) -> Image.Image:
    texture = Image.open(ASSETS / "textures" / "item" / f"{name}.png").convert("RGBA")
    return texture.resize((size, size), Image.NEAREST)


BLOCKS = {
    "programmable_computer": "programmable_computer_running", "network_repeater": "network_repeater",
    "entity_detector": "entity_detector", "weather_station": "weather_station",
    "redstone_controller": "redstone_controller", "inventory_module": "inventory_module",
    "environment_sensor": "environment_sensor", "display_module": "display_module", "alarm_module": "alarm_module",
    "storage_server": "storage_server", "access_controller": "access_controller", "control_panel": "control_panel",
    "wireless_bridge": "wireless_bridge", "network_interface": "network_interface", "sensor": "sensor",
}


def site(output: Path) -> None:
    """Images for web/public/renders: blocks, every model state, item icons and a lineup of the hardware family."""
    for folder in ("blocks", "states", "items"):
        (output / folder).mkdir(parents=True, exist_ok=True)
    for name, model in BLOCKS.items():
        render(f"filasignal:block/{model}", 256).save(output / "blocks" / f"{name}.png", optimize=True)
    for model in sorted(p.stem for p in (ASSETS / "models" / "block").glob("*.json")):
        render(f"filasignal:block/{model}", 192).save(output / "states" / f"{model}.png", optimize=True)
    for name in ("data_drive", "linking_tool"):
        Image.open(ASSETS / "textures" / "item" / f"{name}.png").save(output / "items" / f"{name}.png", optimize=True)
    lineup = ["programmable_computer", "network_repeater", "entity_detector", "alarm_module", "display_module",
              "weather_station", "redstone_controller", "control_panel", "storage_server", "wireless_bridge"]
    cell = 160
    family = Image.new("RGBA", (cell * 5, cell * 2), (0, 0, 0, 0))
    for i, name in enumerate(lineup):
        family.alpha_composite(render(f"filasignal:block/{BLOCKS[name]}", cell), ((i % 5) * cell, (i // 5) * cell))
    family.save(output / "family.png", optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--size", type=int, default=256)
    parser.add_argument("--sheet", action="store_true", help="also write a contact sheet of every model and state")
    parser.add_argument("--site", action="store_true", help="write the documentation site's images instead")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.site:
        site(args.output)
        return
    for name, model in BLOCKS.items():
        render(f"filasignal:block/{model}", args.size).save(args.output / f"{name}.png")
    for name in ("data_drive", "linking_tool"):
        item_icon(name, args.size).save(args.output / f"{name}.png")
    if args.sheet:
        models = sorted(p.stem for p in (ASSETS / "models" / "block").glob("*.json"))
        cell = 192
        columns = 6
        rows = math.ceil(len(models) / columns)
        sheet = Image.new("RGBA", (columns * cell, rows * cell), (58, 62, 70, 255))
        for i, model in enumerate(models):
            sheet.alpha_composite(render(f"filasignal:block/{model}", cell), ((i % columns) * cell, (i // columns) * cell))
        sheet.save(args.output / "contact-sheet.png")
        back = Image.new("RGBA", (columns * cell, rows * cell), (58, 62, 70, 255))
        for i, model in enumerate(models):
            back.alpha_composite(render(f"filasignal:block/{model}", cell, yaw=215), ((i % columns) * cell, (i // columns) * cell))
        back.save(args.output / "contact-sheet-back.png")


if __name__ == "__main__":
    main()

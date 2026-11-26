"""Generates FilaSignal's block and item textures, block models, blockstates and item definitions.

This script is the editable source of the mod's art. Every device shares one casing and palette, and each
device adds a recognizable functional face. Run it from the repository root after changing the art:

    python tools/assets/generate.py

It writes into filasignal-fabric/src/main/resources/assets/filasignal. The generated files are committed, so
building the mod never needs Python. Requires Pillow.
"""
from __future__ import annotations

import json
import math
from pathlib import Path

from pixels import Tex, mix

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "filasignal-fabric" / "src" / "main" / "resources" / "assets" / "filasignal"
NS = "filasignal"

# ---------------------------------------------------------------------------------------------------------------
# Shared palette: warm industrial metal, dark recessed panels, green/amber/red indicators, copper and redstone.
# ---------------------------------------------------------------------------------------------------------------
C = dict(
    edge="#4a463f", shade="#837d70", base="#a49e90", base2="#9b9587", base3="#ada799", light="#c6c1b4",
    bolt="#dedace", bolt_shadow="#6b665c",
    panel="#25272b", panel2="#1b1d20", panel_edge="#3c3f45", panel_light="#50545c",
    green="#5bd672", green_dark="#2e7a3c", amber="#f2b33a", amber_dark="#9a6a1c", red="#e4493b", red_dark="#842821",
    copper="#c97c4f", copper_light="#e8a476", copper_dark="#86482c",
    redstone="#d42b1f", redstone_light="#ff6a58", redstone_dark="#76130c",
    glass="#79b6e2", glass_dark="#2f5e8c", glass_mid="#4f8bc2", glass_light="#d6edfb",
    screen="#0f2620", screen2="#133129", screen_text="#78e6a6", screen_dim="#3f8f66",
    amethyst="#a77bd9", amethyst_dark="#69499e", amethyst_light="#dbbef7",
    hazard="#e8b62e", hazard_dark="#2b2723",
    cream="#e9e1c9", cream_shade="#c9bf9f", key="#d4cfc2", key_shade="#8f897b",
)


def casing(seed: str, bolts: bool = True) -> Tex:
    """The shared hardware casing: grained metal, a dark outline, a raised bevel and four corner bolts."""
    t = Tex().noise([C["base"], C["base2"], C["base3"]], seed, weights=[6, 2, 2])
    t.frame(0, 0, 15, 15, C["edge"])
    t.bevel(1, 1, 14, 14, C["light"], C["shade"])
    if bolts:
        for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
            t.set(x, y, C["bolt"]).set(x + 1 if x < 8 else x - 1, y + 1 if y < 8 else y - 1, C["bolt_shadow"])
    return t


def recess(t: Tex, x0: int, y0: int, x1: int, y1: int, fill=None) -> Tex:
    """A dark recessed panel with a lit lower-right lip."""
    t.rect(x0, y0, x1, y1, fill or C["panel"])
    return t.inset(x0, y0, x1, y1, C["light"], C["panel_edge"]).rect(x0, y0, x1, y0, C["edge"]).rect(x0, y0, x0, y1, C["edge"])


def led(t: Tex, x: int, y: int, color: str, dark: str | None = None) -> Tex:
    """A one-pixel indicator with a darker pixel below it."""
    t.set(x, y, color)
    return t.set(x, y + 1, dark) if dark else t


def side() -> Tex:
    t = casing("side")
    for y in (5, 7, 9):
        t.rect(4, y, 11, y, C["panel2"]).rect(4, y + 1, 11, y + 1, C["light"])
    return t


def top() -> Tex:
    t = casing("top")
    t.frame(4, 4, 11, 11, C["shade"]).bevel(5, 5, 10, 10, C["light"], C["shade"])
    return t


def bottom() -> Tex:
    return casing("bottom", bolts=False)


# --------------------------------------------------------------- networking ------------------------------------

def repeater_side() -> Tex:
    t = casing("repeater")
    recess(t, 2, 5, 13, 11)
    for i, x in enumerate((3, 7, 11)):
        t.rect(x, 7, x + 1, 10, C["panel2"]).rect(x, 7, x + 1, 7, C["copper"])
        led(t, x, 3, C["green"] if i != 1 else C["amber"], C["green_dark"] if i != 1 else C["amber_dark"])
        t.set(x + 1, 3, C["edge"])
    t.rect(2, 12, 13, 12, C["copper_dark"])
    return t


def repeater_top() -> Tex:
    t = top()
    t.ring(7.5, 7.5, 3.2, 1.6, C["copper"]).set(7, 7, C["copper_light"]).set(8, 8, C["copper_dark"])
    for x, y in ((7, 2), (8, 2), (7, 13), (8, 13), (2, 7), (2, 8), (13, 7), (13, 8)):
        t.set(x, y, C["copper_dark"])
    return t


def interface_side() -> Tex:
    """Legacy Network Interface: a copper connector plate with pins and a redstone contact."""
    t = Tex().noise([C["copper"], C["copper_dark"], C["copper_light"]], "interface", weights=[6, 2, 1])
    t.frame(0, 0, 15, 15, C["edge"]).bevel(1, 1, 14, 14, C["copper_light"], C["copper_dark"])
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        t.set(x, y, C["bolt"])
    recess(t, 4, 4, 11, 11)
    for x, y in ((6, 6), (9, 6), (6, 9)):
        t.rect(x - 1, y - 1, x, y, C["panel2"]).set(x, y, C["copper_light"])
    t.rect(8, 8, 9, 9, C["redstone"]).set(8, 8, C["redstone_light"])
    return t


def sensor_side() -> Tex:
    """Legacy Sensor: a small round player eye."""
    t = casing("sensor")
    recess(t, 5, 5, 10, 10)
    t.disc(7.5, 7.5, 2.0, C["green_dark"]).disc(7.5, 7.5, 1.0, C["green"]).set(7, 7, "#d8ffe0")
    return t


# --------------------------------------------------------------- sensing ---------------------------------------

def detector_front() -> Tex:
    """Entity Detector: a large lens with an iris and a red sensing pupil, readable like an Observer face."""
    t = casing("detector")
    t.disc(7.5, 7.5, 6.3, C["panel_edge"]).disc(7.5, 7.5, 5.6, C["panel2"])
    t.disc(7.5, 7.5, 4.7, C["glass_dark"]).disc(7.5, 7.5, 3.7, C["glass_mid"]).disc(7.5, 7.5, 2.6, C["glass"])
    t.disc(7.5, 7.5, 1.6, C["panel2"]).rect(7, 7, 8, 8, C["red"]).set(7, 7, C["redstone_light"])
    t.pixels(((5, 4), (4, 5), (5, 5)), C["glass_light"])
    return t


def detector_back() -> Tex:
    t = casing("detector-back")
    recess(t, 4, 5, 11, 10)
    led(t, 6, 7, C["green"], C["green_dark"])
    led(t, 9, 7, C["red"], C["red_dark"])
    return t


def weather_front() -> Tex:
    """Weather Station: a round gauge above sun, rain and lightning symbols."""
    t = casing("weather")
    recess(t, 2, 2, 13, 13)
    t.disc(7.5, 5.5, 3.6, C["cream_shade"]).disc(7.5, 5.5, 2.8, C["cream"])
    t.pixels(((5, 3), (10, 3), (4, 6), (11, 6)), C["edge"])
    t.pixels(((8, 5), (9, 4)), C["red"]).rect(7, 5, 7, 6, C["panel2"])
    t.pattern(3, 10, [".a.", "aAa", ".a."], {"a": C["amber_dark"], "A": C["amber"]})
    t.pattern(7, 10, [".b.", "bBb", ".b."], {"b": C["glass_dark"], "B": C["glass"]})
    t.pattern(11, 10, ["..Y", ".Y.", "Y.."], {"Y": "#f7e15a"})
    return t


def weather_top() -> Tex:
    t = top()
    for dx, dy in ((0, -4), (0, 4), (-4, 0), (4, 0)):
        x, y = 7 + (1 if dx > 0 else 0) + dx, 7 + (1 if dy > 0 else 0) + dy
        t.rect(min(7, x), min(7, y), max(8, x), max(8, y), C["copper"])
    for x, y in ((7, 2), (13, 7), (8, 13), (2, 8)):
        t.rect(x - 1 if x > 8 else x, y - 1 if y > 8 else y, x if x > 8 else x + 1, y if y > 8 else y + 1, C["copper_dark"])
    t.rect(7, 7, 8, 8, C["copper_light"])
    return t


def environment_top() -> Tex:
    """Environment Sensor: a glass dome over an amber light cell, facing the sky."""
    t = top()
    t.disc(7.5, 7.5, 5.0, C["panel_edge"]).disc(7.5, 7.5, 4.2, C["glass_dark"]).disc(7.5, 7.5, 3.2, C["glass"])
    t.rect(6, 6, 9, 9, C["amber"]).rect(7, 7, 8, 8, "#ffe08a").pixels(((5, 5), (6, 4)), C["glass_light"])
    return t


def environment_side() -> Tex:
    t = casing("environment")
    recess(t, 3, 4, 12, 11)
    for i, height in enumerate((2, 4, 6)):
        x = 5 + i * 2
        t.rect(x, 10 - height, x, 9, C["green"] if i < 2 else C["amber"])
    t.rect(11, 6, 11, 9, C["panel_edge"])
    return t


# --------------------------------------------------------------- control -------------------------------------

def redstone_side(arrow: bool = False) -> Tex:
    """Redstone Controller: a redstone port on every face; the top shows which way is north."""
    t = casing("redstone-top" if arrow else "redstone")
    recess(t, 2, 2, 13, 13)
    t.rect(7, 2 if not arrow else 9, 8, 13, C["redstone_dark"]).rect(2, 7, 13, 8, C["redstone_dark"])
    t.rect(5, 5, 10, 10, C["panel_edge"]).rect(6, 6, 9, 9, C["redstone"]).rect(7, 7, 8, 8, C["redstone_light"])
    if arrow:
        # The top edge of an up-face texture points north.
        t.pattern(5, 1, ["..AA..", ".AAAA.", "AAAAAA"], {"A": C["bolt"]}).rect(5, 4, 10, 4, C["panel_edge"])
    else:
        led(t, 3, 3, C["green"]).set(12, 3, C["red"])
    return t


def inventory_side() -> Tex:
    """Inventory Module: a copper hatch around four item slots."""
    t = casing("inventory")
    t.rect(2, 2, 13, 13, C["copper_dark"]).bevel(2, 2, 13, 13, C["copper_light"], C["copper_dark"])
    for x, y in ((3, 3), (8, 3), (3, 8), (8, 8)):
        t.rect(x, y, x + 4, y + 4, "#6f6f6f").inset(x, y, x + 4, y + 4, "#c8c8c8", "#2e2e2e")
    t.pattern(4, 4, ["II.", "Ii.", "..."], {"I": "#d8d8d8", "i": "#a0a0a0"})
    t.pattern(9, 9, ["c..", ".C.", "..."], {"c": C["copper_light"], "C": C["copper"]})
    return t


def display_front() -> Tex:
    """Display Module: a dark screen with a status light. World text is drawn over the screen area."""
    t = casing("display")
    t.rect(1, 1, 14, 14, C["panel_edge"]).bevel(1, 1, 14, 14, C["panel_light"], C["panel2"])
    t.rect(2, 2, 13, 12, C["screen"])
    for y in range(3, 12, 2):
        t.rect(2, y, 13, y, C["screen2"])
    t.pixels(((3, 3), (4, 3), (3, 4)), C["screen_dim"])
    led(t, 12, 13, C["green"])
    t.rect(3, 13, 6, 13, C["panel2"])
    return t


def alarm_side(lit: bool) -> Tex:
    """Alarm Module: a hazard-striped panel around a siren lamp that glows while sounding."""
    t = casing("alarm")
    for y in range(1, 15):
        for x in range(1, 15):
            if x in (1, 2, 13, 14) or y in (1, 2, 13, 14):
                t.set(x, y, C["hazard"] if (x + y) % 4 < 2 else C["hazard_dark"])
    recess(t, 3, 3, 12, 12)
    if lit:
        t.disc(7.5, 7.5, 4.2, "#ff5a2c").disc(7.5, 7.5, 3.0, "#ff8c3a").disc(7.5, 7.5, 1.7, "#ffd27a")
        t.pixels(((5, 5), (6, 4)), "#fff2d0")
    else:
        t.disc(7.5, 7.5, 4.2, "#5e1712").disc(7.5, 7.5, 3.0, C["red_dark"]).disc(7.5, 7.5, 1.7, "#a0352b")
        t.pixels(((5, 5), (6, 4)), "#c86a5f")
    return t


def alarm_top(lit: bool) -> Tex:
    t = top()
    t.disc(7.5, 7.5, 4.0, C["panel_edge"])
    if lit:
        t.disc(7.5, 7.5, 3.2, "#ff6a2c").disc(7.5, 7.5, 1.8, "#ffd27a")
    else:
        t.disc(7.5, 7.5, 3.2, C["red_dark"]).disc(7.5, 7.5, 1.8, "#a0352b")
    return t


def storage_front() -> Tex:
    """Storage Server: three drive bays with handles and activity lights."""
    t = casing("storage")
    t.rect(1, 1, 14, 14, C["panel_edge"])
    for i, y in enumerate((2, 6, 10)):
        t.rect(2, y, 13, y + 2, C["panel"]).bevel(2, y, 13, y + 2, C["panel_light"], C["panel2"])
        t.rect(4, y + 1, 8, y + 1, C["key_shade"])
        t.set(11, y + 1, C["green"]).set(12, y + 1, C["amber"] if i == 1 else C["green_dark"])
    t.rect(2, 13, 13, 13, C["panel2"])
    return t


def storage_side() -> Tex:
    t = casing("storage-side")
    for x in range(4, 12, 2):
        t.rect(x, 3, x, 12, C["panel2"])
    return t


def access_front(open_: bool) -> Tex:
    """Access Controller: a keypad, a card slot and a light that turns green while the door is open."""
    t = casing("access")
    recess(t, 2, 2, 13, 13)
    t.rect(4, 3, 11, 3, C["green"] if open_ else C["red"]).rect(4, 4, 11, 4, C["green_dark"] if open_ else C["red_dark"])
    for row in range(3):
        for col in range(3):
            x, y = 3 + col * 3, 6 + row * 2
            t.rect(x, y, x + 1, y, C["key"]).set(x + 1, y, C["key_shade"])
    t.rect(12, 6, 12, 11, C["panel2"]).set(12, 6, C["copper"])
    return t


def panel_front() -> Tex:
    """Control Panel: four big buttons with label strips."""
    t = casing("panel")
    recess(t, 1, 1, 14, 14)
    buttons = ((2, 2, "#4cc35f", "#2a7136", "#9af0a7"), (9, 2, "#e04a3c", "#7e241d", "#ff9a8c"),
               (2, 9, "#efb238", "#93661b", "#ffe08c"), (9, 9, "#4d8fd6", "#264e7e", "#a9d0fa"))
    for x, y, color, dark, light in buttons:
        t.rect(x, y, x + 4, y + 3, color).bevel(x, y, x + 4, y + 3, light, dark)
        t.rect(x, y + 4, x + 4, y + 4, C["key_shade"])
    return t


def bridge_front() -> Tex:
    """Wireless Network Bridge: amethyst signal arcs rising from an emitter."""
    t = casing("bridge")
    recess(t, 2, 2, 13, 13)
    cx, cy = 7.5, 11.5
    for radius, color in ((7.6, C["amethyst_dark"]), (5.4, C["amethyst"]), (3.2, C["amethyst_light"])):
        for y in range(3, 12):
            for x in range(3, 13):
                if abs(math.hypot(x - cx, y - cy) - radius) < 0.55 and y < 11:
                    t.set(x, y, color)
    t.rect(7, 11, 8, 12, C["amethyst_light"]).set(12, 12, C["green"])
    return t


def bridge_top() -> Tex:
    t = top()
    t.disc(7.5, 7.5, 3.6, C["copper_dark"]).disc(7.5, 7.5, 2.6, C["copper"])
    t.pattern(6, 5, [".AA.", "AaaA", "AaaA", ".AA."], {"A": C["amethyst"], "a": C["amethyst_light"]})
    return t


# --------------------------------------------------------------- computer ------------------------------------

def computer_casing() -> Tex:
    t = Tex().noise([C["base"], C["base2"], C["base3"]], "computer", weights=[6, 2, 2])
    for y in (3, 7, 11):
        t.rect(0, y, 15, y, C["shade"])
    return t


def computer_vents() -> Tex:
    t = computer_casing()
    for x in range(2, 14, 2):
        t.rect(x, 1, x, 2, C["panel2"])
    return t


def computer_monitor(state: str) -> Tex:
    """The monitor front, 12×11 pixels at the top left. The status light sits under the screen."""
    t = computer_casing()
    t.rect(0, 0, 11, 10, C["base"]).bevel(0, 0, 11, 10, C["light"], C["shade"])
    t.rect(1, 1, 10, 8, C["panel_edge"])
    screen = {"off": "#1c2522", "running": C["screen"], "error": "#2a1210"}[state]
    t.rect(2, 2, 9, 7, screen)
    if state == "off":
        t.pixels(((2, 2), (3, 2), (2, 3)), "#3a4844")
    elif state == "running":
        t.rect(3, 3, 7, 3, C["screen_text"]).rect(3, 5, 5, 5, C["screen_dim"]).set(3, 7, C["screen_text"])
        t.rect(7, 5, 8, 5, C["screen_dim"])
    else:
        t.rect(3, 3, 6, 3, "#ff7a68").rect(3, 5, 4, 5, "#a8453a").rect(8, 3, 8, 5, "#ff7a68").set(8, 7, "#ff7a68")
    light = {"off": C["panel2"], "running": C["green"], "error": C["red"]}[state]
    t.rect(2, 9, 4, 9, C["shade"]).set(9, 9, light)
    return t


def computer_case_front(drive: bool) -> Tex:
    """The desktop case front, 14×4 pixels at the top left: a drive slot and vents."""
    t = computer_casing()
    t.rect(0, 0, 13, 3, C["base"]).bevel(0, 0, 13, 3, C["light"], C["shade"])
    t.rect(2, 1, 7, 2, C["panel2"])
    if drive:
        t.rect(3, 1, 6, 1, "#3d4148").rect(3, 2, 6, 2, C["copper"]).set(6, 1, "#e9e2cf")
    for x in range(9, 13):
        t.set(x, 1, C["shade"]).set(x, 2, C["shade"])
    return t


def computer_keyboard() -> Tex:
    """The keyboard top, 12×4 pixels at the top left."""
    t = computer_casing()
    t.rect(0, 0, 11, 3, C["shade"])
    for y in range(0, 3):
        for x in range(1 if y != 1 else 0, 11, 2):
            t.set(x, y, C["key"])
    t.rect(3, 3, 8, 3, C["key"])
    return t


# --------------------------------------------------------------- items ---------------------------------------

def data_drive() -> Tex:
    """Programmable Data Drive: a chunky cartridge with a label and copper contacts."""
    t = Tex()
    body, body_dark, body_light = "#3d4148", "#26292e", "#5b616b"
    t.rect(2, 1, 13, 14, body).frame(2, 1, 13, 14, "#16181b").bevel(3, 2, 12, 13, body_light, body_dark)
    t.rect(4, 2, 11, 7, "#e9e2cf").frame(4, 2, 11, 7, "#b8ae92")
    t.rect(5, 4, 9, 4, C["copper_dark"]).rect(5, 6, 8, 6, "#9c9480")
    t.rect(4, 9, 11, 12, "#9aa1ab").frame(4, 9, 11, 12, "#5b616b").rect(7, 10, 8, 11, body_dark)
    for x in (4, 6, 8, 10):
        t.set(x, 14, C["copper_light"]).set(x + 1, 14, C["copper"])
    t.set(13, 1, (0, 0, 0, 0)).set(12, 1, "#16181b").set(13, 2, "#16181b")
    return t


def linking_tool() -> Tex:
    """Linking Tool: a probe with a wooden grip, an iron shaft, a copper plug and a coiled cable."""
    t = Tex()
    grip = ((2, 13), (3, 12), (4, 11), (3, 13), (4, 12), (5, 11))
    t.pixels(grip, "#8a5a32").pixels(((2, 14), (3, 14), (1, 13), (1, 12), (2, 12)), "#5e3c20")
    t.pixels(((5, 10), (6, 9), (6, 10)), "#4a463f")
    t.pixels(((7, 8), (8, 7), (9, 6)), "#c6c1b4").pixels(((7, 9), (8, 8), (9, 7)), "#837d70")
    t.pattern(10, 2, ["..oo", ".oOo", "oOO.", "oo.."], {"o": C["copper_dark"], "O": C["copper_light"]})
    t.pixels(((13, 1), (14, 2)), C["copper_light"]).pixels(((12, 1), (14, 3)), C["copper"])
    t.pixels(((3, 10), (2, 9), (2, 8), (3, 7), (4, 7), (5, 8)), C["redstone_dark"]).set(4, 8, C["redstone"])
    return t


# ---------------------------------------------------------------------------------------------------------------
# Models, blockstates and item definitions
# ---------------------------------------------------------------------------------------------------------------

def tex(name: str) -> str:
    return f"{NS}:block/{name}"


def cube(down: str, up: str, north: str, south: str, west: str, east: str) -> dict:
    return {"parent": "minecraft:block/cube", "textures": {
        "particle": tex(west), "down": tex(down), "up": tex(up), "north": tex(north), "south": tex(south),
        "west": tex(west), "east": tex(east)}}


def uniform(side: str, top_: str = "casing_top", bottom_: str = "casing_bottom") -> dict:
    return cube(bottom_, top_, side, side, side, side)


def front(front_: str, top_: str = "casing_top", side_: str = "casing_side", back: str | None = None) -> dict:
    return cube("casing_bottom", top_, front_, back or side_, side_, side_)


FACINGS = {"north": 0, "east": 90, "south": 180, "west": 270}


def facing_variants(model_for) -> dict:
    """Blockstate variants for a horizontal FACING property, rotating a north-facing model."""
    variants = {}
    for facing, rotation in FACINGS.items():
        for key, model in model_for().items():
            entry = {"model": f"{NS}:block/{model}"}
            if rotation:
                entry["y"] = rotation
            variants[f"facing={facing}" + (f",{key}" if key else "")] = entry
    return {"variants": variants}


def box(x0, y0, z0, x1, y1, z1, faces: dict) -> dict:
    return {"from": [x0, y0, z0], "to": [x1, y1, z1], "faces": faces}


def face(texture: str, u0: float, v0: float, u1: float, v1: float, cull: str | None = None) -> dict:
    result = {"uv": [u0, v0, u1, v1], "texture": f"#{texture}"}
    if cull:
        result["cullface"] = cull
    return result


def computer_model(state: str, drive: bool) -> dict:
    """A retro desktop computer: a keyboard in front, a case with a drive slot and a CRT monitor on top.

    The front faces north before rotation. One model unit maps to one texel on every face.
    """
    c = "casing"
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": tex("computer_casing"), "casing": tex("computer_casing"), "vents": tex("computer_vents"),
            "monitor": tex(f"computer_monitor_{state}"), "case": tex("computer_case_front" + ("_drive" if drive else "")),
            "keyboard": tex("computer_keyboard"),
        },
        "elements": [
            # Keyboard: 12 wide, 1 tall, 4 deep.
            box(2, 0, 1, 14, 1, 5, {
                "up": face("keyboard", 0, 0, 12, 4), "north": face(c, 2, 14, 14, 15), "south": face(c, 2, 14, 14, 15),
                "west": face(c, 0, 14, 4, 15), "east": face(c, 0, 14, 4, 15), "down": face(c, 2, 0, 14, 4, "down")}),
            # Desktop case: 14 wide, 4 tall, 11 deep.
            box(1, 0, 5, 15, 4, 16, {
                "north": face("case", 0, 0, 14, 4), "south": face("vents", 1, 0, 15, 4, "south"),
                "west": face("vents", 2, 0, 13, 4), "east": face("vents", 2, 0, 13, 4),
                "up": face(c, 1, 4, 15, 15), "down": face(c, 1, 4, 15, 15, "down")}),
            # Monitor: 12 wide, 11 tall, 8 deep.
            box(2, 4, 6, 14, 15, 14, {
                "north": face("monitor", 0, 0, 12, 11), "south": face(c, 2, 1, 14, 12),
                "west": face("vents", 4, 0, 12, 11), "east": face("vents", 4, 0, 12, 11),
                "up": face(c, 2, 2, 14, 10)}),
            # The CRT's tapered back.
            box(4, 6, 14, 12, 13, 16, {
                "south": face("vents", 4, 4, 12, 11, "south"), "west": face(c, 0, 6, 2, 13),
                "east": face(c, 0, 6, 2, 13), "up": face(c, 4, 0, 12, 2), "down": face(c, 4, 0, 12, 2)}),
        ],
    }


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def item(name: str, model: str) -> None:
    write_json(ASSETS / "items" / f"{name}.json", {"model": {"type": "minecraft:model", "model": model}})


def main() -> None:
    textures = {
        "casing_side": side(), "casing_top": top(), "casing_bottom": bottom(),
        "network_repeater_side": repeater_side(), "network_repeater_top": repeater_top(),
        "network_interface_side": interface_side(), "sensor_side": sensor_side(),
        "entity_detector_front": detector_front(), "entity_detector_back": detector_back(),
        "weather_station_front": weather_front(), "weather_station_top": weather_top(),
        "environment_sensor_top": environment_top(), "environment_sensor_side": environment_side(),
        "redstone_controller_side": redstone_side(), "redstone_controller_top": redstone_side(arrow=True),
        "inventory_module_side": inventory_side(), "display_module_front": display_front(),
        "alarm_module_side": alarm_side(False), "alarm_module_side_on": alarm_side(True),
        "alarm_module_top": alarm_top(False), "alarm_module_top_on": alarm_top(True),
        "storage_server_front": storage_front(), "storage_server_side": storage_side(),
        "access_controller_front": access_front(False), "access_controller_front_open": access_front(True),
        "control_panel_front": panel_front(),
        "wireless_bridge_front": bridge_front(), "wireless_bridge_top": bridge_top(),
        "computer_casing": computer_casing(), "computer_vents": computer_vents(),
        "computer_monitor_off": computer_monitor("off"), "computer_monitor_running": computer_monitor("running"),
        "computer_monitor_error": computer_monitor("error"),
        "computer_case_front": computer_case_front(False), "computer_case_front_drive": computer_case_front(True),
        "computer_keyboard": computer_keyboard(),
    }
    for name, texture in textures.items():
        texture.save(ASSETS / "textures" / "block" / f"{name}.png")
    data_drive().save(ASSETS / "textures" / "item" / "data_drive.png")
    linking_tool().save(ASSETS / "textures" / "item" / "linking_tool.png")

    models = ASSETS / "models" / "block"
    states = ASSETS / "blockstates"

    # Blocks without orientation: their faces are named by world direction, so every side looks alike.
    plain = {
        "network_repeater": uniform("network_repeater_side", "network_repeater_top"),
        "network_interface": uniform("network_interface_side", "network_interface_side", "network_interface_side"),
        "sensor": uniform("sensor_side"),
        "redstone_controller": uniform("redstone_controller_side", "redstone_controller_top"),
        "inventory_module": uniform("inventory_module_side", "inventory_module_side"),
        "environment_sensor": uniform("environment_sensor_side", "environment_sensor_top"),
    }
    for name, model in plain.items():
        write_json(models / f"{name}.json", model)
        write_json(states / f"{name}.json", {"variants": {"": {"model": f"{NS}:block/{name}"}}})
        item(name, f"{NS}:block/{name}")

    write_json(models / "alarm_module.json", uniform("alarm_module_side", "alarm_module_top"))
    write_json(models / "alarm_module_on.json", uniform("alarm_module_side_on", "alarm_module_top_on"))
    write_json(states / "alarm_module.json", {"variants": {
        "lit=false": {"model": f"{NS}:block/alarm_module"}, "lit=true": {"model": f"{NS}:block/alarm_module_on"}}})
    item("alarm_module", f"{NS}:block/alarm_module")

    # Blocks with a front that faces the player who placed them.
    oriented = {
        "entity_detector": front("entity_detector_front", back="entity_detector_back"),
        "weather_station": front("weather_station_front", "weather_station_top"),
        "display_module": front("display_module_front"),
        "storage_server": front("storage_server_front", side_="storage_server_side"),
        "control_panel": front("control_panel_front"),
        "wireless_bridge": front("wireless_bridge_front", "wireless_bridge_top"),
    }
    for name, model in oriented.items():
        write_json(models / f"{name}.json", model)
        write_json(states / f"{name}.json", facing_variants(lambda name=name: {"": name}))
        item(name, f"{NS}:block/{name}")

    write_json(models / "access_controller.json", front("access_controller_front"))
    write_json(models / "access_controller_open.json", front("access_controller_front_open"))
    write_json(states / "access_controller.json", facing_variants(
        lambda: {"open=false": "access_controller", "open=true": "access_controller_open"}))
    item("access_controller", f"{NS}:block/access_controller")

    computer_states = {}
    for state in ("off", "running", "error"):
        for drive in (False, True):
            name = f"programmable_computer_{state}" + ("_drive" if drive else "")
            write_json(models / f"{name}.json", computer_model(state, drive))
            computer_states[f"drive={str(drive).lower()},status={state}"] = name
    write_json(states / "programmable_computer.json", facing_variants(lambda: computer_states))
    item("programmable_computer", f"{NS}:block/programmable_computer_running")

    for name in ("data_drive", "linking_tool"):
        write_json(ASSETS / "models" / "item" / f"{name}.json",
                   {"parent": "minecraft:item/handheld" if name == "linking_tool" else "minecraft:item/generated",
                    "textures": {"layer0": f"{NS}:item/{name}"}})
        item(name, f"{NS}:item/{name}")


if __name__ == "__main__":
    main()

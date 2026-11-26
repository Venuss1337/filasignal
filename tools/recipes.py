"""Generates FilaSignal's crafting recipes, their recipe-book unlock advancements and block mining tags.

The RECIPES table below is the single place to change crafting. Run from the repository root:

    python tools/recipes.py

It writes into filasignal-fabric/src/main/resources/data. The documentation site reads the generated recipe JSON,
so the published recipes always match the mod.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "filasignal-fabric" / "src" / "main" / "resources" / "data"

I, C, R, P, G = "minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:redstone", "minecraft:glass_pane", "minecraft:glass"

# name: (tier, pattern, key, count, unlocking item)
# Early tier needs only Overworld materials; advanced hardware asks for Nether quartz or ender pearls.
RECIPES = {
    "programmable_computer": ("early", ["IPI", "IRI", "ICI"], {"I": I, "P": P, "R": R, "C": C}, 1, R),
    "network_repeater": ("early", ["ICI", "CRC", "ICI"], {"I": I, "C": C, "R": R}, 2, C),
    "linking_tool": ("early", ["  C", " R ", "S  "], {"C": C, "R": R, "S": "minecraft:stick"}, 1, C),
    "entity_detector": ("early", ["IPI", "TRT", "ICI"], {"I": I, "P": P, "T": "minecraft:tripwire_hook", "R": R, "C": C}, 1, R),
    "alarm_module": ("early", ["INI", "IRI", "ICI"], {"I": I, "N": "minecraft:note_block", "R": R, "C": C}, 1, R),
    "redstone_controller": ("early", ["IRI", "XCX", "IRI"], {"I": I, "R": R, "X": "minecraft:repeater", "C": C}, 1, R),
    "environment_sensor": ("early", ["IGI", "CRC", "ICI"], {"I": I, "G": G, "C": C, "R": R}, 1, R),
    "weather_station": ("early", ["ILI", "PKP", "ICI"], {"I": I, "L": "minecraft:lightning_rod", "P": P, "K": "minecraft:clock", "C": C}, 1, C),
    "control_panel": ("early", ["BIB", "BRB", "ICI"], {"B": "#minecraft:buttons", "I": I, "R": R, "C": C}, 1, R),
    "display_module": ("intermediate", ["III", "PPP", "CRC"], {"I": I, "P": P, "C": C, "R": R}, 1, R),
    "inventory_module": ("intermediate", ["IHI", "CRC", "III"], {"I": I, "H": "minecraft:hopper", "C": C, "R": R}, 1, "minecraft:hopper"),
    "data_drive": ("intermediate", ["CIC", "CRC", "CNC"], {"C": C, "I": I, "R": R, "N": "minecraft:gold_nugget"}, 1, R),
    "access_controller": ("intermediate", ["IPI", "BRB", "IGI"], {"I": I, "P": P, "B": "#minecraft:buttons", "R": R, "G": "minecraft:gold_ingot"}, 1, R),
    "storage_server": ("advanced", ["IKI", "XRX", "IKI"], {"I": I, "K": "minecraft:chest", "X": "minecraft:comparator", "R": R}, 1, "minecraft:comparator"),
    "wireless_bridge": ("advanced", ["IAI", "IEI", "ICI"], {"I": I, "A": "minecraft:amethyst_shard", "E": "minecraft:ender_pearl", "C": C}, 1, "minecraft:ender_pearl"),
    # Legacy devices stay craftable for existing builds.
    "network_interface": ("legacy", None, [C, R, I], 1, C),
    "sensor": ("legacy", None, ["minecraft:observer", C], 1, "minecraft:observer"),
}


def write(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    for name, (tier, pattern, key, count, unlock) in RECIPES.items():
        result = {"id": f"filasignal:{name}"}
        if count != 1:
            result["count"] = count
        if pattern is None:
            recipe = {"type": "minecraft:crafting_shapeless", "category": "redstone", "ingredients": key, "result": result}
        else:
            used = set("".join(pattern)) - {" "}
            assert used == set(key), f"{name}: pattern and key differ"
            recipe = {"type": "minecraft:crafting_shaped", "category": "redstone", "key": dict(sorted(key.items())),
                      "pattern": pattern, "result": result}
        write(DATA / "filasignal" / "recipe" / f"{name}.json", recipe)
        criterion = "has_" + unlock.split(":")[1]
        write(DATA / "filasignal" / "advancement" / "recipes" / "redstone" / f"{name}.json", {
            "parent": "minecraft:recipes/root",
            "criteria": {
                criterion: {"conditions": {"items": [{"items": unlock}]}, "trigger": "minecraft:inventory_changed"},
                "has_the_recipe": {"conditions": {"recipes": f"filasignal:{name}"}, "trigger": "minecraft:recipe_unlocked"},
            },
            "requirements": [["has_the_recipe", criterion]],
            "rewards": {"recipes": [f"filasignal:{name}"]},
        })
    blocks = sorted(name for name in RECIPES if name not in ("linking_tool", "data_drive"))
    write(DATA / "minecraft" / "tags" / "block" / "mineable" / "pickaxe.json",
          {"values": [f"filasignal:{name}" for name in blocks]})
    write(ROOT / "web" / "src" / "data" / "recipe-tiers.json", {name: spec[0] for name, spec in RECIPES.items()})


if __name__ == "__main__":
    main()

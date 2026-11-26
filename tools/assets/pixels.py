"""Small pixel-art drawing helpers for the FilaSignal texture generator.

Textures are drawn on a grid of RGBA tuples and saved as PNG with Pillow. Colors are hex strings such as
"#a8a397". Coordinates are pixel indices; rectangle bounds are inclusive.
"""
from __future__ import annotations

import math
import random
from pathlib import Path

from PIL import Image


def rgba(color: str | tuple, alpha: int = 255) -> tuple[int, int, int, int]:
    if isinstance(color, tuple):
        return color if len(color) == 4 else (*color, alpha)
    value = color.lstrip("#")
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), alpha


def mix(a: str, b: str, amount: float) -> tuple[int, int, int, int]:
    ca, cb = rgba(a), rgba(b)
    return tuple(round(ca[i] + (cb[i] - ca[i]) * amount) for i in range(4))


class Tex:
    """A mutable texture. Drawing outside the bounds is ignored."""

    def __init__(self, width: int = 16, height: int = 16, fill: str | tuple | None = None):
        self.width = width
        self.height = height
        empty = (0, 0, 0, 0) if fill is None else rgba(fill)
        self.px = [[empty for _ in range(width)] for _ in range(height)]

    def copy(self) -> "Tex":
        other = Tex(self.width, self.height)
        other.px = [row[:] for row in self.px]
        return other

    def get(self, x: int, y: int):
        return self.px[y][x]

    def set(self, x: int, y: int, color) -> "Tex":
        if 0 <= x < self.width and 0 <= y < self.height:
            self.px[y][x] = rgba(color)
        return self

    def rect(self, x0: int, y0: int, x1: int, y1: int, color) -> "Tex":
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, color)
        return self

    def frame(self, x0: int, y0: int, x1: int, y1: int, color) -> "Tex":
        self.rect(x0, y0, x1, y0, color).rect(x0, y1, x1, y1, color)
        return self.rect(x0, y0, x0, y1, color).rect(x1, y0, x1, y1, color)

    def bevel(self, x0: int, y0: int, x1: int, y1: int, light, dark) -> "Tex":
        """Raised edge: light on the top and left, dark on the bottom and right."""
        self.rect(x0, y0, x1, y0, light).rect(x0, y0, x0, y1, light)
        return self.rect(x0, y1, x1, y1, dark).rect(x1, y0, x1, y1, dark)

    def inset(self, x0: int, y0: int, x1: int, y1: int, light, dark) -> "Tex":
        """Recessed edge: dark on the top and left, light on the bottom and right."""
        return self.bevel(x0, y0, x1, y1, dark, light)

    def disc(self, cx: float, cy: float, radius: float, color) -> "Tex":
        for y in range(self.height):
            for x in range(self.width):
                if math.hypot(x - cx, y - cy) <= radius:
                    self.set(x, y, color)
        return self

    def ring(self, cx: float, cy: float, outer: float, inner: float, color) -> "Tex":
        for y in range(self.height):
            for x in range(self.width):
                if inner < math.hypot(x - cx, y - cy) <= outer:
                    self.set(x, y, color)
        return self

    def pixels(self, points, color) -> "Tex":
        for x, y in points:
            self.set(x, y, color)
        return self

    def pattern(self, x0: int, y0: int, rows: list[str], palette: dict) -> "Tex":
        """Draws a small sprite: each character maps to a palette color; '.' is skipped."""
        for dy, row in enumerate(rows):
            for dx, char in enumerate(row):
                if char != ".":
                    self.set(x0 + dx, y0 + dy, palette[char])
        return self

    def noise(self, colors: list[str], seed: str, x0=0, y0=0, x1=None, y1=None, weights=None) -> "Tex":
        """Deterministic per-pixel variation, used for subtle metal grain."""
        rng = random.Random(seed)
        x1 = self.width - 1 if x1 is None else x1
        y1 = self.height - 1 if y1 is None else y1
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, rng.choices(colors, weights=weights)[0])
        return self

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        image = Image.new("RGBA", (self.width, self.height))
        image.putdata([pixel for row in self.px for pixel in row])
        image.save(path, optimize=True)

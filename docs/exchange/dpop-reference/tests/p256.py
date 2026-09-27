# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Lucas Greuloch
"""A slow, pure-Python ECDSA P-256 verifier, independent of every key backend, for tests only."""

from __future__ import annotations

import hashlib

P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
A = P - 3
B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
G = (
    0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
    0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5,
)

Point = tuple[int, int] | None


def on_curve(point: tuple[int, int]) -> bool:
    """Tells whether an affine point lies on P-256."""
    x, y = point
    return 0 <= x < P and 0 <= y < P and (y * y - (x * x * x + A * x + B)) % P == 0


def _add(p1: Point, p2: Point) -> Point:
    """Adds two affine points."""
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    x1, y1 = p1
    x2, y2 = p2
    if x1 == x2 and (y1 + y2) % P == 0:
        return None
    if p1 == p2:
        slope = (3 * x1 * x1 + A) * pow(2 * y1, -1, P) % P
    else:
        slope = (y2 - y1) * pow(x2 - x1, -1, P) % P
    x3 = (slope * slope - x1 - x2) % P
    return x3, (slope * (x1 - x3) - y1) % P


def _multiply(k: int, point: Point) -> Point:
    """Multiplies an affine point by a scalar."""
    result: Point = None
    addend = point
    while k:
        if k & 1:
            result = _add(result, addend)
        addend = _add(addend, addend)
        k >>= 1
    return result


def verify_digest(public: tuple[int, int], digest: bytes, r: int, s: int) -> bool:
    """Verifies an ECDSA P-256 signature ``(r, s)`` over a 32-byte digest."""
    if not on_curve(public) or not (1 <= r < N and 1 <= s < N):
        return False
    e = int.from_bytes(digest, "big")
    w = pow(s, -1, N)
    point = _add(_multiply(e * w % N, G), _multiply(r * w % N, public))
    return point is not None and point[0] % N == r


def verify_es256(public: tuple[int, int], data: bytes, signature: bytes) -> bool:
    """Verifies a JWS ES256 ``r || s`` signature over ``data``."""
    if len(signature) != 64:
        return False
    r = int.from_bytes(signature[:32], "big")
    s = int.from_bytes(signature[32:], "big")
    return verify_digest(public, hashlib.sha256(data).digest(), r, s)

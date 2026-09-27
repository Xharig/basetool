# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Lucas Greuloch
"""Tests that the independent P-256 verifier itself is right, against RFC 6979's vector."""

from __future__ import annotations

import hashlib
import unittest

from tests import p256

PUBLIC = (
    0x60FED4BA255A9D31C961EB74C6356D68C049B8923B61FA6CE669622E60F29FB6,
    0x7903FE1008B8BC99A41AE9E95628BC64F2F1B20C2D7E9F5177A3C294D4462299,
)
R = 0xEFD48B2AACB6A8FD1140DD9CD45E81D69D2C877B56AAF991C34D0EA84EAF3716
S = 0xF7CB1C942D657C41D436C7A1B6E29F65F3E900DBB9AFF4064DC4AB2F843ACDA8


class VerifierTest(unittest.TestCase):
    """RFC 6979 §A.2.5, P-256 with SHA-256 over the message ``sample``."""

    def test_accepts_the_published_signature(self) -> None:
        """The published signature verifies."""
        digest = hashlib.sha256(b"sample").digest()
        self.assertTrue(p256.verify_digest(PUBLIC, digest, R, S))

    def test_rejects_changes(self) -> None:
        """Another message, a changed ``r`` or ``s``, or an off-curve key fail."""
        digest = hashlib.sha256(b"sample").digest()
        self.assertFalse(p256.verify_digest(PUBLIC, hashlib.sha256(b"test").digest(), R, S))
        self.assertFalse(p256.verify_digest(PUBLIC, digest, R ^ 1, S))
        self.assertFalse(p256.verify_digest(PUBLIC, digest, R, S ^ 1))
        self.assertFalse(p256.verify_digest((PUBLIC[0], PUBLIC[1] ^ 1), digest, R, S))
        self.assertFalse(p256.verify_digest(PUBLIC, digest, 0, S))

    def test_generator_is_on_the_curve(self) -> None:
        """The curve constants are consistent."""
        self.assertTrue(p256.on_curve(p256.G))
        self.assertTrue(p256.on_curve(PUBLIC))


if __name__ == "__main__":
    unittest.main()

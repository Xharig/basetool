# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Lucas Greuloch
"""Tests of the platform's choice of backend; each case runs only on its own platform."""

from __future__ import annotations

import sys
import tempfile
import unittest
import uuid
from pathlib import Path

from dpop_reference.keystore import delete_installation_key, open_installation_key


@unittest.skipUnless(sys.platform == "win32", "the CNG key store is Windows only")
class WindowsKeystoreTest(unittest.TestCase):
    """On Windows the installation key is a persisted CNG key."""

    def test_open_reopen_delete(self) -> None:
        """The same name opens the same key until it is deleted."""
        from dpop_reference.cng import CngKey

        name = f"dpop-reference-test-{uuid.uuid4()}"
        self.addCleanup(delete_installation_key, name)
        with open_installation_key(name) as first:
            thumbprint = first.thumbprint()
        with open_installation_key(name) as second:
            self.assertEqual(thumbprint, second.thumbprint())
        delete_installation_key(name)
        self.assertIsNone(CngKey.open(name))


@unittest.skipIf(sys.platform == "win32", "the file key store is used outside Windows")
class FileKeystoreTest(unittest.TestCase):
    """Elsewhere the installation key is an OpenSSL key in a file."""

    def test_key_file_is_required(self) -> None:
        """Without a key file there is nowhere to keep the key."""
        with self.assertRaises(ValueError):
            open_installation_key("unused")

    def test_open_reopen_delete(self) -> None:
        """The same file opens the same key until it is deleted."""
        from dpop_reference.openssl import OpenSslKey

        if not OpenSslKey.is_available():
            self.skipTest("no OpenSSL 3 libcrypto on this machine")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "dpop.pem"
            with open_installation_key("unused", key_file=path) as first:
                thumbprint = first.thumbprint()
            with open_installation_key("unused", key_file=path) as second:
                self.assertEqual(thumbprint, second.thumbprint())
            delete_installation_key("unused", key_file=path)
            self.assertFalse(path.exists())


if __name__ == "__main__":
    unittest.main()

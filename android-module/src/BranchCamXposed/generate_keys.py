#!/usr/bin/env python3
"""
BranchCam — One-Time AES Key Generator
======================================
Run this ONCE to generate a fresh AES-256 key + IV pair.
Paste the printed Kotlin arrays directly into LicenseManager.kt.
Then update AES_KEY and AES_IV in license_gen.py to match.

NEVER run this again after distributing APKs — changing the key
invalidates all previously issued license keys.

Dependencies: none (uses Python stdlib only)
"""

import os
import sys

XK = 0x5A  # XOR mask (matches LicenseManager.kt)


def fmt_kotlin_array(raw: bytes, name: str) -> str:
    lines = [f"    private val {name} = byteArrayOf("]
    row = []
    for i, b in enumerate(raw):
        obf = b ^ XK
        row.append(f"0x{obf:02X}.toByte()")
        if len(row) == 8 or i == len(raw) - 1:
            sep = "," if i < len(raw) - 1 else ""
            lines.append("        " + ", ".join(row) + sep)
            row = []
    lines.append("    )")
    return "\n".join(lines)


def fmt_python_bytes(raw: bytes, name: str) -> str:
    rows = []
    for i in range(0, len(raw), 4):
        chunk = raw[i:i+4]
        rows.append("    " + ", ".join(f"0x{b:02X}" for b in chunk) + ",")
    return f"{name} = bytes([\n" + "\n".join(rows) + "\n])"


if __name__ == "__main__":
    key = os.urandom(32)   # AES-256
    iv  = os.urandom(16)   # AES-CBC IV

    print("=" * 64)
    print("  BranchCam — Generated AES Key Pair")
    print("  Save these somewhere safe. You need them to generate")
    print("  license keys for buyers in license_gen.py.")
    print("=" * 64)

    print("\n── Paste into LicenseManager.kt (hook module) ───────────")
    print(fmt_kotlin_array(key, "KEY_OBF"))
    print(fmt_kotlin_array(iv,  "IV_OBF"))

    print("\n── Paste into license_gen.py (AES_KEY / AES_IV) ─────────")
    print(fmt_python_bytes(key, "AES_KEY"))
    print()
    print(fmt_python_bytes(iv, "AES_IV"))

    print("\n" + "=" * 64)
    print("  Done. Replace the placeholder arrays in both files,")
    print("  rebuild the APK, and start selling keys.")
    print("=" * 64 + "\n")

#!/usr/bin/env python3
"""
BranchCam License Key Generator
==============================
Run on YOUR machine (or VPS) — never share this script with buyers.

Usage:
    python3 license_gen.py <device_id> <days> [tier]

    device_id  — the ANDROID_ID shown in the app's License screen
    days       — how many days the key is valid (e.g. 30, 90, 365)
    tier       — BASIC or PRO (default: BASIC)

Examples:
    python3 license_gen.py a3f9b2c1d4e5f6a7 30
    python3 license_gen.py a3f9b2c1d4e5f6a7 365 PRO

Output: a base64 license key the buyer enters in the app.

Dependencies: pip install pycryptodome
"""

import sys
import hashlib
import time
import base64
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad

# ══ CHANGE THESE TO MATCH LicenseManager.kt ══════════════════════════════════
# XOR each byte with 0x5A to get the obfuscated arrays for the Kotlin file.
# Or run generate_keys.py to get a fresh random pair + pre-XOR'd arrays.

AES_KEY = bytes([
    0x44, 0x55, 0x12, 0x54,
    0x1B, 0x55, 0x4D, 0x10,
    0x56, 0x4D, 0x57, 0x12,
    0x4B, 0x56, 0x12, 0x4D,
    0x57, 0x12, 0x4B, 0x56,
    0x4D, 0x57, 0x12, 0x4B,
    0x44, 0x55, 0x12, 0x54,
    0x1B, 0x55, 0x4D, 0x10,
])  # 32 bytes — must match KEY_OBF ^ 0x5A in LicenseManager.kt

AES_IV = bytes([
    0x75, 0x50, 0x12, 0x55,
    0x1B, 0x55, 0x4D, 0x10,
    0x56, 0x4D, 0x57, 0x12,
    0x4B, 0x56, 0x12, 0x4D,
])  # 16 bytes — must match IV_OBF ^ 0x5A in LicenseManager.kt

SALT = "FC_SALT_"  # must match hashDeviceId() in LicenseManager.kt
# ══════════════════════════════════════════════════════════════════════════════


def hash_device_id(android_id: str) -> str:
    """SHA-256 of SALT+android_id, first 32 hex chars."""
    salted = SALT + android_id
    full_hash = hashlib.sha256(salted.encode("utf-8")).hexdigest()
    return full_hash[:32]


def generate_license(device_id: str, days: int, tier: str = "BASIC") -> str:
    """
    Returns the base64-encoded AES-256-CBC encrypted license key.
    """
    tier = tier.upper()
    if tier not in ("BASIC", "PRO"):
        raise ValueError(f"Invalid tier '{tier}' — must be BASIC or PRO")

    device_hash = hash_device_id(device_id)
    expiry_epoch = int(time.time()) + days * 86400
    plaintext = f"{device_hash}|{expiry_epoch}|{tier}"

    cipher = AES.new(AES_KEY, AES.MODE_CBC, AES_IV)
    ciphertext = cipher.encrypt(pad(plaintext.encode("utf-8"), AES.block_size))
    return base64.b64encode(ciphertext).decode("utf-8")


def print_xor_arrays():
    """Print the XOR-obfuscated byte arrays to paste into LicenseManager.kt."""
    print("\n── Obfuscated arrays for LicenseManager.kt ──")
    print("val KEY_OBF = byteArrayOf(")
    for i, b in enumerate(AES_KEY):
        end = "," if i < len(AES_KEY) - 1 else ""
        print(f"    0x{b ^ 0x5A:02X}.toByte(){end}", end="")
        if (i + 1) % 4 == 0:
            print()
    print(")")
    print("val IV_OBF = byteArrayOf(")
    for i, b in enumerate(AES_IV):
        end = "," if i < len(AES_IV) - 1 else ""
        print(f"    0x{b ^ 0x5A:02X}.toByte(){end}", end="")
        if (i + 1) % 4 == 0:
            print()
    print(")")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    device_id = sys.argv[1].strip()
    days      = int(sys.argv[2])
    tier      = sys.argv[3].upper() if len(sys.argv) > 3 else "BASIC"

    key = generate_license(device_id, days, tier)

    import datetime
    expiry = datetime.datetime.now() + datetime.timedelta(days=days)

    print(f"\n{'━'*60}")
    print(f"  BranchCam License Key")
    print(f"{'━'*60}")
    print(f"  Device  : {device_id}")
    print(f"  Tier    : {tier}")
    print(f"  Expires : {expiry.strftime('%d %b %Y')} ({days} days)")
    print(f"{'━'*60}")
    print(f"\n{key}\n")
    print("  ↑ Send this key to the buyer.\n")

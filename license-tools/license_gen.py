#!/usr/bin/env python3
"""
license_generator.py — Generate license.key files for RootHideSuite buyers

Usage:
    python3 license_generator.py <device_serial> <days>

Example:
    python3 license_generator.py R58M12345XY 30
    # → outputs license.key (send this file to buyer)

Buyer pushes to their device:
    adb push license.key /data/adb/modules/RootHideSuite/license.key
"""

import sys
import struct
import time
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad

# ── MUST MATCH license.cpp constants ──────────────────────────────────────
AES_KEY = bytes([
    0x3f,0x1a,0xc2,0x77,0x9e,0x04,0xb5,0x61,
    0x2d,0x88,0xf0,0x5c,0xa3,0x17,0x6b,0xe9,
    0x94,0x2c,0x50,0xd7,0x8a,0x3e,0xf1,0x06,
    0xbc,0x49,0x73,0x25,0xce,0x81,0x5a,0x0d
])
AES_IV = bytes([
    0xa4,0x27,0x93,0x0e,0x6c,0xb8,0x41,0x5f,
    0xd2,0x17,0x8b,0x34,0x09,0xe6,0x7a,0xc5
])
# ──────────────────────────────────────────────────────────────────────────

def generate_license(device_serial: str, days: int) -> bytes:
    expiry = int(time.time()) + days * 86400
    plain = f"{device_serial}|{expiry}".encode()

    cipher = AES.new(AES_KEY, AES.MODE_CBC, AES_IV)
    encrypted = cipher.encrypt(pad(plain, AES.block_size))
    return encrypted

def main():
    if len(sys.argv) < 3:
        print("Usage: python3 license_generator.py <device_serial> <days>")
        sys.exit(1)

    device_serial = sys.argv[1].strip()
    days = int(sys.argv[2])

    key_bytes = generate_license(device_serial, days)

    with open("license.key", "wb") as f:
        f.write(key_bytes)

    expiry_ts = int(time.time()) + days * 86400
    expiry_readable = time.strftime('%Y-%m-%d', time.localtime(expiry_ts))

    print(f"✅ license.key generated")
    print(f"   Device  : {device_serial}")
    print(f"   Days    : {days}")
    print(f"   Expires : {expiry_readable}")
    print()
    print("Send license.key to buyer.")
    print("Buyer runs:")
    print("   adb push license.key /data/adb/modules/RootHideSuite/license.key")

if __name__ == "__main__":
    main()

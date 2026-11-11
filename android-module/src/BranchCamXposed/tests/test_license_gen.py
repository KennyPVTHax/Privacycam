import base64
import hashlib
import re
import sys
import unittest
from pathlib import Path

from Crypto.Cipher import AES
from Crypto.Util.Padding import pad, unpad

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT))
import license_gen


def kotlin_material(name: str) -> bytes:
    source = (PROJECT / "hook/src/main/java/com/branchcam/hook/LicenseManager.kt").read_text(
        encoding="utf-8"
    )
    match = re.search(
        rf"private val {name}_OBF = byteArrayOf\((.*?)\n    \)", source, re.S
    )
    if not match:
        raise AssertionError(f"Could not locate {name}_OBF in LicenseManager.kt")
    obfuscated = bytes(
        int(value, 16)
        for value in re.findall(r"0x([0-9A-Fa-f]+)\.toByte\(\)", match.group(1))
    )
    return bytes(value ^ 0x5A for value in obfuscated)


def decrypt_using_kotlin_material(encoded: str) -> str:
    cipher = AES.new(kotlin_material("KEY"), AES.MODE_CBC, kotlin_material("IV"))
    return unpad(cipher.decrypt(base64.b64decode(encoded, validate=True)), AES.block_size).decode()


class LicenseGeneratorTests(unittest.TestCase):
    def test_historical_device_hash_format_remains_accepted_by_verifier_source(self):
        device_id = "historical-test-device"
        historical_hash = hashlib.sha256(("FC_SALT_" + device_id).encode()).hexdigest()[:32]
        current_hash = license_gen.hash_device_id(device_id)
        self.assertNotEqual(historical_hash, current_hash)

        source = (PROJECT / "hook/src/main/java/com/branchcam/hook/LicenseManager.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn('hashDeviceIdWithSalt(androidId, "FC_SALT_")', source)
        self.assertIn('hashDeviceIdWithSalt(androidId, "BC_SALT_")', source)

        payload = f"{historical_hash}|2000000000|PRO".encode()
        encoded = base64.b64encode(AES.new(kotlin_material("KEY"), AES.MODE_CBC,
                                           kotlin_material("IV")).encrypt(pad(payload, 16))).decode()
        self.assertEqual(decrypt_using_kotlin_material(encoded), payload.decode())

    def test_historical_hash_stays_bound_to_original_device(self):
        historical_hash = hashlib.sha256(b"FC_SALT_device-a").hexdigest()[:32]
        other_device_hash = hashlib.sha256(b"FC_SALT_device-b").hexdigest()[:32]
        self.assertNotEqual(historical_hash, other_device_hash)

    def test_generator_key_and_iv_match_android_verifier(self):
        self.assertEqual(license_gen.AES_KEY, kotlin_material("KEY"))
        self.assertEqual(license_gen.AES_IV, kotlin_material("IV"))

    def test_generated_license_uses_android_payload_and_device_hash(self):
        device_id = "branchcam-test-device"
        before = int(license_gen.time.time())
        encoded = license_gen.generate_license(device_id, 3, "PRO")
        device_hash, expiry, tier = decrypt_using_kotlin_material(encoded).split("|")

        self.assertEqual(device_hash, license_gen.hash_device_id(device_id))
        self.assertEqual(tier, "PRO")
        self.assertGreaterEqual(int(expiry), before + 3 * 86400)
        self.assertLessEqual(int(expiry), before + 3 * 86400 + 1)

    def test_wrong_device_has_a_different_binding_hash(self):
        encoded = license_gen.generate_license("device-a", 1, "BASIC")
        device_hash, _, tier = decrypt_using_kotlin_material(encoded).split("|")

        self.assertEqual(tier, "BASIC")
        self.assertNotEqual(device_hash, license_gen.hash_device_id("device-b"))

    def test_expired_test_license_has_expiry_in_the_past(self):
        encoded = license_gen.generate_license("expired-test", -1, "BASIC")
        _, expiry, _ = decrypt_using_kotlin_material(encoded).split("|")
        self.assertLess(int(expiry), int(license_gen.time.time()))

    def test_unknown_tier_is_rejected_by_generator(self):
        with self.assertRaises(ValueError):
            license_gen.generate_license("test-device", 1, "UNLIMITED")


if __name__ == "__main__":
    unittest.main()

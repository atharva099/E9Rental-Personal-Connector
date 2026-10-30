"""Synthetic regression tests; no private runtime required."""
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("audit", pathlib.Path(__file__).with_name("audit-publication.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)

class AuditRegression(unittest.TestCase):
    def scan(self, data, kind="blob", known=()):
        counts = dict.fromkeys([*audit.patterns, "known_external_value", "non_example_email"], 0)
        audit.scan_data(data, kind, known, counts)
        return counts

    def test_generic_file_email_runs_after_all_credential_patterns(self):
        address = b"synthetic-person@" + b"synthetic-mailbox.dev"
        self.assertEqual(1, self.scan(address)["non_example_email"])

    def test_example_email_is_allowed(self):
        for address in (b"demo@example.com", b"demo@fixture.test", b"demo@fixture.invalid"):
            self.assertEqual(0, sum(self.scan(address).values()))

    def test_ordinary_git_identity_is_not_credential(self):
        self.assertEqual(0, sum(self.scan(b"author Person <synthetic-person@" + b"synthetic-mailbox.dev>", "commit").values()))

    def test_known_private_identity_is_blocked_in_metadata(self):
        value = b"synthetic-person@" + b"synthetic-mailbox.dev"
        self.assertEqual(1, self.scan(b"author Person <"+value+b">", "commit", (value,))["known_external_value"])

    def test_credential_check_is_independent_of_object_type(self):
        value = b"ghp_" + b"A" * 30
        for kind in ("blob", "commit", "tag"):
            self.assertEqual(1, self.scan(value, kind)["github_token"])

    def test_only_three_exact_public_paths_are_allowed(self):
        for path in audit.PUBLIC_OUTPUTS: self.assertFalse(audit.bad_path(path))
        for path in ("outputs/private.json","nested/outputs/gmail_output.json","outputs/runtime.db","outputs/gmail_output.json.bak"):
            self.assertTrue(audit.bad_path(path))
    def test_public_output_markers_do_not_bypass_secret_scanning(self):
        import json
        obj={"provisional_schema":True,"official_schema_available":False,"fixture_provenance":"GENERATED_SYNTHETIC_FIXTURES","practice_schema_version":"2.0","data_mode":"SYNTHETIC"}
        self.assertTrue(audit.public_marker_valid(json.dumps(obj).encode()))
        obj["data_mode"]="LIVE";self.assertFalse(audit.public_marker_valid(json.dumps(obj).encode()))
        self.assertFalse(audit.public_marker_valid(b"{}"))
        self.assertEqual(1,self.scan(b"ghp_"+b"A"*30)["github_token"])

if __name__ == "__main__":
    unittest.main()

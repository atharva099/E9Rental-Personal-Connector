"""Synthetic launcher checks. No JVM/server/provider started; no private runtime read."""
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class PersonalLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="e9-personal-launcher-synthetic-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runtime = self.root / "runtime"
        self.client = self.root / "synthetic-client.json"
        self.client.write_text(json.dumps({"installed": {"client_id": "synthetic-only", "client_secret": "synthetic-only"}}))
        self.jar = self.root / "synthetic-placeholder.jar"
        self.jar.write_bytes(b"synthetic test placeholder; Java is mocked")

    def run_launcher(self, initialize=False, identity=True, approve=True, verifier_ok=True, schema_ok=True, fail_init=False, legacy_bootstrap_switch=False, challenge_tenant=None, practice_tenant=None, challenge_runtime=True):
        def quoted(value):
            return "'" + str(value).replace("'", "''") + "'"
        # Replace inherited configuration completely for this child; never inherit real provider secrets.
        env = {k: v for k, v in os.environ.items() if not k.startswith(("GOOGLE_", "PERSONAL_", "GMAIL_", "DRIVE_", "WHATSAPP_", "SANDBOX_", "CHALLENGE_", "PRACTICE_", "TOKEN_", "LOCAL_API_", "UNIFIED_", "SPRING_", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"))}
        if challenge_runtime:
            env["CHALLENGE_RUNTIME_DIR"] = str(self.root / "separate-challenge")
        if challenge_tenant is not None:
            env["CHALLENGE_TENANT_ID"] = challenge_tenant
        if practice_tenant is not None:
            env["PRACTICE_TENANT_ID"] = practice_tenant
        if identity:
            env["PERSONAL_GMAIL_ACCOUNT"] = "owner@example.test"
        script = """
$ErrorActionPreference='Stop'
function java {
    if ($args -contains 'org.springframework.boot.loader.launch.PropertiesLauncher') {
        if ($args -contains '-Dloader.main=com.e9rental.config.PersonalRuntimeSchemaInitializer') {
            Write-Output 'MOCK_EMPTY_SCHEMA_INITIALIZER'
            $global:LASTEXITCODE=SCHEMA_CODE
        } else {
            Write-Output 'MOCK_OFFLINE_VERIFIER'
            $global:LASTEXITCODE=VERIFIER_CODE
        }
    } else {
        if ($env:PERSONAL_DRIVE_ENABLED -ne 'false' -or $env:WHATSAPP_SELECTED_GROUPS -ne '') { throw 'Provider isolation failed' }
        Write-Output 'MOCK_BACKEND_NO_PROCESS_STARTED'
        $global:LASTEXITCODE=0
    }
}
FAULT_HOOK
try { & LAUNCHER -PrivateDirectory RUNTIME -ClientFile CLIENT -Port 19081 -ApplicationJar JARPATH FLAGS }
catch { Write-Output 'SAFE_STARTUP_DENIED'; exit 19 }
"""
        flags = ("-InitializeRuntime " if initialize else "") + ("-ContinueRuntimeBootstrap " if legacy_bootstrap_switch else "") + ("-ApprovePersonalGoogleAccess" if approve else "")
        fault_hook = "function Set-Acl { param($LiteralPath,$AclObject); Write-Output 'SYNTHETIC_INIT_FAILURE'; throw 'synthetic ACL failure' }" if fail_init else ""
        script = script.replace("VERIFIER_CODE", "0" if verifier_ok else "1").replace("SCHEMA_CODE", "0" if schema_ok else "1").replace("LAUNCHER", quoted(ROOT / "scripts/Start-Personal.ps1")).replace("RUNTIME", quoted(self.runtime)).replace("CLIENT", quoted(self.client)).replace("JARPATH", quoted(os.environ.get("PERSONAL_TEST_JAR", self.jar))).replace("FLAGS", flags).replace("FAULT_HOOK", fault_hook)
        return subprocess.run(["pwsh", "-NoProfile", "-NonInteractive", "-Command", script], env=env, capture_output=True, text=True)

    def prepare(self):
        result = self.run_launcher(initialize=True)
        self.assertEqual(result.returncode, 0, "Synthetic initialization failed")
        (self.runtime / "unified").mkdir()
        (self.runtime / "unified/index.mv.db").write_bytes(b"synthetic-mocked-database")
        (self.runtime / "gmail-token.enc").write_bytes(b"synthetic-mocked-token")

    def snapshot(self):
        return {str(p.relative_to(self.runtime)): p.read_bytes() for p in self.runtime.rglob("*") if p.is_file()}

    def test_explicit_new_initialization(self):
        result = self.run_launcher(initialize=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn("MOCK_EMPTY_SCHEMA_INITIALIZER", result.stdout)
        self.assertIn("MOCK_BACKEND_NO_PROCESS_STARTED", result.stdout)
        for name in ("local-api-token.key", "token-encryption.key"):
            self.assertEqual(len(base64.b64decode((self.runtime / name).read_text())), 32)
        self.assertNotEqual((self.runtime / "local-api-token.key").read_bytes(), (self.runtime / "token-encryption.key").read_bytes())
        self.assertRegex((self.runtime / "personal-tenant-id.txt").read_text(), r"^personal-[0-9a-f]{32}$")
        self.assertFalse((self.runtime / ".personal-runtime-initialization-failed").exists())

    def test_initialization_failure_marks_new_runtime_and_reuse_refuses_it(self):
        result = self.run_launcher(initialize=True, fail_init=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SYNTHETIC_INIT_FAILURE", result.stdout)
        marker = self.runtime / ".personal-runtime-initialization-failed"
        self.assertTrue(marker.is_file())
        self.assertEqual(marker.read_text(), "Initialization failed; preserve runtime for manual review.")
        before = self.snapshot()
        reuse = self.run_launcher()
        self.assertNotEqual(reuse.returncode, 0)
        self.assertNotIn("MOCK_BACKEND_NO_PROCESS_STARTED", reuse.stdout)
        self.assertEqual(self.snapshot(), before)

    def test_schema_initialization_failure_marks_runtime_and_prevents_backend(self):
        result = self.run_launcher(initialize=True, schema_ok=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MOCK_EMPTY_SCHEMA_INITIALIZER", result.stdout)
        self.assertNotIn("MOCK_BACKEND_NO_PROCESS_STARTED", result.stdout)
        self.assertTrue((self.runtime / ".personal-runtime-initialization-failed").is_file())

    def test_schema_initializer_runs_only_for_explicit_fresh_initialization(self):
        self.prepare()
        before = self.snapshot()
        result = self.run_launcher()
        self.assertEqual(result.returncode, 0)
        self.assertIn("MOCK_OFFLINE_VERIFIER", result.stdout)
        self.assertNotIn("MOCK_EMPTY_SCHEMA_INITIALIZER", result.stdout)
        self.assertEqual(self.snapshot(), before)

    def test_missing_directory_not_provisioned(self):
        self.assertNotEqual(self.run_launcher().returncode, 0)
        self.assertFalse(self.runtime.exists())

    def test_missing_challenge_runtime_denied_before_runtime_writes_or_backend(self):
        result = self.run_launcher(initialize=True, challenge_runtime=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("MOCK_BACKEND_NO_PROCESS_STARTED", result.stdout)
        self.assertFalse(self.runtime.exists())

    def test_initialization_refuses_existing_even_empty_directory(self):
        self.runtime.mkdir()
        self.assertNotEqual(self.run_launcher(initialize=True).returncode, 0)
        self.assertEqual(list(self.runtime.iterdir()), [])

    def test_every_missing_artifact_denied_without_recreation(self):
        self.prepare()
        for name in ("local-api-token.key", "token-encryption.key", "personal-tenant-id.txt", "gmail-token.enc", "unified/index.mv.db"):
            with self.subTest(name=name):
                file = self.runtime / name
                data = file.read_bytes()
                file.unlink()
                before = self.snapshot()
                result = self.run_launcher()
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("MOCK_BACKEND_NO_PROCESS_STARTED", result.stdout)
                self.assertEqual(self.snapshot(), before)
                file.write_bytes(data)

    def test_successful_reuse_preserves_artifacts(self):
        self.prepare()
        before = self.snapshot()
        result = self.run_launcher()
        self.assertEqual(result.returncode, 0)
        self.assertIn("MOCK_OFFLINE_VERIFIER", result.stdout)
        self.assertEqual(self.snapshot(), before)

    def test_stale_personal_practice_tenant_does_not_block_reuse(self):
        self.prepare()
        personal_tenant = (self.runtime / "personal-tenant-id.txt").read_text().strip()
        before = self.snapshot()
        result = self.run_launcher(practice_tenant=personal_tenant)
        self.assertEqual(result.returncode, 0)
        self.assertIn("MOCK_BACKEND_NO_PROCESS_STARTED", result.stdout)
        self.assertEqual(self.snapshot(), before)

    def test_actual_challenge_tenant_collision_is_rejected(self):
        self.prepare()
        personal_tenant = (self.runtime / "personal-tenant-id.txt").read_text().strip()
        before = self.snapshot()
        result = self.run_launcher(challenge_tenant=personal_tenant)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("MOCK_BACKEND_NO_PROCESS_STARTED", result.stdout)
        self.assertEqual(self.snapshot(), before)

    def test_malformed_personal_tenant_remains_rejected(self):
        self.prepare()
        tenant_file = self.runtime / "personal-tenant-id.txt"
        tenant_file.write_text("personal-invalid")
        before = self.snapshot()
        result = self.run_launcher()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("MOCK_BACKEND_NO_PROCESS_STARTED", result.stdout)
        self.assertEqual(self.snapshot(), before)

    def test_verifier_denial_prevents_backend(self):
        self.prepare()
        before = self.snapshot()
        result = self.run_launcher(verifier_ok=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("MOCK_BACKEND_NO_PROCESS_STARTED", result.stdout)
        self.assertEqual(self.snapshot(), before)

    def test_missing_identity_or_approval_denied(self):
        for kwargs in ({"identity": False}, {"approve": False}):
            self.assertNotEqual(self.run_launcher(initialize=True, **kwargs).returncode, 0)
            self.assertFalse(self.runtime.exists())

    def test_invalid_client_denied_before_initialization_writes(self):
        self.client.write_text("not-json")
        self.assertNotEqual(self.run_launcher(initialize=True).returncode, 0)
        self.assertFalse(self.runtime.exists())

    def test_connection_string_path_rejected(self):
        self.runtime = self.root / "runtime;unsafe"
        self.assertNotEqual(self.run_launcher(initialize=True).returncode, 0)
        self.assertFalse(self.runtime.exists())

    def test_removed_bootstrap_switch_is_rejected_before_runtime_writes_or_java_launch(self):
        result = self.run_launcher(initialize=True, legacy_bootstrap_switch=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("MOCK_EMPTY_SCHEMA_INITIALIZER", result.stdout)
        self.assertNotIn("MOCK_OFFLINE_VERIFIER", result.stdout)
        self.assertNotIn("MOCK_BACKEND_NO_PROCESS_STARTED", result.stdout)
        self.assertFalse(self.runtime.exists())

    def test_removed_bootstrap_switch_cannot_modify_existing_runtime(self):
        self.prepare()
        before = self.snapshot()
        result = self.run_launcher(legacy_bootstrap_switch=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("MOCK_OFFLINE_VERIFIER", result.stdout)
        self.assertNotIn("MOCK_BACKEND_NO_PROCESS_STARTED", result.stdout)
        self.assertEqual(self.snapshot(), before)


if __name__ == "__main__":
    unittest.main()

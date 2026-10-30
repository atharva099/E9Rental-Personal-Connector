"""Redacted reachable-history publication audit; standard library, no network."""
import argparse, io, json, pathlib, re, subprocess, sys

def git(*args):
    return subprocess.check_output(["git", *args])

patterns = {
    "google_api_key": re.compile(rb"AIza[A-Za-z0-9_-]{30,}"),
    "google_oauth_secret": re.compile(rb"GOCSPX-[A-Za-z0-9_-]{15,}"),
    "google_access_token": re.compile(rb"ya29\.[A-Za-z0-9._-]{25,}"),
    "google_refresh_token": re.compile(rb"1//[A-Za-z0-9_-]{25,}"),
    "google_client_identifier": re.compile(rb"\b[0-9]{8,}-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com"),
    "github_token": re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{30,})"),
    "aws_access_key": re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "private_key": re.compile(rb"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----"),
}

email = re.compile(rb"\b[A-Za-z0-9_.+-]+@([A-Za-z0-9.-]+\.[A-Za-z]{2,})\b")
def scan_data(data, object_kind, known, findings):
    for category, pattern in patterns.items():
        if pattern.search(data): findings[category] += 1
    if any(value in data for value in known): findings["known_external_value"] += 1
    # Ordinary author identities are not credentials; exact private values remain blocked.
    for match in email.finditer(data) if object_kind == "blob" else []:
        domain = match.group(1).decode().lower()
        if not (domain.endswith((".test", ".invalid")) or domain in {"example.com", "example.org", "example.net"}):
            findings["non_example_email"] += 1

PUBLIC_OUTPUTS = {"outputs/whatsapp_output.json", "outputs/gmail_output.json", "outputs/drive_output.json"}
def public_marker_valid(data):
    try:
        obj=json.loads(data)
        return (isinstance(obj,dict) and obj.get("provisional_schema") is True and obj.get("official_schema_available") is False and
            obj.get("fixture_provenance")=="GENERATED_SYNTHETIC_FIXTURES" and obj.get("practice_schema_version")=="2.0" and
            obj.get("data_mode") in {"SYNTHETIC","SIMULATED"} and obj.get("live_integration",False) is False)
    except (ValueError,UnicodeError): return False

def bad_path(path):
    if path in PUBLIC_OUTPUTS: return False
    p = pathlib.PurePosixPath(path)
    name = p.name.lower()
    return ((name.startswith(".env") and name != ".env.example") or
            name.endswith((".key", ".pem", ".enc", ".db", ".sqlite", ".sqlite3", ".log", ".har", ".p12", ".pfx", ".jks")) or
            name.startswith(("credentials", "client_secret", "token.")) and name.endswith(".json") or
            any(x in {"secrets", "outputs", "node_modules", "target", "dist"} for x in p.parts))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--private-runtime", type=pathlib.Path)
    parser.add_argument("--oauth-client", type=pathlib.Path)
    args = parser.parse_args()

    known = set()
    if args.private_runtime:
        for name in ("local-api-token.key", "token-encryption.key"):
            p = args.private_runtime / name
            if p.exists():
                value = p.read_bytes().strip()
                if len(value) >= 16: known.add(value)
        p = args.private_runtime / "outputs/gmail_output.json"
        if p.exists():
            data = json.loads(p.read_text(encoding="utf-8-sig"))
            for m in data.get("messages", []):
                meta = m.get("source", {}).get("metadata", {})
                for key in ("from", "to"):
                    for address in re.findall(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", str(meta.get(key, ""))):
                        if not address.lower().endswith((".test", ".invalid")):
                            known.add(address.encode())
    if args.oauth_client:
        p = args.oauth_client
        if not p.exists() and pathlib.Path(str(p)+".json").exists(): p = pathlib.Path(str(p)+".json")
        if p.exists():
            data = json.loads(p.read_text(encoding="utf-8-sig")).get("installed", {})
            for key in ("client_id", "client_secret"):
                if data.get(key): known.add(data[key].encode())
    paths = set(git("log", "--all", "--pretty=format:", "--name-only").decode().splitlines())
    paths.update(git("ls-files").decode().splitlines())
    path_findings = sum(bad_path(p) for p in paths if p)
    objects = git("rev-list", "--objects", "--all").decode().splitlines()
    public_ids={line.split(" ",1)[0] for line in objects if len(line.split(" ",1))==2 and line.split(" ",1)[1] in PUBLIC_OUTPUTS}
    public_ids.update(line.split()[1] for line in git("ls-files","--stage").decode().splitlines() if line.split("\t",1)[-1] in PUBLIC_OUTPUTS)
    objects.extend(line.split()[1] for line in git("ls-files", "--stage").decode().splitlines())
    object_ids = list(dict.fromkeys(line.split(" ", 1)[0] for line in objects))
    batch = io.BytesIO(subprocess.check_output(["git", "cat-file", "--batch"], input=("\n".join(object_ids)+"\n").encode()))
    checked = 0
    metadata_checked = 0
    findings = {key: 0 for key in patterns}
    findings["known_external_value"] = 0
    findings["non_example_email"] = 0
    findings["invalid_public_output_provenance"] = 0
    for oid in object_ids:
        header = batch.readline().split()
        kind = header[1].decode()
        size = int(header[2])
        data = batch.read(size)
        assert batch.read(1) == b"\n"
        if kind not in {"blob", "commit", "tag"}: continue
        if kind == "blob": checked += 1
        else: metadata_checked += 1
        scan_data(data, kind, known, findings)
        if oid in public_ids and (kind!="blob" or not public_marker_valid(data)): findings["invalid_public_output_provenance"] += 1

    total = sum(findings.values()) + path_findings
    print(json.dumps({"result": "PASS" if not total else "BLOCKED",
        "reachable_commits": int(git("rev-list", "--all", "--count")),
        "git_blobs_checked": checked, "commit_tag_objects_checked": metadata_checked,
        "historical_and_index_paths_checked": len(paths),
        "sensitive_artifact_paths": path_findings, "findings_by_category": findings,
        "known_external_values_compared": len(known),
        "scope": "reachable history, staged blobs and tracked paths; no secret values printed"}, sort_keys=True))
    sys.exit(1 if total else 0)

if __name__ == "__main__":
    main()

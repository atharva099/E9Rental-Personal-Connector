"""Read-only Personal governance validation. No credentials or runtime files read."""
import pathlib
import re


def validate_milestones(text):
    section = re.search(r"^## Milestones\s*\n(.*?)(?=^## |\Z)", text, re.M | re.S)
    assert section is not None, "Missing Milestones section"
    rows = [line for line in section.group(1).splitlines() if line.startswith("| P")]
    ids = []
    for row in rows:
        cells = [cell.strip() for cell in row.strip().strip("|").split("|")]
        assert len(cells) == 5 and all(cells), "Milestone must have five nonempty columns"
        match = re.match(r"^(P[0-8])\s+", cells[0])
        assert match is not None, "Invalid milestone ID"
        ids.append(match.group(1))
        assert re.match(r"^(VERIFIED|PARTIAL|BLOCKED|NOT STARTED)(?:\s|$)", cells[1]), "Invalid milestone status"
    assert len(ids) == 9 and set(ids) == {f"P{i}" for i in range(9)}, "Expected exactly one each of P0-P8"
    return ids


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    documents = ["AGENTS.md", "PROJECT_RULES.md", "PROJECT_ROADMAP.md", "SECURITY.md",
                 "PROJECT_TRACKER.md", "TEST_RESULTS.md", "ARCHITECTURE.md", "docs/PERSONAL_CONNECTORS.md"]
    for name in documents:
        path = root / name
        text = path.read_text(encoding="utf-8-sig")
        assert text.strip(), f"Empty document: {name}"
        for target in re.findall(r"\]\(([^)]+)\)", text):
            target = target.strip("<>").split("#")[0]
            if not target or re.match(r"^[A-Za-z]+:", target) or target.startswith("/"):
                continue
            assert (path.parent / target).exists(), f"Broken local link in {name}: {target}"
    validate_milestones((root / "PROJECT_ROADMAP.md").read_text(encoding="utf-8-sig"))
    for name in ["PROJECT_ROADMAP.md", "PROJECT_TRACKER.md", "TEST_RESULTS.md", "docs/PERSONAL_CONNECTORS.md"]:
        assert "225" in (root / name).read_text(encoding="utf-8-sig"), f"Missing test baseline: {name}"
    assert "PERSONAL_DRIVE_ENABLED=false" in (root / ".env.example").read_text()
    print("PASS: 8 governance documents/local links; milestone table has 9 unique IDs P0-P8, five columns and valid statuses; test baseline and disabled Drive default")


if __name__ == "__main__":
    main()

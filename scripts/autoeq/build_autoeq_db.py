#!/usr/bin/env python3
"""
Build the AutoEq headphone profile database bundled with SimpleSound.

Usage:
    git clone --depth 1 https://github.com/jaakkopasanen/AutoEq.git
    python3 scripts/autoeq/build_autoeq_db.py AutoEq/results \
        data/src/main/assets/autoeq/autoeq_profiles.json [--commit <sha>]

(On Windows, clone with `-c core.longpaths=true` or ~300 files fail to check out.)

Reads every "<name> ParametricEQ.txt" under results/<source>/<rig>/<name>/ and
keeps one profile per headphone: the best-ranked source wins, ties broken by rig
and then path so the output is identical run to run.

AutoEq data is MIT licensed - keep its copyright notice in the app's licenses screen.
"""
import argparse
import json
import re
from pathlib import Path

# Bump when the JSON shape changes; AutoEqDatabase.kt checks it.
SCHEMA_VERSION = 1

# Preferred measurement sources, best first. Anything not listed ranks last.
SOURCE_PRIORITY = ["oratory1990", "crinacle", "Rtings", "Innerfidelity"]

# Within one source, preferred rigs (substring match), best first. crinacle and
# Rtings each have several rigs measuring the same model.
RIG_PRIORITY = ["GRAS 43AG-7", "Bruel & Kjaer 4620", "Bruel & Kjaer 5128", "711", "HMS II.3"]

PREAMP_RE = re.compile(r"^Preamp:\s*(-?\d+(?:\.\d+)?)\s*dB", re.M)
FILTER_RE = re.compile(
    r"^Filter\s+\d+:\s+ON\s+(PK|LSC|HSC)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz\s+"
    r"Gain\s+(-?\d+(?:\.\d+)?)\s*dB\s+Q\s+(\d+(?:\.\d+)?)",
    re.M,
)
INVISIBLE_RE = re.compile("[\\u200b-\\u200f\\u2060\\ufeff]")


def headphone_type(rig_folder: str) -> str:
    for t in ("in-ear", "over-ear", "earbud"):
        if t in rig_folder:
            return t
    return "unknown"


def clean_name(folder: str) -> str:
    # A few folder names carry zero-width spaces and other invisible characters.
    return " ".join(INVISIBLE_RE.sub("", folder).split())


def name_key(name: str) -> str:
    # Letters and digits only, so "HD650" / "HD 650" and "AirPods Pro2" / "Airpods Pro 2" merge.
    return re.sub(r"[\W_]+", "", name.lower())


def slug(name: str) -> str:
    # Same characters as name_key, so distinct keys always give distinct ids.
    return re.sub(r"[\W_]+", "-", name.lower()).strip("-")


def rank(order: list, value: str) -> int:
    return next((i for i, v in enumerate(order) if v in value), len(order))


def source_rank(source: str) -> int:
    return SOURCE_PRIORITY.index(source) if source in SOURCE_PRIORITY else len(SOURCE_PRIORITY)


def parse_profile(path: Path):
    text = path.read_text(encoding="utf-8", errors="replace")
    preamp = PREAMP_RE.search(text)
    filters = [
        {"type": t, "fc": float(fc), "gain": float(g), "q": float(q)}
        for t, fc, g, q in FILTER_RE.findall(text)
    ]
    if not preamp or not filters:
        return None  # malformed file - skip it
    return float(preamp.group(1)), filters


def collect(root: Path):
    best = {}  # name_key -> (sort key, profile)
    skipped = 0
    for path in root.glob("*/*/*/* ParametricEQ.txt"):
        source, rig, name = path.parts[-4], path.parts[-3], clean_name(path.parts[-2])
        parsed = parse_profile(path)
        if parsed is None:
            skipped += 1
            continue
        preamp, filters = parsed
        key = (source_rank(source), rank(RIG_PRIORITY, rig), path.as_posix())
        current = best.get(name_key(name))
        if current is None or key < current[0]:
            best[name_key(name)] = (key, {
                "id": slug(name),
                "name": name,
                "type": headphone_type(rig),
                "source": source,
                "preamp": preamp,
                "filters": filters,
            })
    profiles = sorted((p for _, p in best.values()), key=lambda p: (p["name"].lower(), p["name"]))
    return profiles, skipped


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("results_dir", type=Path, help="AutoEq/results")
    ap.add_argument("out_file", type=Path)
    ap.add_argument("--commit", default="", help="AutoEq commit the data came from")
    args = ap.parse_args()

    profiles, skipped = collect(args.results_dir)
    if not profiles:
        raise SystemExit(f"no ParametricEQ.txt files under {args.results_dir}")

    # No timestamp: identical input must give byte-identical output so the scheduled
    # workflow only opens a PR when AutoEq data actually changed.
    db = {"schemaVersion": SCHEMA_VERSION, "autoeqCommit": args.commit, "profiles": profiles}
    args.out_file.parent.mkdir(parents=True, exist_ok=True)
    args.out_file.write_text(json.dumps(db, separators=(",", ":"), ensure_ascii=False), encoding="utf-8")
    size = args.out_file.stat().st_size / 1024
    print(f"Wrote {len(profiles)} headphones to {args.out_file} ({size:.0f} KiB, skipped {skipped} malformed files)")


if __name__ == "__main__":
    main()

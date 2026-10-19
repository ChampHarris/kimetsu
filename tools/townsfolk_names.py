#!/usr/bin/env python3
"""
townsfolk_names.py: generate a sample town roster from the townsfolk data (R7-05), the executable reference for
the Java generator. Same rules: birth year picks the naming era, the region weights surnames, occupations follow
the 1920 sector shares, households share a surname, repetition caps hold, and canon names never appear.

  python tools/townsfolk_names.py --town 40 --region tohoku --seed 7
  python tools/townsfolk_names.py --check        # 2,000 towns: blocklist and repetition caps never break
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "src" / "main" / "resources" / "data" / "kimetsu" / "townsfolk" / "names.json"


def load():
    return json.loads(DATA.read_text(encoding="utf-8"))


def blocked(d) -> tuple[set[str], set[str], set[tuple[str, str]]]:
    b = d["blocklist"]
    sur = {x.split()[0] for x in b["surnames"]}
    given = {x.split()[0] for x in b["given"] if "(with" not in x}
    pairs = set()
    for x in b["given"]:
        if "(with Kamado only)" in x:
            pairs.add(("竈門", x.split()[0]))
    return sur, given, pairs


def pick(rng: random.Random, rows, weight=lambda r: r["weight"]):
    total = sum(weight(r) for r in rows)
    x = rng.uniform(0, total)
    for r in rows:
        x -= weight(r)
        if x <= 0:
            return r
    return rows[-1]


def era_for(d, born: int) -> str:
    for era, block in d["given"].items():
        lo, hi = block["born"]
        if lo <= born <= hi:
            return era
    return "edo" if born < 1835 else "taisho"


def kana_share(d, born: int) -> float:
    ks = {int(k): v for k, v in d["female_kana_share"].items() if not k.startswith("_")}
    share = 1.0
    for year in sorted(ks):
        if born >= year:
            share = ks[year]
    return share


def given_name(d, rng, sex: str, born: int, used: Counter, cap: int, bad_given: set[str], family: set[str]):
    era = era_for(d, born)
    rows = [r for r in d["given"][era]["male" if sex == "m" else "female"] if r["name"] not in bad_given]
    if sex == "f" and era == "taisho":
        kana = rng.random() < kana_share(d, born)
        is_kana = lambda n: all("぀" <= c <= "ヿ" for c in n)
        pool = [r for r in rows if is_kana(r["name"]) == kana] or rows
        rows = pool
    for _ in range(60):
        r = pick(rng, rows)
        if used[r["name"]] < cap and r["romaji"] not in family:     # no two siblings share a name
            return r
    return next((r for r in rows if r["romaji"] not in family), rows[0])


def town(d, rng: random.Random, size: int, region: str, year: int | None = None):
    year = year or d["setting_year"]
    bad_sur, bad_given, bad_pairs = blocked(d)
    caps = d["repetition"]
    surnames = [s for s in d["surnames"] if s["name"] not in bad_sur]
    occs = d["occupations"]
    used_given, used_sur, people = Counter(), Counter(), []
    while len(people) < size:
        for _ in range(30):
            s = pick(rng, surnames, lambda r: r["weight"] * r["region"].get(region, 1.0))
            if used_sur[s["name"]] < caps["max_households_per_surname_per_town"]:
                break
        used_sur[s["name"]] += 1
        n = min(size - len(people), rng.randint(*d["household"]["size"]))
        head_born = year - rng.randint(28, 62)
        trade = pick(rng, occs, lambda o: o["share"])["id"]           # households mostly share one trade
        family: set[str] = set()
        for i in range(n):
            if i == 0:
                born, sex, role = head_born, "m", "head"
            elif i == 1:
                born, sex, role = head_born + rng.randint(0, 8), "f", "spouse"
            elif i == 2 and rng.random() < 0.4:
                born, sex, role = head_born - rng.randint(20, 30), rng.choice("mf"), "elder"
            else:
                born, sex, role = head_born + rng.randint(20, 45), rng.choice("mf"), "child"
            age = year - born
            if age < 0:
                continue
            g = given_name(d, rng, sex, born, used_given, caps["max_same_given_per_town"], bad_given, family)
            if (s["name"], g["name"]) in bad_pairs:
                continue
            used_given[g["name"]] += 1
            family.add(g["romaji"])
            stage = "child" if age < d["ages"]["child"][1] + 1 else "elder" if age >= d["ages"]["elder"][0] else "adult"
            occ = None if stage != "adult" else (trade if (role == "head" or rng.random() < 0.7)
                                                 else pick(rng, occs, lambda o: o["share"])["id"])
            people.append({"name": f"{s['name']} {g['name']}", "romaji": f"{s['romaji']} {g['romaji']}", "sex": sex,
                           "age": age, "role": role, "occupation": occ or stage})
    return people[:size]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Sample townsfolk rosters (R7-05).")
    ap.add_argument("--town", type=int, default=30)
    ap.add_argument("--region", default="kanto")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args(argv)
    d = load()
    if a.check:
        bad_sur, bad_given, bad_pairs = blocked(d)
        worst_given = worst_sur = 0
        for seed in range(2000):
            rng = random.Random(seed)
            people = town(d, rng, 40, d["regions"][seed % 4])
            for p in people:
                sur, given = p["name"].split()
                assert sur not in bad_sur and given not in bad_given and (sur, given) not in bad_pairs, p
            worst_given = max(worst_given, max(Counter(p["name"].split()[1] for p in people).values()))
            heads = Counter(p["name"].split()[0] for p in people if p["role"] == "head")
            worst_sur = max(worst_sur, max(heads.values()))
            homes = {}
            for p in people:
                homes.setdefault((p["name"].split()[0], p.get("home")), []).append(p["romaji"])
        print(f"2000 towns of 40: no canon names; most repeated given name per town {worst_given} "
              f"(cap {d['repetition']['max_same_given_per_town']}), most households per surname {worst_sur} "
              f"(cap {d['repetition']['max_households_per_surname_per_town']})")
        return 0
    for p in town(d, random.Random(a.seed), a.town, a.region):
        print(f"{p['name']:<10} {p['romaji']:<22} {p['sex']} {p['age']:>3}  {p['role']:<6} {p['occupation']}")
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())

# -*- coding: utf-8 -*-
"""
Matris kosularini tez tablolarina cevirir.

    python analysis/aggregate_matrix.py            # gercek kosular (S1_baseline_01 ...)
    python analysis/aggregate_matrix.py --smoke    # duman kosulari (smoke_S1_...)

Girdi : runs/<prefix>S<n>_<kosul>_<tekrar>/ altindaki meta.txt + metrics.json
Cikti : analysis/out/per_run.csv   -- kosu basina bir satir (tez eki tablosu)
        analysis/out/summary.csv   -- senaryo x kosul basina bir satir
        analysis/out/summary.md    -- rapora yapistirilabilir tablolar

Sadece verdict=VALID kosular toplanir (11.4). Gecersizler dislanir ama
sayilari ve sebepleri raporlanir -- kac kosunun atildigi tezde yazilmali.
"""
import argparse
import csv
import json
import re
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNS = ROOT / "runs"
OUT = ROOT / "analysis" / "out"

CONDITIONS = ("baseline", "adaptive")

SCENARIO_TITLES = {
    "S1": "S1 -- Normal load, no attack",
    "S2": "S2 -- Distributed credential stuffing",
    "S3": "S3 -- Shared address (NAT)",
    "S4": "S4 -- Internal degradation",
}


# --------------------------------------------------------------- okuma

def read_meta(path):
    """meta.txt basit key=value. PowerShell BOM birakiyor -- utf-8-sig sart."""
    meta = {}
    with path.open(encoding="utf-8-sig") as fh:
        for line in fh:
            if "=" in line:
                key, value = line.strip().split("=", 1)
                meta[key] = value
    return meta


def collect(prefix):
    """Kosu dizinlerini bul, (gecerli, gecersiz, bozuk) uclusu dondur."""
    pattern = re.compile(r"^" + re.escape(prefix) + r"S(\d)_(baseline|adaptive)_(\d+)$")
    valid, invalid, broken = [], [], []

    for run_dir in sorted(RUNS.iterdir()):
        if not run_dir.is_dir():
            continue
        match = pattern.match(run_dir.name)
        if not match:
            continue

        meta_path = run_dir / "meta.txt"
        metrics_path = run_dir / "metrics.json"
        if not meta_path.exists() or not metrics_path.exists():
            broken.append((run_dir.name, "meta.txt veya metrics.json yok"))
            continue

        try:
            meta = read_meta(meta_path)
            with metrics_path.open(encoding="utf-8") as fh:
                metrics = json.load(fh)
        except Exception as exc:
            broken.append((run_dir.name, "okunamadi: %s" % exc))
            continue

        row = {
            "run": run_dir.name,
            "scenario": "S" + match.group(1),
            "condition": match.group(2),
            "repeat": int(match.group(3)),
            "meta": meta,
            "metrics": metrics,
        }

        if meta.get("verdict") == "VALID":
            valid.append(row)
        else:
            invalid.append((run_dir.name, meta.get("invalid_reasons") or "sebep yazilmamis"))

    return valid, invalid, broken


# --------------------------------------------------------------- metrikler

def ttd(m):
    """Ilk tespit. Tek saldirgan varsa zaten tek deger; min = ilk yakalanan."""
    values = [v for v in (m.get("ttd_seconds") or {}).values() if v is not None]
    return min(values) if values else None


def phase(m, name, stat):
    return (m.get("latency_by_phase") or {}).get(name, {}).get(stat)


# (anahtar, etiket, cikarici, ondalik, sadece-su-senaryolarda)
METRICS = [
    ("ttd_s",       "TTD (s)",              ttd,                                                    3, None),
    ("precision",   "Precision",            lambda m: (m.get("precision_recall") or {}).get("precision"), 3, None),
    ("recall",      "Recall",               lambda m: (m.get("precision_recall") or {}).get("recall"),    3, None),
    ("fpr",         "FPR",                  lambda m: m.get("fpr"),                                 4, None),
    ("cdr",         "CDR (innocent refusal)", lambda m: m.get("cdr"),                               4, {"S3"}),
    ("victim_lock", "Victim lockout",       lambda m: m.get("victim_lockout"),                      4, {"S3"}),
    ("atk_p50_ms",  "Attack median (ms)",   lambda m: phase(m, "attack", "median"),                 1, None),
    ("atk_p95_ms",  "Attack p95 (ms)",      lambda m: phase(m, "attack", "p95"),                    1, None),
    ("decisions",   "Decisions issued",     lambda m: m.get("decisions"),                           0, None),
]


def summarise(values):
    """
    SECENEK A: medyan + (min-maks).
    Ortalama +/- std istersen bu fonksiyonun donusunu degistir:
        "mid": statistics.mean(clean),
        "lo":  statistics.mean(clean) - statistics.pstdev(clean),
        "hi":  statistics.mean(clean) + statistics.pstdev(clean),
    ve fmt() icindeki "-" ayiracini "+/-" yap.
    """
    clean = [v for v in values if v is not None]
    if not clean:
        return None
    return {
        "n": len(clean),
        "mid": statistics.median(clean),
        "lo": min(clean),
        "hi": max(clean),
    }


def fmt(agg, digits):
    if agg is None:
        return "--"
    if digits == 0:
        mid, lo, hi = int(round(agg["mid"])), int(round(agg["lo"])), int(round(agg["hi"]))
    else:
        mid, lo, hi = (round(agg[k], digits) for k in ("mid", "lo", "hi"))
    if lo == hi:
        return str(mid)
    return "%s (%s-%s)" % (mid, lo, hi)


def detection_rate(rows):
    """Kac kosuda saldirgan HIC tespit edildi. Baseline icin bu 0/5 cikmali
    ve bu bir eksik veri degil, bulgunun kendisi."""
    detected = sum(1 for r in rows if ttd(r["metrics"]) is not None)
    return detected, len(rows)


# --------------------------------------------------------------- cikti

def build_cell(rows, scenario):
    """Bir (senaryo x kosul) hucresi icin etiket -> bicimlenmis deger."""
    cell = {}
    hit, total = detection_rate(rows)
    # Saldirgansiz senaryoda (S1) "0/5 runs" yazarsa okuyucu bunu basarisizlik
    # sanar; oysa orada olculecek bir sey yok. Senaryo adina degil rol listesine
    # bakiyoruz -- ileride saldirgansiz bir S5 eklenirse kendiliginden dogru olur.
    if not any(r["metrics"].get("identities", {}).get("attacker") for r in rows):
        cell["Attacker detected"] = "n/a (no attacker in scenario)"
    else:
        cell["Attacker detected"] = "%d/%d runs" % (hit, total)
    for key, label, extract, digits, only in METRICS:
        if only and scenario not in only:
            continue
        cell[label] = fmt(summarise([extract(r["metrics"]) for r in rows]), digits)
    return cell


def write_per_run(valid, path):
    fields = ["run", "scenario", "condition", "repeat", "requests"] + \
             [key for key, _, _, _, _ in METRICS] + ["problems"]
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        for r in valid:
            m = r["metrics"]
            row = {
                "run": r["run"], "scenario": r["scenario"],
                "condition": r["condition"], "repeat": r["repeat"],
                "requests": m.get("requests"),
                "problems": " | ".join(m.get("problems") or []),
            }
            for key, _, extract, _, _ in METRICS:
                row[key] = extract(m)
            writer.writerow(row)


def write_summary(scenarios, md_path, csv_path):
    lines = ["# Matrix results", ""]
    csv_rows = []

    for scenario in sorted(scenarios):
        cells = scenarios[scenario]
        lines.append("## " + SCENARIO_TITLES.get(scenario, scenario))
        lines.append("")

        counts = {c: len(cells[c]["rows"]) for c in CONDITIONS if c in cells}
        header = ["Metric"] + ["%s (n=%d)" % (c, counts[c]) for c in CONDITIONS if c in cells]
        lines.append("| " + " | ".join(header) + " |")
        lines.append("|" + "|".join(["---"] * len(header)) + "|")

        labels = []
        for c in CONDITIONS:
            for label in cells.get(c, {}).get("cell", {}):
                if label not in labels:
                    labels.append(label)

        for label in labels:
            values = [cells[c]["cell"].get(label, "--") for c in CONDITIONS if c in cells]
            lines.append("| " + " | ".join([label] + values) + " |")
            row = {"scenario": scenario, "metric": label}
            for c in CONDITIONS:
                row[c] = cells[c]["cell"].get(label, "") if c in cells else ""
            csv_rows.append(row)
        lines.append("")

    md_path.write_text("\n".join(lines), encoding="utf-8")

    with csv_path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=["scenario", "metric"] + list(CONDITIONS))
        writer.writeheader()
        writer.writerows(csv_rows)

    return lines


# --------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--smoke", action="store_true",
                        help="gercek kosular yerine smoke_ onekli kosulari topla")
    args = parser.parse_args()
    prefix = "smoke_" if args.smoke else ""

    valid, invalid, broken = collect(prefix)

    if not valid:
        print("Toplanacak gecerli kosu bulunamadi (onek: %r)." % prefix)
        if invalid or broken:
            print("Gecersiz: %d, bozuk: %d" % (len(invalid), len(broken)))
        return 1

    # senaryo -> kosul -> {rows, cell}
    scenarios = {}
    for r in valid:
        scenarios.setdefault(r["scenario"], {}).setdefault(
            r["condition"], {"rows": []})["rows"].append(r)
    for scenario, conds in scenarios.items():
        for cond, data in conds.items():
            data["cell"] = build_cell(data["rows"], scenario)

    OUT.mkdir(parents=True, exist_ok=True)
    write_per_run(valid, OUT / "per_run.csv")
    lines = write_summary(scenarios, OUT / "summary.md", OUT / "summary.csv")

    print("\n".join(lines))
    print("KAPSAM: %d gecerli kosu" % len(valid))
    for scenario in sorted(scenarios):
        parts = ["%s=%d" % (c, len(scenarios[scenario][c]["rows"]))
                 for c in CONDITIONS if c in scenarios[scenario]]
        print("  %s  %s" % (scenario, "  ".join(parts)))

    if invalid:
        print("\nGECERSIZ (%d) -- tez metnine yazilmali:" % len(invalid))
        for name, reason in invalid:
            print("  %-24s %s" % (name, reason))
    if broken:
        print("\nOKUNAMAYAN (%d):" % len(broken))
        for name, reason in broken:
            print("  %-24s %s" % (name, reason))

    seen = set()
    for r in valid:
        for problem in (r["metrics"].get("problems") or []):
            seen.add(problem)
    if seen:
        print("\nKOSULARDA RAPORLANAN SORUNLAR (%d farkli):" % len(seen))
        for problem in sorted(seen):
            print("  " + problem)

    print("\nYazildi: %s" % OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
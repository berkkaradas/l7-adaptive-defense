#!/usr/bin/env python3
"""
Bir koşu klasörünü beş metriğe çevirir.

Kullanım:  python analysis/analyze_run.py runs/S1_adaptive_01

Bağımlılık yok — pandas vs. gerekmiyor, düz Python.
"""
import json
import math
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

# Cezalandırma sayılan kararlar. ALLOW zaten yayınlanmıyor (Karar Kaydı 4.12.2)
# ama gelirse de pozitif sayılmamalı.
PUNISHMENTS = {"RATE_LIMIT", "TARPIT", "DROP"}

LEGITIMATE_ROLES = {"legit", "innocent"}


# --------------------------------------------------------------- yardımcılar

def parse_ts(text):
    """
    ISO-8601 zaman damgası okur.

    İki kaynak, iki farklı biçim: k6 yerel saatle 7 basamak kesir yazıyor
    ('...28.6469233+01:00'), Java'nın Instant'ı UTC'de 9 basamak
    ('...44.205954500Z'). Python'un fromisoformat'ı en fazla 6 basamak
    kabul ediyor ve eski sürümlerde 'Z' sonekini tanımıyor.

    Kesir kısmını RAKAM OLMAYAN İLK KARAKTERDE kesiyoruz — offset'in kendi
    rakamlarına ('+01:00' içindeki 0,1,0,0) dokunmamak için. Bunları da
    toplarsan kesir 10 basamak sanılır ve offset yanlış yerden kopar.
    """
    text = text.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"

    if "." in text:
        head, _, tail = text.partition(".")
        i = 0
        while i < len(tail) and tail[i].isdigit():
            i += 1
        fraction, offset = tail[:i], tail[i:]
        text = f"{head}.{fraction[:6].ljust(6, '0')}{offset}"

    return datetime.fromisoformat(text).astimezone(timezone.utc)


def pct(values, q):
    """Nearest-rank yüzdelik. Küçük örneklemde interpolasyondan daha dürüst:
    var olmayan bir değeri uydurmuyor, gerçekten ölçülmüş bir isteği döndürüyor."""
    if not values:
        return None
    ordered = sorted(values)
    rank = math.ceil(q * len(ordered))
    return ordered[max(0, min(len(ordered) - 1, rank - 1))]


def read_jsonl(path):
    """
    JSON-lines dosyası okur, kodlamayı BOM'dan tespit ederek.

    Neden gerekli: bu dosyaları PowerShell yönlendirmesi üretiyor ve Windows
    PowerShell 5.1 sürüme/ayara göre UTF-16 LE, UTF-8-BOM veya düz UTF-8
    yazabiliyor. Üçü de geçerli metin ama sabit bir kodlama varsayarsan
    ikisinde UnicodeDecodeError alırsın — üstelik analiz sırasında, koşu
    çoktan bitmişken.
    """
    if not path.exists():
        return []

    raw = path.read_bytes()
    if raw[:2] in (b"\xff\xfe", b"\xfe\xff"):
        text = raw.decode("utf-16")        # BOM'u codec kendisi ayıklıyor
    elif raw[:3] == b"\xef\xbb\xbf":
        text = raw.decode("utf-8-sig")
    else:
        text = raw.decode("utf-8", errors="replace")

    out = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            continue   # console consumer sonda "Processed a total of N messages" basıyor
    return out


# --------------------------------------------------------------- yükleme

def load_requests(run_dir):
    """
    k6 ham çıktısından her isteğin süresi, fazı, rolü ve kimliği.

    Yalnızca http_req_duration noktalarını alıyoruz; k6 onlarca başka metrik
    de yazıyor ve hepsini okumak dosyayı gereksiz büyütür.
    """
    requests = []
    for row in read_jsonl(run_dir / "k6_raw.json"):
        if row.get("type") != "Point" or row.get("metric") != "http_req_duration":
            continue
        data = row["data"]
        tags = data.get("tags", {})
        requests.append({
            "time": parse_ts(data["time"]),
            "duration_ms": data["value"],
            # setup istekleri hiçbir senaryoda koşmadığı için etiketsiz gelir
            "phase": tags.get("scenario", "setup"),
            "role": tags.get("role"),
            "identity": tags.get("identity"),
            "status": tags.get("status"),
        })
    return requests


# --------------------------------------------------------------- metrikler

def latency_by_phase(requests):
    """Faz başına gecikme — YALNIZCA meşru trafik.

    Saldırganın gecikmesi ilgimizi çekmiyor; hatta tarpit altında saniyeler
    sürüyor ve karışsaydı sonucu tamamen bozardı.
    """
    buckets = defaultdict(list)
    for r in requests:
        if r["role"] in LEGITIMATE_ROLES:
            buckets[r["phase"]].append(r["duration_ms"])

    table = {}
    for phase in ("warmup", "baseline", "attack", "recovery"):
        values = buckets.get(phase, [])
        if not values:
            continue
        table[phase] = {
            "n": len(values),
            "median": round(pct(values, 0.50), 2),
            "p90": round(pct(values, 0.90), 2),
            "p95": round(pct(values, 0.95), 2),
            "max": round(max(values), 2),
        }
    return table


def identities_by_role(requests):
    roles = defaultdict(set)
    for r in requests:
        if r["role"] and r["identity"]:
            roles[r["role"]].add(r["identity"])
    return roles


def first_request_at(requests, identity):
    times = [r["time"] for r in requests if r["identity"] == identity]
    return min(times) if times else None


def first_decision_at(decisions, identity):
    times = [parse_ts(d["issuedAt"]) for d in decisions
             if d["identity"] == identity and d["decision"] in PUNISHMENTS]
    return min(times) if times else None


def time_to_detect(requests, decisions, attackers):
    """
    TTD = ilk kötü niyetli istek  →  o kimliğe verilen ilk ceza.

    Senaryolarımızda saldırgan yalnızca kötü niyetli trafik ürettiği için
    'ilk isteği' ile 'ilk kötü niyetli isteği' aynı şey.
    """
    result = {}
    for identity in sorted(attackers):
        started = first_request_at(requests, identity)
        detected = first_decision_at(decisions, identity)
        if started and detected:
            result[identity] = round((detected - started).total_seconds(), 3)
        else:
            result[identity] = None   # hiç yakalanmadı
    return result


def punished_identities(decisions):
    return {d["identity"] for d in decisions if d["decision"] in PUNISHMENTS}

def unmatched_identities(roles, punished):
    """
    Cezalandırılmış ama hiçbir role ait olmayan kimlikler.

    Neredeyse tek bir sebebi var: saldırgan etiketindeki IP, Gateway'in
    gerçekte gördüğü IP ile uyuşmuyor. O zaman sistem saldırganı yakalamış
    olur ama analiz onu tanıyamaz — TTD 'yakalanmadı' çıkar. Sayı makul
    görünür, yanlıştır, ve fark edilmesi zordur. O yüzden bağırıyoruz.
    """
    known = set().union(*roles.values()) if roles else set()
    return sorted(punished - known)


def blocked_identities(requests):
    """
    Fiilen hizmet alamayanlar. Karar kaydından farklı bir şey ölçüyor:
    orada 'sistem ne dedi', burada 'kullanıcı ne yaşadı'.
    """
    return {r["identity"] for r in requests
            if r["status"] and r["status"] != "200" and r["identity"]}


def precision_recall(roles, punished):
    legitimate = set().union(*(roles[r] for r in LEGITIMATE_ROLES if r in roles)) \
        if any(r in roles for r in LEGITIMATE_ROLES) else set()
    attackers = roles.get("attacker", set())

    true_positive = len(punished & attackers)
    false_positive = len(punished & legitimate)
    false_negative = len(attackers - punished)

    precision = true_positive / (true_positive + false_positive) \
        if (true_positive + false_positive) else None
    recall = true_positive / (true_positive + false_negative) \
        if (true_positive + false_negative) else None

    return {
        "tp": true_positive, "fp": false_positive, "fn": false_negative,
        "precision": round(precision, 3) if precision is not None else None,
        "recall": round(recall, 3) if recall is not None else None,
    }


def classification(decisions):
    """Hangi kimliğe hangi saldırı tipi atandı — sınıflandırmanın doğruluğu için."""
    seen = defaultdict(lambda: defaultdict(int))
    for d in decisions:
        if d["decision"] in PUNISHMENTS:
            seen[d["identity"]][d["attackType"]] += 1
    return {k: dict(v) for k, v in seen.items()}


def validity(run_dir, requests):
    """
    Koşunun kullanılabilir olup olmadığı. Metriklerden ÖNCE bakılacak yer.
    Geçersiz bir koşunun sayıları makul görünür ama anlamsızdır.
    """
    problems = []

    summary_path = run_dir / "k6_summary.json"
    if summary_path.exists():
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
        metrics = summary.get("metrics", {})
        dropped = metrics.get("dropped_iterations", {}).get("values", {}).get("count", 0)
        if dropped:
            problems.append(
                f"k6 {int(dropped)} iterasyon düşürdü — yük üreteci hedef hıza "
                f"yetişemedi, bu koşu geçersiz")

    non200 = [r for r in requests
              if r["role"] in LEGITIMATE_ROLES and r["status"] not in (None, "200")]
    if non200:
        codes = defaultdict(int)
        for r in non200:
            codes[r["status"]] += 1
        problems.append(f"meşru trafikte 200 olmayan yanıtlar: {dict(codes)}")

    return problems


# --------------------------------------------------------------- rapor

def main():
    if len(sys.argv) != 2:
        print("kullanım: python analysis/analyze_run.py <koşu klasörü>")
        raise SystemExit(2)

    run_dir = Path(sys.argv[1])
    requests = load_requests(run_dir)
    decisions = read_jsonl(run_dir / "decisions.jsonl")
    roles = identities_by_role(requests)

    attackers = roles.get("attacker", set())
    legitimate = set().union(*(roles[r] for r in LEGITIMATE_ROLES if r in roles)) \
        if any(r in roles for r in LEGITIMATE_ROLES) else set()
    innocents = roles.get("innocent", set())

    punished = punished_identities(decisions)
    blocked = blocked_identities(requests)

    problems = validity(run_dir, requests)
    unmatched = unmatched_identities(roles, punished)
    if unmatched:
        problems.append(
            f"karar verilen ama hiçbir role eşleşmeyen kimlikler: {unmatched} "
            f"-> CLIENT_IP yanlış olabilir")

    metrics = {
        "run": run_dir.name,
        "requests": len(requests),
        "decisions": len(decisions),
        "identities": {role: sorted(ids) for role, ids in roles.items()},
        "latency_by_phase": latency_by_phase(requests),
        "ttd_seconds": time_to_detect(requests, decisions, attackers),
        "fpr": round(len(punished & legitimate) / len(legitimate), 4) if legitimate else None,
        "cdr": round(len(blocked & innocents) / len(innocents), 4) if innocents else None,
        "precision_recall": precision_recall(roles, punished),
        "classification": classification(decisions),
        "problems": problems,
    }

    (run_dir / "metrics.json").write_text(
        json.dumps(metrics, indent=2, ensure_ascii=False), encoding="utf-8")

    # ------------------------------------------------------------ ekran
    print(f"\n=== {metrics['run']} ===")
    print(f"{len(requests)} istek, {len(decisions)} karar")

    if metrics["problems"]:
        print("\n!! GEÇERLİLİK UYARISI")
        for p in metrics["problems"]:
            print(f"   - {p}")

    print("\nMeşru trafik gecikmesi (ms)")
    print(f"  {'faz':<10}{'n':>6}{'medyan':>10}{'p90':>9}{'p95':>9}{'max':>10}")
    for phase, row in metrics["latency_by_phase"].items():
        mark = "   <- atılıyor" if phase == "warmup" else ""
        print(f"  {phase:<10}{row['n']:>6}{row['median']:>10}{row['p90']:>9}"
              f"{row['p95']:>9}{row['max']:>10}{mark}")

    print("\nKimlikler")
    for role, ids in sorted(metrics["identities"].items()):
        print(f"  {role:<10}{len(ids):>3}  {', '.join(sorted(ids))}")

    if attackers:
        print("\nTespit süresi (saniye)")
        for identity, seconds in metrics["ttd_seconds"].items():
            print(f"  {identity:<28}{seconds if seconds is not None else 'YAKALANMADI'}")

    pr = metrics["precision_recall"]
    print(f"\nFPR  {metrics['fpr']}    CDR  {metrics['cdr']}")
    print(f"Precision {pr['precision']}  Recall {pr['recall']}  "
          f"(TP {pr['tp']} / FP {pr['fp']} / FN {pr['fn']})")

    if metrics["classification"]:
        print("\nSınıflandırma")
        for identity, types in metrics["classification"].items():
            print(f"  {identity:<28}{types}")

    print(f"\n-> {run_dir / 'metrics.json'}\n")


if __name__ == "__main__":
    main()
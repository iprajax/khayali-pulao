#!/usr/bin/env python3
"""
Download NSE's public daily bhavcopy (every listed stock's daily prices) into YOUR OWN local cache.
This project never redistributes the data: each user runs this (or the app) to fetch their own copy.

  python3 tools/market/fetch.py --from 2006-01-01 --to today --dir ~/.khayali/bhav [--workers 4]

Resumable: files already on disk are skipped; days with no file (weekends, holidays) are remembered
in .missing so they aren't asked for again. Polite: a few parallel requests, retries with back-off.
"""
import argparse, concurrent.futures as cf, datetime as dt, os, sys, time, urllib.request

HOST = "nsearchives.nseindia.com"
NEW_FROM = dt.date(2024, 7, 8)

def url(day):
    if day >= NEW_FROM:
        return f"https://{HOST}/content/cm/BhavCopy_NSE_CM_0_0_0_{day:%Y%m%d}_F_0000.csv.zip"
    mon = day.strftime("%b").upper()
    return f"https://{HOST}/content/historical/EQUITIES/{day.year}/{mon}/cm{day:%d}{mon}{day.year}bhav.csv.zip"

def fetch(day, folder):
    path = os.path.join(folder, f"{day:%Y%m%d}.csv.zip")
    if os.path.exists(path): return day, "have"
    for attempt in range(4):
        try:
            req = urllib.request.Request(url(day), headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=30) as r:
                data = r.read()
            with open(path + ".part", "wb") as f: f.write(data)
            os.replace(path + ".part", path)
            return day, "got"
        except urllib.error.HTTPError as e:
            if e.code == 404: return day, "none"
            time.sleep(2 ** attempt)
        except Exception:
            time.sleep(2 ** attempt)
    return day, "fail"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="start", default="2000-01-03")
    ap.add_argument("--to", dest="end", default="today")
    ap.add_argument("--dir", default=os.path.expanduser("~/.khayali/bhav"))
    ap.add_argument("--workers", type=int, default=4)
    a = ap.parse_args()
    start = dt.date.fromisoformat(a.start); end = dt.date.today() if a.end == "today" else dt.date.fromisoformat(a.end)
    os.makedirs(a.dir, exist_ok=True)
    missing_file = os.path.join(a.dir, ".missing"); missing = set(open(missing_file).read().split()) if os.path.exists(missing_file) else set()
    days = [start + dt.timedelta(d) for d in range((end - start).days + 1)]
    days = [d for d in days if d.weekday() < 5 and f"{d:%Y%m%d}" not in missing and not (d >= dt.date.today())]
    counts = {"have": 0, "got": 0, "none": 0, "fail": 0}
    with cf.ThreadPoolExecutor(a.workers) as ex:
        for i, (day, status) in enumerate(ex.map(lambda d: fetch(d, a.dir), days), 1):
            counts[status] += 1
            if status == "none": missing.add(f"{day:%Y%m%d}")
            if i % 100 == 0 or i == len(days): print(f"{i}/{len(days)} {counts}", flush=True)
    open(missing_file, "w").write("\n".join(sorted(missing)))
    print("done", counts)
    return 0 if counts["fail"] == 0 else 1

if __name__ == "__main__":
    sys.exit(main())

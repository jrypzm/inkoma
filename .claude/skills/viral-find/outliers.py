#!/usr/bin/env python3
"""Find the reels that beat their own account's normal.

python3 outliers.py reels.json [--top 15] [--min-x 5] [--min-views 100000] [--today YYYY-MM-DD] [--out outliers.json]

reels.json is Instagram scraper output (a list, or {"items": [...]}). A CSV with the columns
account,views,url,caption,date works too, so a list typed from a screenshot is enough.
"""
import argparse
import csv
import datetime as dt
import json
import re
import statistics
import sys

SETTLED_DAYS = 7  # Younger reels are still climbing, so they make a poor yardstick.
MIN_BASELINE = 5


def load(path):
    if path.endswith(".csv"):
        with open(path, encoding="utf-8", newline="") as f:
            return [{"account": r["account"].strip().lstrip("@"), "views": int(re.sub(r"\D", "", r["views"]) or 0), "url": r.get("url", ""),
                     "caption": r.get("caption", ""), "date": r.get("date", "")} for r in csv.DictReader(f)]
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    rows = data["items"] if isinstance(data, dict) else data
    return [{"account": r.get("ownerUsername", "?"), "views": r.get("videoPlayCount") or r.get("videoViewCount") or 0, "url": r.get("url", ""),
             "caption": r.get("caption") or "", "date": (r.get("timestamp") or "")[:10], "likes": r.get("likesCount"),
             "comments": r.get("commentsCount"), "seconds": r.get("videoDuration"), "pinned": bool(r.get("isPinned")),
             "videoUrl": r.get("videoUrl")} for r in rows]


def age_days(date, today):
    try:
        return (today - dt.date.fromisoformat(date)).days
    except ValueError:
        return None


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("path")
    p.add_argument("--top", type=int, default=15)
    p.add_argument("--min-x", type=float, default=5)
    p.add_argument("--min-views", type=int, default=100000)
    p.add_argument("--today", help="YYYY-MM-DD, defaults to today")
    p.add_argument("--out", default="outliers.json")
    a = p.parse_args()
    today = dt.date.fromisoformat(a.today) if a.today else dt.date.today()

    # The same video is often posted twice, or as a trial reel. Keep the bigger copy.
    best = {}
    for r in load(a.path):
        if r["views"] <= 0:
            continue
        key = (r["account"], re.sub(r"\W+", " ", r["caption"]).strip().lower()[:80] or r["url"])
        if key not in best or r["views"] > best[key]["views"]:
            best[key] = r
    by_account = {}
    for r in best.values():
        r["age_days"] = age_days(r["date"], today)
        by_account.setdefault(r["account"], []).append(r)
    if not by_account:
        sys.exit("No reels with view counts in that file.")

    results, summary = [], []
    for account, rows in by_account.items():
        top_x, hits = 0, 0
        for r in rows:
            # Pinned reels are hand-picked winners, so they can't count as the account's normal.
            others = [o for o in rows if o is not r and not o.get("pinned")] or [o for o in rows if o is not r]
            settled = [o for o in others if o["age_days"] is not None and o["age_days"] >= SETTLED_DAYS]
            solid = len(settled) >= MIN_BASELINE
            pool = settled if solid else others
            base = statistics.median(o["views"] for o in pool) if pool else r["views"]
            x = r["views"] / max(base, 1)
            top_x = max(top_x, x)
            if x >= a.min_x and r["views"] >= a.min_views:
                hits += 1
                results.append({**r, "x": round(x, 1), "account_typical": round(base), "too_new": r["age_days"] is not None and r["age_days"] < 2, "solid_baseline": solid})
        views = sorted(r["views"] for r in rows)
        settled_all = [r["views"] for r in rows if r["age_days"] is not None and r["age_days"] >= SETTLED_DAYS and not r.get("pinned")]
        solid = len(settled_all) >= MIN_BASELINE
        summary.append({"account": account, "reels": len(rows), "typical_views": round(statistics.median(settled_all if solid else views)),
                        "best_views": views[-1], "best_x": round(top_x, 1), "outliers": hits, "solid_baseline": solid})
    # Multiples measured against settled reels come first, because they can be trusted.
    results.sort(key=lambda r: (r["solid_baseline"], r["x"], r["views"]), reverse=True)
    summary.sort(key=lambda s: s["best_views"], reverse=True)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump({"today": today.isoformat(), "accounts": summary, "outliers": results[: max(a.top, 50)]}, f, ensure_ascii=False, indent=1)

    print(f"{'account':<22}{'reels':>6}{'typical':>12}{'best':>14}{'best x':>10}{'outliers':>10}")
    for s in summary:
        mark = "" if s["solid_baseline"] else "  * most reels under a week old"
        print(f"{s['account']:<22}{s['reels']:>6}{s['typical_views']:>12,}{s['best_views']:>14,}{s['best_x']:>9.1f}x{s['outliers']:>10}{mark}")
    print(f"\nTop outliers (at least {a.min_x:g}x the account's typical reel and {a.min_views:,} views):")
    for r in results[: a.top]:
        note = " [under 2 days old]" if r["too_new"] else ("" if r["solid_baseline"] else " [multiple inflated: compared with reels still climbing]")
        cap = re.sub(r"\s+", " ", r["caption"])[:58]
        print(f"{r['x']:>7.1f}x {r['views']:>13,} (typical {r['account_typical']:,})  {r['account']:<18} {r['date']}  {cap}{note}")
    if not results:
        print("None. Lower --min-x or --min-views (the best x column shows what each account has), or add more accounts.")
    print(f"\nSaved {a.out}")


if __name__ == "__main__":
    main()

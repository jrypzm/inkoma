#!/usr/bin/env python3
"""python3 fetch_reels.py account1 account2 ... [--limit 30] [--max-usd 0.5] [--out reels.json]

Pulls recent reels through Apify's Instagram Reel Scraper. Needs APIFY_TOKEN. You pay Apify per reel,
so the run stops at --max-usd.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

KEEP = ["ownerUsername", "url", "shortCode", "caption", "videoPlayCount", "videoViewCount", "likesCount", "commentsCount", "videoDuration", "timestamp", "isPinned", "videoUrl"]


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("accounts", nargs="+")
    p.add_argument("--limit", type=int, default=30)
    p.add_argument("--max-usd", type=float, default=0.5)
    p.add_argument("--out", default="reels.json")
    a = p.parse_args()
    token = os.environ.get("APIFY_TOKEN")
    if not token:
        sys.exit("APIFY_TOKEN is not set. Get one at https://console.apify.com/settings/integrations, or use a screenshot instead (see SKILL.md).")
    accounts = [x.strip().lstrip("@").split("instagram.com/")[-1].split("?")[0].strip("/") for x in a.accounts]
    base = os.environ.get("APIFY_BASE_URL", "https://api.apify.com")
    query = urllib.parse.urlencode({"maxTotalChargeUsd": a.max_usd, "timeout": 280})
    req = urllib.request.Request(f"{base}/v2/acts/apify~instagram-reel-scraper/run-sync-get-dataset-items?{query}",
                                 data=json.dumps({"username": accounts, "resultsLimit": a.limit}).encode(),
                                 headers={"content-type": "application/json", "authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            items = json.load(resp)
    except urllib.error.HTTPError as err:
        sys.exit(f"Apify said {err.code}: {err.read()[:300]!r}")
    rows = [{k: r.get(k) for k in KEEP} for r in items if r.get("ownerUsername")]
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)
    found = sorted({r["ownerUsername"] for r in rows})
    print(f"{len(rows)} reels from {len(found)} accounts saved to {a.out}")
    missing = [x for x in accounts if x not in found]
    if missing:
        print("No reels came back for: " + ", ".join(missing))


if __name__ == "__main__":
    main()

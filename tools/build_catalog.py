#!/usr/bin/env python3
"""Builds catalog/channels.json for the TV Cast app.

Source: the open iptv-org database (https://github.com/iptv-org/iptv), a community list of
free, publicly available TV streams. Channels are grouped Language -> Country -> Channels.

Usage:
  python3 tools/build_catalog.py                 # downloads the data
  python3 tools/build_catalog.py --data DIR      # uses already-downloaded JSON files in DIR
"""
import argparse
import datetime
import json
import os
import urllib.request

BASE = "https://iptv-org.github.io/api/"
FILES = ["streams", "feeds", "channels", "languages", "countries", "categories"]
MAX_STREAMS_PER_CHANNEL = 4


def load(name, data_dir):
    if data_dir:
        with open(os.path.join(data_dir, name + ".json"), encoding="utf-8") as f:
            return json.load(f)
    req = urllib.request.Request(BASE + name + ".json", headers={"User-Agent": "TVCast-catalog"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


def playable(url):
    u = url.lower()
    if not (u.startswith("http://") or u.startswith("https://")):
        return False
    path = u.split("?")[0]
    return not path.endswith(".mpd")  # DASH isn't supported by the TV player page


def quality_rank(q):
    try:
        return int((q or "0").rstrip("pi"))
    except ValueError:
        return 0


def build(data_dir=None):
    d = {n: load(n, data_dir) for n in FILES}
    channels = {c["id"]: c for c in d["channels"]}
    feeds = {(f["channel"], f["id"]): f for f in d["feeds"]}
    main_feed = {f["channel"]: f for f in d["feeds"] if f.get("is_main")}
    lang_names = {l["code"]: l["name"] for l in d["languages"]}
    countries = {c["code"]: c for c in d["countries"]}
    categories = {c["id"]: c["name"] for c in d["categories"]}

    # channel id -> {"streams": [...], "langs": set()}
    found = {}
    for s in d["streams"]:
        cid = s.get("channel")
        if not cid or cid not in channels or not playable(s["url"]):
            continue
        ch = channels[cid]
        if ch.get("is_nsfw") or ch.get("closed"):
            continue
        feed = feeds.get((cid, s.get("feed"))) if s.get("feed") else main_feed.get(cid)
        entry = found.setdefault(cid, {"streams": [], "langs": set()})
        if feed:
            entry["langs"].update(feed.get("languages") or [])
        labels = s.get("labels") or []
        entry["streams"].append({
            "u": s["url"],
            "r": s.get("referrer") or "",
            "a": s.get("user_agent") or "",
            "q": s.get("quality") or "",
            "geo": "Geo-blocked" in labels,
            "part": "Not 24/7" in labels,
        })

    out_channels = []
    by_lang = {}  # lang -> country -> [channel index]
    for cid, e in found.items():
        ch = channels[cid]
        country = ch.get("country") or ""
        # best streams first: working everywhere, then 24/7, then higher quality
        e["streams"].sort(key=lambda x: (x["geo"], x["part"], -quality_rank(x["q"])))
        streams = e["streams"][:MAX_STREAMS_PER_CHANNEL]
        langs = sorted(e["langs"])
        if not langs:
            cl = (countries.get(country) or {}).get("languages") or []
            langs = cl[:1] or ["und"]
        best = streams[0]
        cats = [categories.get(c, c.title()) for c in (ch.get("categories") or [])]
        idx = len(out_channels)
        out_channels.append({
            "n": ch["name"],
            "c": country,
            "g": cats[0] if cats else "",
            "q": best["q"],
            "geo": all(x["geo"] for x in streams),
            "s": [[x["u"], x["r"], x["a"]] for x in streams],
        })
        for lang in langs:
            by_lang.setdefault(lang, {}).setdefault(country, []).append(idx)

    languages = []
    for code, by_country in by_lang.items():
        cs = []
        for cc, idxs in by_country.items():
            idxs.sort(key=lambda i: (out_channels[i]["geo"], out_channels[i]["n"].lower()))
            info = countries.get(cc) or {}
            cs.append({"code": cc, "name": info.get("name", cc or "International"),
                       "flag": info.get("flag", "🌐"), "ch": idxs})
        cs.sort(key=lambda x: x["name"])
        languages.append({
            "code": code,
            "name": lang_names.get(code, "Other" if code == "und" else code),
            "count": sum(len(x["ch"]) for x in cs),
            "countries": cs,
        })
    languages.sort(key=lambda x: x["name"])

    return {
        "updated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"),
        "source": "iptv-org (https://github.com/iptv-org/iptv)",
        "channels": out_channels,
        "languages": languages,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "catalog", "channels.json"))
    a = ap.parse_args()
    cat = build(a.data)
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(cat, f, ensure_ascii=False, separators=(",", ":"))
    print("channels:", len(cat["channels"]), "languages:", len(cat["languages"]))


if __name__ == "__main__":
    main()

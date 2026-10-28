#!/usr/bin/env python3
"""Builds catalog/vod.json: free movies, TV shows, documentaries and cartoons for Lazhar TV Cast.

Source: the Internet Archive (archive.org) curated collections of public-domain and freely
licensed films (Feature Films, Classic TV, Prelinger Archives, Classic Cartoons, ...).
Only these curated collections are used, so everything listed is free and legal to watch.
"""
import concurrent.futures
import datetime
import html
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

UA = {"User-Agent": "LazharTVCast-catalog/1.0 (+https://github.com/lazharmeguedmi/TVCast)"}
FREE = "collection:(feature_films OR classic_tv OR classic_cartoons OR prelinger OR film_noir OR silent_films OR SciFi_Horror OR comedy_films OR documentaryandshort OR ephemera OR short_films)"
ROWS = 80

SECTIONS = [
    ("movies", "Movies", [
        ("Most watched", "collection:feature_films"),
        ("Comedy", "collection:(feature_films OR comedy_films) AND subject:(comedy)"),
        ("Drama & romance", "collection:feature_films AND subject:(drama OR romance)"),
        ("Action & adventure", "collection:feature_films AND subject:(action OR adventure OR war)"),
        ("Horror & sci-fi", "collection:(feature_films OR SciFi_Horror) AND subject:(horror OR \"science fiction\" OR sci-fi OR monster)"),
        ("Crime & film noir", "collection:(feature_films OR film_noir) AND subject:(noir OR crime OR mystery OR detective)"),
        ("Westerns", "collection:feature_films AND subject:(western OR westerns)"),
        ("Silent classics", "collection:(silent_films OR feature_films) AND subject:(silent)"),
    ]),
    ("shows", "TV Shows", [
        ("Classic TV", "collection:classic_tv"),
        ("Sitcoms & comedy", "collection:classic_tv AND subject:(comedy OR sitcom)"),
        ("Mystery & detective", "collection:classic_tv AND subject:(mystery OR detective OR crime)"),
        ("Westerns & adventure", "collection:classic_tv AND subject:(western OR adventure)"),
        ("Sci-fi & fantasy", "collection:classic_tv AND subject:(\"science fiction\" OR sci-fi OR fantasy)"),
    ]),
    ("docs", "Documentaries", [
        ("Popular documentaries", FREE + " AND subject:(documentary OR documentaries)"),
        ("History & war", FREE + " AND subject:(history OR \"world war\" OR war) AND subject:(documentary OR newsreel OR history)"),
        ("Science & nature", FREE + " AND subject:(science OR nature OR space OR animals)"),
        ("Vintage America (Prelinger)", "collection:prelinger"),
    ]),
    ("kids", "Cartoons", [
        ("Classic cartoons", "collection:classic_cartoons"),
        ("Animated films", "collection:(feature_films OR classic_cartoons) AND subject:(animation OR animated OR cartoon)"),
    ]),
]

ADULT = re.compile(r"\b(sex\w*|nude|nudes|nudity|nudist\w*|naked|erotic\w*|porn\w*|xxx|strip|stripper\w*|striptease|burlesque|"
                   r"adult|adults only|exploitation|sexploitation|stag|pin-?up|lingerie|vice|marihuana|marijuana|"
                   r"reefer|drug|cocaine)\b", re.I)

PREFERRED_FORMATS = ["h.264", "h.264 HD", "MPEG4", "h.264 IA", "512Kb MPEG4"]


def get_json(url, tries=3):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # noqa: BLE001
            if i == tries - 1:
                print("WARN", url[:120], e, file=sys.stderr)
                return None
            time.sleep(2 * (i + 1))


def search(q):
    params = [("q", q + " AND mediatype:movies"), ("rows", str(ROWS)), ("page", "1"), ("output", "json"),
              ("sort[]", "downloads desc")]
    for f in ["identifier", "title", "year", "downloads"]:
        params.append(("fl[]", f))
    d = get_json("https://archive.org/advancedsearch.php?" + urllib.parse.urlencode(params))
    if not d:
        return []
    return [x["identifier"] for x in d.get("response", {}).get("docs", []) if x.get("identifier")]


def seconds(v):
    if v is None:
        return 0
    v = str(v).strip()
    try:
        if ":" in v:
            t = 0.0
            for p in v.split(":"):
                t = t * 60 + float(p)
            return int(t)
        return int(float(v))
    except ValueError:
        return 0


def clean(text, n):
    if isinstance(text, list):
        text = " ".join(str(t) for t in text)
    text = html.unescape(re.sub(r"<[^>]+>", " ", str(text or "")))
    text = re.sub(r"\s+", " ", text).strip()
    return text[:n].rsplit(" ", 1)[0] + "…" if len(text) > n else text


def details(ident):
    d = get_json("https://archive.org/metadata/" + urllib.parse.quote(ident))
    if not d or "files" not in d:
        return None
    md = d.get("metadata", {})
    subj = md.get("subject") or ""
    if isinstance(subj, list):
        subj = " ".join(str(x) for x in subj)
    title_text = md.get("title") if not isinstance(md.get("title"), list) else " ".join(md.get("title"))
    if ADULT.search(str(title_text or "")) or ADULT.search(str(subj)) or str(md.get("noindex", "")).lower() == "true":
        return None  # keep the list family-friendly
    best = None
    for rank, fmt in enumerate(PREFERRED_FORMATS):
        for f in d["files"]:
            name = f.get("name", "")
            if f.get("format") == fmt and name.lower().endswith(".mp4"):
                size = int(f.get("size") or 0)
                if size and size > 6_000_000_000:
                    continue
                cand = (rank, -seconds(f.get("length")), name, seconds(f.get("length")))
                if best is None or cand < best:
                    best = cand
        if best:
            break
    if not best:
        return None
    title = md.get("title")
    if isinstance(title, list):
        title = title[0]
    year = str(md.get("year") or md.get("date") or "")[:4]
    if not title:
        return None
    return {
        "id": ident,
        "t": clean(title, 90),
        "y": year if year.isdigit() else "",
        "d": clean(md.get("description"), 320),
        "f": best[2],
        "s": best[3],
    }


def main():
    out = os.path.join(os.path.dirname(__file__), "..", "catalog", "vod.json")
    rows = []
    ids = []
    for sid, sname, row_defs in SECTIONS:
        for rname, q in row_defs:
            found = search(q)
            print(f"{sname} / {rname}: {len(found)}")
            rows.append((sid, rname, found))
            ids.extend(found)
    unique = list(dict.fromkeys(ids))
    info = {}
    with concurrent.futures.ThreadPoolExecutor(12) as ex:
        for ident, d in zip(unique, ex.map(details, unique)):
            if d:
                info[ident] = d
    items, index = [], {}
    for ident in unique:
        if ident in info:
            index[ident] = len(items)
            items.append(info[ident])
    sections = []
    for sid, sname, _ in SECTIONS:
        srows = [{"name": rn, "items": [index[i] for i in f if i in index]} for (s, rn, f) in rows if s == sid]
        srows = [r for r in srows if r["items"]]
        sections.append({"id": sid, "name": sname, "rows": srows})
    cat = {
        "updated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"),
        "source": "Internet Archive (archive.org) public-domain collections",
        "sections": sections,
        "items": items,
    }
    if len(items) < 50:
        print(f"::error::Only {len(items)} titles found; keeping the previous list")
        sys.exit(1)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(cat, f, ensure_ascii=False, separators=(",", ":"))
    summary = ", ".join(f"{s['name']} {sum(len(r['items']) for r in s['rows'])}" for s in sections)
    print(f"::notice::VOD titles: {len(items)} ({summary})")


if __name__ == "__main__":
    main()

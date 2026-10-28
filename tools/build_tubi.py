#!/usr/bin/env python3
"""Builds catalog/tubi.json: what's free on Tubi right now, from Tubi's public category pages
(allowed by tubitv.com/robots.txt). The TV app shows these and opens the Tubi app to play them
(with Tubi's ads) - nothing is streamed outside Tubi."""
import datetime
import html
import json
import os
import re
import sys
import time
import urllib.request

UA = {"User-Agent": "Mozilla/5.0 (compatible; LazharTVCast/1.0; +https://github.com/lazharmeguedmi/TVCast)"}
BASE = "https://tubitv.com"

# (row name, pattern matched against category URLs from Tubi's sitemap)
WANTED = [
    ("Superheroes", r"superhero"),
    ("Most popular", r"most[_-]popular|popular|trending|top[_-]?picks"),
    ("New releases", r"new[_-]releases|recently[_-]added|just[_-]added|new[_-]on"),
    ("Action", r"/action$|/action[_-]movies"),
    ("Comedy", r"/comedy$|/comedies|/comedy[_-]movies"),
    ("Drama", r"/drama$|/dramas"),
    ("Thrillers", r"thriller"),
    ("Horror", r"/horror$|/horror[_-]movies"),
    ("Sci-fi & fantasy", r"sci[_-]?fi|science[_-]fiction|fantasy"),
    ("Family & kids", r"/family$|family[_-]movies|/kids$|kids[_-]and[_-]family"),
    ("Romance", r"romance"),
    ("Documentaries", r"documentar"),
    ("Anime", r"/anime$"),
    ("Arabic", r"arabic|middle[_-]east"),
    ("Español", r"/espanol$|spanish|latino"),
]
ADULT = re.compile(r"\b(erotic\w*|sex\w*|nude|porn\w*|xxx|adult)\b", re.I)


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def locs(xml):
    return re.findall(r"<loc>\s*([^<\s]+)\s*</loc>", xml)


def walk(o, found):
    """Collect {url,name,image} dicts from any nested JSON."""
    if isinstance(o, dict):
        url = o.get("url") or o.get("@id") or ""
        name = o.get("name") or o.get("title")
        if isinstance(url, str) and re.search(r"/(movies|series)/\d+", url) and isinstance(name, str):
            img = o.get("image") or o.get("thumbnailUrl")
            if isinstance(img, list):
                img = img[0] if img else None
            if isinstance(img, dict):
                img = img.get("url") or img.get("contentUrl")
            found.append({"url": url, "name": name, "image": img if isinstance(img, str) else None})
        for v in o.values():
            walk(v, found)
    elif isinstance(o, list):
        for v in o:
            walk(v, found)


def parse_category(page):
    items = []
    for block in re.findall(r'<script[^>]+application/ld\+json[^>]*>(.*?)</script>', page, re.S):
        try:
            walk(json.loads(block), items)
        except Exception:  # noqa: BLE001
            pass
    # links on the page (also catches items not in JSON-LD)
    for path, kind, cid, slug in re.findall(r'href="(/(movies|series)/(\d+)/([^"?#]+))"', page):
        items.append({"url": BASE + path, "name": None, "image": None, "slug": slug})
    # de-duplicate by id, keep the best data
    out = {}
    for it in items:
        m = re.search(r"/(movies|series)/(\d+)(?:/([^/?#]+))?", it["url"])
        if not m:
            continue
        key = m.group(2)
        cur = out.setdefault(key, {"kind": m.group(1), "id": key, "slug": m.group(3) or it.get("slug") or "",
                                   "name": None, "image": None})
        if it.get("name") and not cur["name"]:
            cur["name"] = html.unescape(it["name"])
        if it.get("image") and not cur["image"]:
            cur["image"] = it["image"]
    return list(out.values())


def og(url):
    try:
        p = get(url)
    except Exception:  # noqa: BLE001
        return None, None, None
    t = re.search(r'<meta[^>]+property="og:title"[^>]+content="([^"]*)"', p)
    i = re.search(r'<meta[^>]+property="og:image"[^>]+content="([^"]*)"', p)
    d = re.search(r'<meta[^>]+(?:property="og:description"|name="description")[^>]+content="([^"]*)"', p)
    title = html.unescape(t.group(1)) if t else None
    if title:
        title = re.sub(r"^\s*Watch\s+", "", title)
        title = re.sub(r"\s*[-|–]\s*(Free Movies|Free TV|Tubi).*$", "", title, flags=re.I)
        title = re.sub(r"\s*\((\d{4})\)\s*$", r" (\1)", title)
    return title, (i.group(1) if i else None), (html.unescape(d.group(1)) if d else None)


def main():
    cats = []
    try:
        for sm in locs(get(BASE + "/sitemaps/category-pages.xml")):
            cats.append(sm.rstrip("/"))
    except Exception as e:  # noqa: BLE001
        print("WARN category sitemap:", e)
    cats = list(dict.fromkeys(cats + [BASE + "/category/superheroes"]))
    print(f"{len(cats)} category pages in sitemap")

    rows, items, index, log = [], [], {}, []
    for name, pat in WANTED:
        url = next((c for c in cats if re.search(pat, c.lower())), None)
        if not url:
            log.append(f"{name}: no category")
            continue
        try:
            entries = parse_category(get(url))
        except Exception as e:  # noqa: BLE001
            log.append(f"{name}: {e}")
            continue
        ids = []
        for e in entries[:60]:
            if e["id"] not in index:
                path = f"/{e['kind']}/{e['id']}/{e['slug']}".rstrip("/")
                index[e["id"]] = len(items)
                items.append({"id": e["id"], "k": "movie" if e["kind"] == "movies" else "series",
                              "u": BASE + path, "t": e["name"], "i": e["image"], "d": None})
            ids.append(index[e["id"]])
        rows.append({"name": name, "items": ids})
        log.append(f"{name}: {len(ids)} from {url.replace(BASE, '')}")
        time.sleep(0.5)

    # fill in missing titles / posters from each title's page (polite pace)
    fetched = 0
    for it in items:
        if it["t"] and it["i"]:
            continue
        t, i, d = og(it["u"])
        fetched += 1
        it["t"] = it["t"] or t or it["u"].rsplit("/", 1)[-1].replace("-", " ").title()
        it["i"] = it["i"] or i
        it["d"] = d
        time.sleep(0.25)

    keep = [i for i, it in enumerate(items) if it["t"] and not ADULT.search(it["t"])]
    remap = {old: new for new, old in enumerate(keep)}
    items = [items[i] for i in keep]
    for r in rows:
        r["items"] = [remap[i] for i in r["items"] if i in remap]
    rows = [r for r in rows if r["items"]]

    print("\n".join(log))
    if len(items) < 20:
        print(f"::error::Only {len(items)} Tubi titles found; keeping previous list%0A" + "%0A".join(log))
        sys.exit(1)
    out = os.path.join(os.path.dirname(__file__), "..", "catalog", "tubi.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"updated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"),
                   "source": "tubitv.com public category pages", "rows": rows, "items": items},
                  f, ensure_ascii=False, separators=(",", ":"))
    sample = [items[i]["t"] for i in rows[0]["items"][:8]]
    print(f"::notice::TUBI titles: {len(items)} (pages fetched {fetched})%0A" + "%0A".join(log)
          + "%0Afirst row: " + " | ".join(sample)
          + "%0Aposters: " + str(sum(1 for i in items if i['i'])))


if __name__ == "__main__":
    main()

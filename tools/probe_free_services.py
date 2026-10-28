#!/usr/bin/env python3
"""One-off check: what do Tubi's and Pluto TV's public websites allow (robots.txt) and expose (sitemaps,
page metadata)? Results are printed as a GitHub annotation."""
import gzip
import re
import urllib.request

UA = {"User-Agent": "Mozilla/5.0 (compatible; LazharTVCast-check/1.0)"}
out = []


def get(url, limit=400000):
    try:
        req = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(req, timeout=30) as r:
            data = r.read(limit)
            if url.endswith(".gz") or data[:2] == b"\x1f\x8b":
                data = gzip.decompress(data)
            return r.status, data.decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return getattr(e, "code", 0), str(e)


def log(s):
    out.append(s)


for site in ["https://tubitv.com", "https://pluto.tv"]:
    log(f"===== {site}")
    code, robots = get(site + "/robots.txt")
    log(f"robots.txt {code}:")
    for line in robots.splitlines()[:40]:
        if line.strip():
            log("  " + line.strip()[:150])
    sitemaps = re.findall(r"(?im)^sitemap:\s*(\S+)", robots)
    for sm in sitemaps[:3]:
        c, body = get(sm)
        locs = re.findall(r"<loc>\s*([^<\s]+)\s*</loc>", body)
        log(f"sitemap {sm} -> {c}, {len(locs)} entries")
        for l in locs[:8]:
            log("    " + l[:160])
        # descend one level into a movie-ish child sitemap
        child = [l for l in locs if re.search(r"movie|video|vod|title|content", l, re.I)][:2]
        for ch in child:
            c2, b2 = get(ch)
            l2 = re.findall(r"<loc>\s*([^<\s]+)\s*</loc>", b2)
            log(f"  child {ch} -> {c2}, {len(l2)} entries")
            for l in l2[:5]:
                log("      " + l[:160])

for page in ["https://tubitv.com/movies/100035476/the-dark-knight", "https://tubitv.com/category/superheroes",
             "https://tubitv.com/category/action", "https://pluto.tv/us/on-demand/movies"]:
    c, b = get(page, 800000)
    title = re.search(r'<meta[^>]+property="og:title"[^>]+content="([^"]*)"', b)
    img = re.search(r'<meta[^>]+property="og:image"[^>]+content="([^"]*)"', b)
    ld = len(re.findall(r'application/ld\+json', b))
    links = set(re.findall(r'href="(/(?:movies|series|on-demand/movies)/[^"]+)"', b))
    log(f"page {page} -> {c}, og:title={title.group(1)[:60] if title else None}, "
        f"og:image={'yes' if img else 'no'}, ld+json={ld}, title links={len(links)}")
    for l in list(links)[:5]:
        log("    " + l[:120])

msg = "%0A".join(s.replace("%", "%25") for s in out)
print("::notice::FREE SERVICES PROBE%0A" + msg[:60000])
print("\n".join(out))

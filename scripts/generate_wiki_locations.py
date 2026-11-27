#!/usr/bin/env python3
"""
Generates src/main/resources/com/osrsai/wiki-locations.tsv from the Old School RuneScape Wiki.

Every page in the wiki's `infobox_location` bucket is read. From its Infobox Location the
script takes the type, the parent location and league region, and the map:

  * polygons (mtype=polygon), squares (r=) and rectangles (rectX/rectY) become exact areas;
  * a bare centre point becomes a circle whose radius comes from the map's zoom and size;
  * a dungeon's surface map is its entrance, stored as "<name> entrance" with a small radius,
    and pins on the page that lie underground are used for the dungeon itself;
  * monster spawns ({{LocLine}} on monster and NPC pages) give an approximate outline for a
    location with no map (many big dungeons only have a map image), for the underground part of
    a place whose map only shows its entrance, and for places that have no location page.

Run it again whenever new areas are released:

    python scripts/generate_wiki_locations.py

Wiki content is licensed CC BY-NC-SA 3.0; the generated file carries that notice.
"""
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request

API = "https://oldschool.runescape.wiki/api.php"
USER_AGENT = "OSRS AI Assistant RuneLite plugin location data generator (https://github.com/Timboy67678/osrs-ai-assistant)"
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "com", "osrsai", "wiki-locations.tsv")

# Y coordinates at or above this are underground or other non-surface map areas.
UNDERGROUND_Y = 4200
# Location types that are big bodies of water; only used when nothing on land matches.
WATER_TYPES = {"sea", "ocean", "river", "lake", "bay", "strait"}
# Names of untyped pages that are water.
WATER_NAME = re.compile(r"^(River|Lake|Sea of)\b|\b(River|Lake|Bay|Sea|Ocean|Strait|Channel|Estuary)$", re.I)
# Long, thin features: a circle round the centre covers far too much, so draw it smaller.
LINEAR_NAME = re.compile(r"\b(River|Wall|Mountains?|Mountain range|Range|Cliffs?|Coast|Road|Path|Pass)$|^River\b", re.I)
# Small location types; caps the circle drawn around a centre point.
SMALL_TYPE_RADIUS = {"building": 15, "bank": 8, "shop": 6, "windmill": 6, "mine": 14, "altar": 8,
                     "agility course": 30, "guild": 25}
DEFAULT_RADIUS = 30
# Radius used for a centre point with no zoom hint, by location type.
TYPE_DEFAULT_RADIUS = {"dungeon": 64, "settlement": 45, "island": 40}
# Types too large or irregular to outline from monster spawns.
NO_SPAWN_OUTLINE_TYPES = {"region", "realm", "continent", "kingdom", "province", "plane"} | WATER_TYPES
SPAWN_OUTLINE_PADDING = 4
SPAWN_OUTLINE_MAX_SPAN = 400
# Spawns further than this from the group's median are treated as mistakes or other floors.
SPAWN_OUTLIER_TILES = 150
MAX_RADIUS = 160
ENTRANCE_RADIUS = 6
# A page that is the parent of this many location pages is a region; a circle round its centre
# says nothing useful, so only its outline (if any) is kept and its children carry it as context.
REGION_CHILDREN = 15


def api(params):
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except Exception:
            if attempt == 3:
                raise
            time.sleep(2 + attempt * 3)


def location_page_names():
    names, offset = set(), 0
    while True:
        query = "bucket('infobox_location').select('page_name').limit(500).offset(%d).run()" % offset
        rows = api({"action": "bucket", "format": "json", "query": query}).get("bucket", [])
        names.update(r["page_name"] for r in rows)
        if len(rows) < 500:
            return sorted(names)
        offset += 500
        time.sleep(1)


def fetch_wikitext(names):
    pages = {}
    for i in range(0, len(names), 50):
        batch = names[i:i + 50]
        data = api({"action": "query", "format": "json", "formatversion": 2, "prop": "revisions",
                    "rvprop": "content", "rvslots": "main", "titles": "|".join(batch)})
        for p in data["query"]["pages"]:
            if "revisions" in p:
                pages[p["title"]] = p["revisions"][0]["slots"]["main"]["content"]
        time.sleep(1)
    return pages


def locline_pages():
    """Wikitext of every article that uses {{LocLine}} (monster and NPC spawn lists)."""
    titles, params = [], {"action": "query", "format": "json", "list": "embeddedin",
                          "eititle": "Template:LocLine", "einamespace": 0, "eilimit": 500}
    while True:
        data = api(params)
        titles += [p["title"] for p in data["query"]["embeddedin"]]
        if "continue" not in data:
            break
        params.update(data["continue"])
        time.sleep(1)
    print("pages with spawn lists:", len(titles))
    return fetch_wikitext(sorted(set(titles)))


def spawn_points(pages):
    """Map of location page title -> spawn coordinates, from every {{LocLine}} on the pages."""
    spawns = {}
    for text in pages.values():
        for m in re.finditer(r"\{\{\s*LocLine([\s\S]*?)\}\}", text):
            body = m.group(1)
            m_loc = re.search(r"\|\s*location\s*=\s*(\[\[[^\]]+\]\]|[^|]*)", body)
            loc = link_target(m_loc.group(1)) if m_loc else None
            if not loc:
                continue
            key = loc[:1].upper() + loc[1:].replace("_", " ")
            pts = [(int(x), int(y)) for x, y in re.findall(r"x\s*:\s*(-?\d+)\s*,\s*y\s*:\s*(-?\d+)", body)]
            spawns.setdefault(key, []).extend(pts)
    return spawns


def resolve_redirects(titles):
    """Map of title -> redirect target, for those titles that are redirects."""
    targets, titles = {}, sorted(titles)
    for i in range(0, len(titles), 50):
        data = api({"action": "query", "format": "json", "formatversion": 2, "redirects": 1,
                    "titles": "|".join(titles[i:i + 50])})
        for r in data["query"].get("redirects", []):
            targets[r["from"]] = r["to"]
        for n in data["query"].get("normalized", []):
            if n["to"] in targets:
                targets[n["from"]] = targets[n["to"]]
        time.sleep(1)
    return targets


def merge_redirected_spawns(spawns, redirects):
    """Move spawns listed under a redirect onto the page it points to."""
    merged = {}
    for place, points in spawns.items():
        merged.setdefault(redirects.get(place, place), []).extend(points)
    return merged


def spawn_outlines(points):
    """Outlines for surface and underground spawns separately, without stray outliers."""
    out = []
    for group in ([p for p in points if p[1] < UNDERGROUND_Y], [p for p in points if p[1] >= UNDERGROUND_Y]):
        if len(group) < 3:
            continue
        mx = sorted(x for x, _ in group)[len(group) // 2]
        my = sorted(y for _, y in group)[len(group) // 2]
        kept = [(x, y) for x, y in group if max(abs(x - mx), abs(y - my)) <= SPAWN_OUTLIER_TILES]
        outline = spawn_outline(kept)
        if outline:
            out.append(outline)
    return out


def spawn_outline(points):
    """Convex hull around spawn points, padded outward; None if too spread out to be one place."""
    pts = sorted(set(points))
    if len(pts) < 3:
        return None
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    if max(xs) - min(xs) > SPAWN_OUTLINE_MAX_SPAN or max(ys) - min(ys) > SPAWN_OUTLINE_MAX_SPAN:
        return None

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    hull = lower[:-1] + upper[:-1]
    if len(hull) < 3:
        return None
    cx, cy = sum(x for x, _ in hull) / len(hull), sum(y for _, y in hull) / len(hull)
    padded = []
    for x, y in hull:
        dx, dy = x - cx, y - cy
        d = math.hypot(dx, dy) or 1
        padded.append((round(x + dx / d * SPAWN_OUTLINE_PADDING), round(y + dy / d * SPAWN_OUTLINE_PADDING)))
    return padded


def template_body(text, name_pattern):
    """Body of the first {{<name> ...}} template, with nested templates kept intact."""
    m = re.search(r"\{\{\s*" + name_pattern, text, re.I)
    if not m:
        return None
    i, depth = m.start(), 0
    while i < len(text):
        if text.startswith("{{", i):
            depth += 1
            i += 2
        elif text.startswith("}}", i):
            depth -= 1
            i += 2
            if depth == 0:
                return text[m.end():i - 2]
        else:
            i += 1
    return None


def split_params(body):
    parts, depth, cur, i = [], 0, "", 0
    while i < len(body):
        two = body[i:i + 2]
        if two in ("{{", "[["):
            depth += 1
            cur += two
            i += 2
        elif two in ("}}", "]]"):
            depth -= 1
            cur += two
            i += 2
        elif body[i] == "|" and depth == 0:
            parts.append(cur)
            cur = ""
            i += 1
        else:
            cur += body[i]
            i += 1
    parts.append(cur)
    params = {}
    for p in parts:
        if "=" in p:
            k, v = p.split("=", 1)
            params[k.strip().lower()] = v.strip()
    return params


COORD = re.compile(r"^(-?\d+)\s*[,:]\s*(-?\d+)(?:\s*[,:]\s*-?\d+)?$")


def parse_maps(text):
    """Each {{Map}} in text as dict(kind, points, plane, zoom, width, height, r, rectx, recty, caption)."""
    maps = []
    for m in re.finditer(r"\{\{\s*Map\s*\|(.*?)\}\}", text, re.S):
        kv, pts = {}, []
        for arg in m.group(1).split("|"):
            arg = arg.strip()
            c = COORD.match(arg)
            if c:
                pts.append((int(c.group(1)), int(c.group(2))))
            elif "=" in arg:
                k, v = arg.split("=", 1)
                kv[k.strip().lower()] = v.strip()
        if not pts and "x" in kv and "y" in kv:
            try:
                pts = [(int(kv["x"]), int(kv["y"]))]
            except ValueError:
                pass
        if not pts:
            continue

        def num(key, default=None):
            try:
                return int(kv[key])
            except (KeyError, ValueError):
                return default

        maps.append({"mtype": kv.get("mtype", "").lower(), "points": pts, "plane": num("plane", 0),
                     "zoom": num("zoom"), "width": num("width"), "height": num("height"),
                     "r": num("r"), "rectx": num("rectx"), "recty": num("recty"),
                     "caption": (kv.get("caption", "") + " " + kv.get("desc", "")).lower(),
                     "group": kv.get("group"), "type": kv.get("type", "").lower()})
    return maps


def shapes_from_map(mp, loc_type, name=""):
    """Convert one parsed map into ('poly', [(x,y)...]) or ('disk', (x, y, r)) shapes."""
    pts, mtype = mp["points"], mp["mtype"]
    if mtype in ("polygon", "focusarea") or (len(pts) >= 3 and mtype not in ("pin",)):
        return [("poly", pts)] if len(pts) >= 3 else []
    out = []
    for (x, y) in pts:
        if mtype == "square" and mp["r"]:
            r = mp["r"]
            out.append(("poly", [(x - r, y - r), (x + r, y - r), (x + r, y + r), (x - r, y + r)]))
        elif mtype == "rectangle" and mp["rectx"] and mp["recty"]:
            hx, hy = mp["rectx"] / 2.0, mp["recty"] / 2.0
            out.append(("poly", [(round(x - hx), round(y - hy)), (round(x + hx), round(y - hy)),
                                 (round(x + hx), round(y + hy)), (round(x - hx), round(y + hy))]))
        else:
            out.append(("disk", (x, y, radius_for(mp, loc_type, name))))
    return out


def radius_for(mp, loc_type, name=""):
    """Rough extent of a place from how the wiki framed it: visible tiles at the map's zoom."""
    zoom = mp["zoom"] if mp["zoom"] is not None else 2
    size = min(mp["width"] or 300, mp["height"] or 300)
    px_per_tile = 2 ** max(zoom, 0)
    default = TYPE_DEFAULT_RADIUS.get(loc_type, DEFAULT_RADIUS)
    r = int(size / px_per_tile / 2.5) if mp["zoom"] is not None else default
    r = max(6, min(r, MAX_RADIUS))
    if loc_type in WATER_TYPES or LINEAR_NAME.search(name):
        r = max(6, int(r * 0.4))
    elif loc_type == "island":
        # A circle around an island spills onto nearby coasts; keep it tighter.
        r = max(6, int(r * 0.65))
    cap = SMALL_TYPE_RADIUS.get(loc_type)
    if cap:
        # The zoom shows how big the wiki thinks the place is, so allow more room when given.
        r = min(r, cap * 3 if mp["zoom"] is not None else cap)
    return r


def link_target(value):
    m = re.search(r"\[\[([^\]|#]+)", value or "")
    return m.group(1).strip() if m else None


def clean(value):
    value = re.sub(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]", r"\1", value or "")
    value = re.sub(r"<[^>]+>|\{\{[^}]*\}\}|'''?", "", value)
    return value.replace("\t", " ").replace("\n", " ").strip()


def display_name(title):
    return re.sub(r"\s*\((?:location|building|area|dungeon|mine|island|town|city)\)$", "", title, flags=re.I)


def build(pages, spawns):
    entries, parents, regions = [], {}, {}
    for title, text in pages.items():
        if re.match(r"\s*#REDIRECT", text, re.I):
            continue
        body = template_body(text, r"Infobox[ _]Location")
        if body is None:
            continue
        p = split_params(body)
        loc_type = clean(p.get("type", "")).lower()
        parents[title] = link_target(p.get("location"))
        regions[title] = clean(p.get("leagueregion", "")).split(",")[0].strip()
        name = display_name(title)

        infobox_maps = []
        for key, value in p.items():
            if re.fullmatch(r"map\d*", key):
                infobox_maps += parse_maps(value)

        shapes, entrance = [], []
        for mp in infobox_maps:
            surface_pin = all(y < UNDERGROUND_Y for _, y in mp["points"])
            if loc_type == "dungeon" and surface_pin:
                entrance += [("disk", (x, y, ENTRANCE_RADIUS)) for x, y in mp["points"]]
            else:
                shapes += shapes_from_map(mp, loc_type, name)

        if not shapes:
            # No usable infobox map (often a map image): use underground map links and pins on
            # the page, which can only be inside the place itself.
            for mp in parse_maps(text):
                inside = [(x, y) for x, y in mp["points"] if y >= UNDERGROUND_Y]
                if inside:
                    mp = dict(mp, points=inside)
                    shapes += shapes_from_map(mp, loc_type, name)

        kind = "water" if loc_type in WATER_TYPES or (not loc_type and WATER_NAME.search(name)) else "land"
        plane = mp_plane(infobox_maps)
        for shape in shapes:
            entries.append((title, name, loc_type, kind, shape, plane))
        for shape in entrance:
            entries.append((title, name + " entrance", "dungeon entrance", "land", shape, 0))

        # Monster spawns fill in a layer the maps don't cover: a page with no map at all, or one
        # whose map only shows the surface entrance of somewhere underground (or the reverse).
        if loc_type not in NO_SPAWN_OUTLINE_TYPES:
            covered = {shape_layer(shape) for shape in shapes}
            for outline in spawn_outlines(spawns.get(title, [])):
                if shape_layer(("poly", outline)) not in covered:
                    entries.append((title, name, loc_type, kind + "~", ("poly", outline), plane))

    # Places named in spawn lists that have no location page (boss lairs, minigame areas).
    known = {t for t in pages}
    for place, points in spawns.items():
        if place in known or ":" in place:
            continue
        for outline in spawn_outlines(points):
            entries.append((place, display_name(place), "", "land~", ("poly", outline), 0))
    return entries, parents, regions


def shape_layer(shape):
    """'underground' or 'surface', from where the shape's first point lies."""
    kind, geom = shape
    y = geom[1] if kind == "disk" else geom[0][1]
    return "underground" if y >= UNDERGROUND_Y else "surface"


def mp_plane(maps):
    return maps[0]["plane"] if maps else 0


def chain(title, parents, limit=4):
    out, seen = [], {title}
    cur = parents.get(title)
    while cur and cur not in seen and len(out) < limit:
        seen.add(cur)
        out.append(display_name(cur))
        cur = parents.get(cur)
    return out


def write(entries, parents, regions, out_path):
    lines = [
        "# Generated by scripts/generate_wiki_locations.py from the Old School RuneScape Wiki",
        "# (https://oldschool.runescape.wiki), Infobox Location pages. Do not edit by hand.",
        "# Wiki content is licensed CC BY-NC-SA 3.0 (https://creativecommons.org/licenses/by-nc-sa/3.0/);",
        "# this derived data is shared under the same licence.",
        "# Columns: name, wiki page, type, land|water (~ = outline estimated from monster spawns),",
        "#          parents (|-separated), Leagues region, plane,",
        "#          shape (P = polygon x,y;x,y;... | C = circle x,y,r)",
    ]
    children = {}
    for child, parent in parents.items():
        if parent:
            children[parent] = children.get(parent, 0) + 1
    rows = []
    for title, name, loc_type, kind, (shape, geom), plane in entries:
        if shape == "disk" and children.get(title, 0) >= REGION_CHILDREN:
            continue
        if shape == "poly":
            g = "P\t" + ";".join("%d,%d" % pt for pt in geom)
        else:
            g = "C\t%d,%d,%d" % geom
        rows.append("\t".join([name, title, loc_type, kind, "|".join(chain(title, parents)),
                               regions.get(title, ""), str(plane), g]))
    lines += sorted(set(rows))
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    return len(rows)


def main():
    # Optional cache directory, so the parser can be re-run without downloading again.
    cache = sys.argv[1] if len(sys.argv) > 1 else None

    def cached(name, fetch):
        path = os.path.join(cache, name) if cache else None
        if path and os.path.exists(path):
            return json.load(open(path, encoding="utf-8"))
        data = fetch()
        if path:
            os.makedirs(cache, exist_ok=True)
            json.dump(data, open(path, "w", encoding="utf-8"))
        return data

    pages = cached("location_pages.json", lambda: fetch_wikitext(location_page_names()))
    spawns = spawn_points(cached("locline_pages.json", locline_pages))
    unknown = [p for p in spawns if p not in pages]
    spawns = merge_redirected_spawns(spawns, cached("spawn_redirects.json", lambda: resolve_redirects(unknown)))
    entries, parents, regions = build(pages, spawns)
    count = write(entries, parents, regions, os.path.normpath(OUT))
    print("wrote", count, "shapes for", len({e[0] for e in entries}), "pages to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()

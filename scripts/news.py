"""Collects headlines from RSS/Atom feeds into news.json for the Doorway Terminal.

Runs on GitHub Actions every 30 minutes. Edit FEEDS to change sources.
The "topic" must match a topic id in the dashboard's CONFIG.topics.
"""
import datetime
import email.utils
import html
import json
import re
import urllib.request
import xml.etree.ElementTree as ET

FEEDS = [
    ("uk",       "BBC NEWS",    "https://feeds.bbci.co.uk/news/uk/rss.xml"),
    ("local",    "BBC NORFOLK", "https://feeds.bbci.co.uk/news/england/norfolk/rss.xml"),
    ("world",    "BBC NEWS",    "https://feeds.bbci.co.uk/news/world/rss.xml"),
    ("tech",     "BBC TECH",    "https://feeds.bbci.co.uk/news/technology/rss.xml"),
    ("tech",     "THE VERGE",   "https://www.theverge.com/rss/index.xml"),
    ("ai",       "THE VERGE",   "https://www.theverge.com/rss/ai-artificial-intelligence/index.xml"),
    ("ai",       "ARS TECHNICA", "https://arstechnica.com/ai/feed/"),
    ("gaming",   "EUROGAMER",   "https://www.eurogamer.net/feed"),
    ("pokemon",  "GOOGLE NEWS", "https://news.google.com/rss/search?q=%22pokemon+tcg%22+OR+%22pokemon+center%22+OR+%22pokemon+cards%22&hl=en-GB&gl=GB&ceid=GB:en"),
    ("science",  "BBC SCIENCE", "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml"),
    ("business", "BBC BUSINESS", "https://feeds.bbci.co.uk/news/business/rss.xml"),
    ("sport",    "BBC SPORT",   "https://feeds.bbci.co.uk/sport/rss.xml"),
]
PER_FEED = 6
MAX_ITEMS = 80
ATOM = "{http://www.w3.org/2005/Atom}"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (DoorwayTerminal news collector)"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read()


def clean(text):
    text = html.unescape(re.sub(r"<[^>]+>", "", text or ""))
    return re.sub(r"\s+", " ", text).strip()


def parse_date(text):
    text = (text or "").strip()
    if not text:
        return None
    try:
        d = email.utils.parsedate_to_datetime(text)
    except (TypeError, ValueError):
        try:
            d = datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))
        except ValueError:
            return None
    if d.tzinfo is None:
        d = d.replace(tzinfo=datetime.timezone.utc)
    return d.astimezone(datetime.timezone.utc)


def text_of(el):
    return "".join(el.itertext()) if el is not None else ""


def parse(data):
    root = ET.fromstring(data)
    out = []
    for item in root.iter("item"):  # RSS
        out.append((clean(item.findtext("title")), parse_date(item.findtext("pubDate")), (item.findtext("link") or "").strip()))
    for entry in root.iter(ATOM + "entry"):  # Atom
        link = entry.find(ATOM + "link")
        out.append((clean(text_of(entry.find(ATOM + "title"))),
                    parse_date(entry.findtext(ATOM + "published") or entry.findtext(ATOM + "updated")),
                    link.get("href", "") if link is not None else ""))
    return [o for o in out if o[0]]


def main():
    items, seen, ok = [], set(), 0
    for topic, source, url in FEEDS:
        try:
            entries = parse(fetch(url))
            entries.sort(key=lambda e: e[1] or datetime.datetime.min.replace(tzinfo=datetime.timezone.utc), reverse=True)
            added = 0
            for title, date, link in entries:
                key = title.lower()
                if key in seen:
                    continue
                seen.add(key)
                src = source
                if source == "GOOGLE NEWS" and " - " in title:  # Google News titles end with " - Publisher"
                    title, src = title.rsplit(" - ", 1)
                    src = src.upper()
                items.append({"title": title, "source": src, "topic": topic,
                              "published": date.isoformat().replace("+00:00", "Z") if date else None, "link": link})
                added += 1
                if added >= PER_FEED:
                    break
            ok += 1
            print(f"OK    {source:<13} {topic:<9} {added} items")
        except Exception as e:  # one bad feed shouldn't stop the rest
            print(f"FAIL  {source:<13} {topic:<9} {url} -> {e}")
    if not items:
        raise SystemExit("No feeds worked; leaving the old news.json in place.")
    items.sort(key=lambda i: i["published"] or "", reverse=True)
    out = {"updated": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"), "items": items[:MAX_ITEMS]}
    with open("news.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"{ok}/{len(FEEDS)} feeds worked, {len(out['items'])} headlines written")


if __name__ == "__main__":
    main()

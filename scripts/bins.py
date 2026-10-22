"""Turns a personal bin-collection calendar (iCal) into bins.json for the Doorway Terminal.

The calendar link is kept in the repository secret BIN_ICAL_URL, so the address it belongs to
is never published. Only dates and bin types end up in bins.json.
"""
import datetime
import json
import os
import re
import urllib.request

TYPES = [  # first match wins
    ("garden", r"garden|brown"),
    ("recycling", r"recycl|blue|green"),
    ("glass", r"glass"),
    ("food", r"food|caddy"),
    ("general", r"refuse|rubbish|general|residual|black|grey|waste"),
]


def classify(summary):
    s = summary.lower()
    for name, pattern in TYPES:
        if re.search(pattern, s):
            return name
    return "other"


def unfold(text):
    out = []
    for line in text.replace("\r\n", "\n").split("\n"):
        if line[:1] in (" ", "\t") and out:
            out[-1] += line[1:]
        else:
            out.append(line)
    return out


def parse(text):
    events, cur = [], None
    for line in unfold(text):
        if line == "BEGIN:VEVENT":
            cur = {}
        elif line == "END:VEVENT":
            if cur and cur.get("date"):
                events.append(cur)
            cur = None
        elif cur is not None and ":" in line:
            key, val = line.split(":", 1)
            name = key.split(";")[0].upper()
            if name == "DTSTART":
                digits = re.sub(r"[^0-9]", "", val)[:8]
                if len(digits) == 8:
                    cur["date"] = f"{digits[:4]}-{digits[4:6]}-{digits[6:8]}"
            elif name == "SUMMARY":
                cur["summary"] = val.replace("\\,", ",").strip()
    return events


def main():
    url = os.environ.get("BIN_ICAL_URL", "").strip()
    if not url:
        print("BIN_ICAL_URL secret not set yet; leaving bins.json alone.")
        return
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (DoorwayTerminal bins)"})
    with urllib.request.urlopen(req, timeout=30) as r:
        text = r.read().decode("utf-8", "replace")
    today = datetime.date.today()
    horizon = today + datetime.timedelta(days=120)
    by_date = {}
    for ev in parse(text):
        d = datetime.date.fromisoformat(ev["date"])
        if not (today - datetime.timedelta(days=1) <= d <= horizon):
            continue
        entry = by_date.setdefault(ev["date"], {"date": ev["date"], "types": [], "labels": []})
        kind = classify(ev.get("summary", ""))
        if kind not in entry["types"]:
            entry["types"].append(kind)
        entry["labels"].append(ev.get("summary", ""))
    collections = [by_date[k] for k in sorted(by_date)]
    if not collections:
        raise SystemExit("Calendar had no upcoming collections; leaving the old bins.json in place.")
    with open("bins.json", "w", encoding="utf-8") as f:
        json.dump({"updated": datetime.datetime.utcnow().isoformat() + "Z", "collections": collections}, f, indent=1)
    for c in collections[:6]:
        print(c["date"], "+".join(c["types"]), "|", " / ".join(c["labels"]))
    print(f"{len(collections)} collection days written")


if __name__ == "__main__":
    main()

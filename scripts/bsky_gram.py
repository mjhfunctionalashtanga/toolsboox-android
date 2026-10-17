#!/usr/bin/env python3
"""Drain the gram-share queue: each gram Michael shared from Ledger becomes ONE Bluesky post —
the card as an image embed, his words (plus the source link as a facet) as the text.

The sibling of bsky_reply.py, one degree simpler: no reply refs to resolve, no thread to hang
from — a shared gram is a top-level post. The app password never leaves this box; Ledger only
ever talks to WordPress (mu-plugin mjh-bsky-gram.php on michaeljoelhall.com), and this script is
the only thing that turns a queue row into a live record. Ack after posting, so a row can never
double-post; a row that fails stays pending and the next run retries it.

Runs DRY (print, no publish, no ack) whenever creds are absent, exactly as bsky_reply.py does.
"""
import json, sys, urllib.request

import bsky_poster as b
from bsky_reply import SECRET, link_facets, wp

BASE = "https://michaeljoelhall.com/wp-json/mjh/v1"
LIMIT = 10
MAX_GRAPHEMES = 300

DRY = ("--dry" in sys.argv) or not (b.HANDLE and b.APP_PW)


def compose_text(item):
    """The post's words: what Ledger sent, with the source link on its own line when it isn't
    already in the text. The mu-plugin enforced the 300-grapheme ceiling on the words alone, so
    appending the link can overrun — in that case the link is dropped from the TEXT (the card
    still shows, and the words were what he approved) rather than truncating what he wrote."""
    text = (item.get("text") or "").strip()
    link = (item.get("link") or "").strip()
    if link and link not in text:
        joined = (text + "\n\n" + link).strip() if text else link
        if len(joined) <= MAX_GRAPHEMES:
            return joined
    return text or link


def post_gram(item, jwt=None, did=None):
    text = compose_text(item)
    if not text and not item.get("image_url"):
        raise ValueError("empty share")
    facets = link_facets(text)

    if DRY:
        print("──────── DRY RUN · Bluesky GRAM ────────")
        print(text)
        print("────────")
        if item.get("image_url"):
            print(f"  image embed → {item['image_url']}")
        if facets:
            print(f"  link facets → {[f['features'][0]['uri'] for f in facets]}")
        return {"dry": True}

    record = {"$type": "app.bsky.feed.post", "text": text, "createdAt": b._now(), "langs": ["en"]}
    if facets:
        record["facets"] = facets
    if item.get("image_url"):
        # The gram IS the share — unlike a reply's optional handwriting, a missing card here
        # means the post says almost nothing. So the image is NOT best-effort: a fetch/upload
        # failure raises, the row stays pending, and the next run tries again.
        img, mime = b._fetch_image(item["image_url"])
        if not img:
            raise ValueError(f"card PNG unreachable: {item['image_url']}")
        record["embed"] = {"$type": "app.bsky.embed.images",
                          "images": [{"alt": (item.get("image_alt") or "A card from the Ledger")[:1000],
                                      "image": b.upload_blob(jwt, img, mime)}]}
    return b._create_record(jwt, did, record)


def main():
    jwt = did = None
    if not DRY:
        jwt, did = b.create_session()

    try:
        queue = wp(BASE, f"/bsky-gram-queue?limit={LIMIT}").get("items") or []
    except Exception as e:
        print(f"queue unreachable: {str(e)[:120]}", flush=True)
        return
    if not queue:
        return

    for item in queue:
        try:
            res = post_gram(item, jwt, did)
            if DRY:
                continue
            url = ""
            uri = res.get("uri") or ""
            if uri.startswith("at://"):
                # at://did/app.bsky.feed.post/rkey → the human URL, for the ack's record.
                parts = uri.split("/")
                if len(parts) >= 5:
                    url = f"https://bsky.app/profile/{parts[2]}/post/{parts[4]}"
            wp(BASE, "/bsky-gram-ack", {"id": item["id"], "uri": uri, "url": url})
            print(f"posted gram {item['id']} -> {url or uri}", flush=True)
        except Exception as e:
            print(f"gram {item['id']} failed (stays pending): {str(e)[:160]}", flush=True)


if __name__ == "__main__":
    main()

# Ledger + WordPress: the bridge, FluentCRM, and webhooks

Ledger's networked half runs on **your** WordPress. One plugin — `ledgr-fb-bridge` —
exposes a small REST surface at `/wp-json/ledgr/v1/*`, authenticated with a normal
WordPress **application password**. There is no middleman service.

---

## What the bridge exposes today

| Area | Routes | What it does |
|---|---|---|
| Cards | `/card`, `/card/{uuid}/ocr`, `/card/{id}/strokes` | A handwritten card becomes a FluentBoards task — stroke PNG as the cover, raw strokes archived, OCR written back later |
| Boards | `/boards`, `/board/{id}/compact`, `/due-cards`, `.../move`, `.../update`, `.../comment`, `.../members`, `.../related` | Browse and work real boards from e-ink: move cards, assign, set due/priority, comment (typed or handwritten) |
| Community | `/community/feed`, `/community/gram`, `/community/comment`, `/community/react`, `/community/spaces`, `/community/related`, `/community/courses` | Post grams, reply (ink or markdown), like, browse spaces and courses |
| Correspondence | `/correspondence`, `/correspondence/to-board`, `/thread`, `/thread/delete` | Gather replies to your posts and cards; read whole threads in-app; turn replies into board cards |
| Chat | `/chat/{thread}/ink` (+ FluentCommunity's own `v2/chat/*`) | Group chat and DMs, including handwritten messages |
| Essays | `/essay` | A finished page goes out as a WordPress draft (any post type), an email, or a community post |
| Bookings | `/bookings`, `/booking/{id}`, `.../cancel`, `.../reschedule`, `.../note`, `/booking/event/{id}/slots` | See below |
| **CRM** | `/crm/note`, `/crm/contact/{id}/compact` | See below |

## FluentBooking: a booking is just a dated object

The bookings routes deliberately answer in the **same dialect as `/due-cards`** — a title,
a date, and a `bucket` of `todo` / `doing` / `done`. That's the whole trick. Your timeline
and your kanban already know how to draw a dated object with a state, so a booking needs no
surface of its own: it lands in the same columns and the same day list as a board card, with
its own tint and a 🕘 instead of a 🌐.

The bucket is read off the booking's own life rather than a stage position — **still ahead of
you → todo, under way right now → doing, settled either way → done.**

Everything is scoped server-side to bookings **you host**. Nobody sees the whole book.

### The four gestures

Tapping a booking opens a sheet offering exactly four things, because these are the four you'd
do with a pen in your hand:

| | |
|---|---|
| **Keep it** | do nothing — just read who's coming, what they said, and where |
| **Cancel…** | with a reason. FluentBooking sends the attendee's mail itself |
| **Move…** | pick from the event's *own* open times, so a move can't land somewhere the calendar wouldn't have offered |
| **Note…** | in your own hand. Lands on the booking **and** on the person in FluentCRM |

Authoring availability, building event types and editing booking forms stay on the web, where
a keyboard already lives. Refusing that scope is the point — it's what keeps four Fluent
integrations from turning Ledger into an admin console.

### What "Move…" can and can't do

**Group and round-robin bookings can't be moved from the device.** They carry seat and host
bookkeeping that the web flow owns, and half-doing it here would corrupt the group. On a studio
calendar that's *most* of the book — every class booking is a group booking — so the API says so
up front via `can_move`, and the sheet hides the button rather than offering one that always
fails. One-to-one sessions move fine.

A move preserves duration: it relocates a booking, it doesn't resize it.

### Hooks

Writes go through FluentBooking's own paths, so its notifications, CRM triggers and remote
calendar sync all follow: cancel delegates to the model's `cancelMeeting()`, and reschedule
mirrors the plugin's own sequence, firing `fluent_booking/log_booking_activity` and
`fluent_booking/after_booking_rescheduled`. Nothing is written behind the plugin's back.

Bookings the device creates are tagged `source = ledgr`, which is also how a two-way sync
avoids echoing its own writes back at itself.

### No new setup

Bookings reuse the site/user/password you already set under **Community & Boards (Fluent)**,
and ride the same `include_site` opt-in as Site cards. There is no separate booking switch to
find — turning on Site content turns on all of it.

## FluentCRM: what works now

**`POST /crm/note`** is the heart of it. Hand it a note (typed or OCR'd from
handwriting) and it will:

1. **Find or create the contact** — by `contact_id`, by `email`, or by pulling an email
   address out of the OCR text itself.
2. **Write the note onto the contact's timeline** as a FluentCRM `SubscriberNote`,
   including the image of what you wrote.
3. **Apply tags** you pass along.
4. **Fire `ledgr_fb/crm_note_created`** — an action hook carrying the contact, the note,
   the tags, and the image URL.

Board cards also carry `crm_contact_id`, auto-matched from the card's content, and a
card's detail view shows the linked contact.

**`GET /crm/contact/{id}/compact`** returns a contact snapshot sized for e-ink.

## How you actually send mail with this

Ledger deliberately does **not** ship its own mailer. In the Fluent idiom you don't send
from the device — you *tag*, and the automation sends. The chain is:

```
handwrite a note  →  /crm/note  →  contact + note + TAG
                                        ↓
                        FluentCRM automation (trigger: tag applied)
                                        ↓
                        email / sequence / FluentCampaign Pro journey
```

So a handwritten card on your Boox can start a welcome sequence, a follow-up, or a
one-off email — with the handwriting itself attached to the contact record as evidence
of what you actually said.

Two extra levers:

- **`ledgr_fb/crm_note_created`** is a normal WordPress action. Hang anything off it:
  a FluentCampaign Pro sequence attach, a `wp_mail`, an n8n/Zapier call, a Slack ping.
  This is the "webhook" seam — and because it's server-side, it works even when the
  device that wrote the note is asleep.
- **`POST /essay` with `dest=email`** sends a finished page directly to an address via
  `wp_mail`. That's a deliberate one-off send, not a campaign.

### The honest gap

There is **no route today that composes a campaign or emails a CRM list/segment from
inside Ledger**. If you want "write a note on the Boox → it goes out to a segment," you
get there via tag-triggered automation (above), not by pressing send in the app.

Closing that is small and worth doing: a `/crm/send` route taking `contact_id` or a
tag/list plus a subject and body, sending through FluentCRM so it's logged on the
contact's timeline like any other message. Say the word and it goes on the list — the
client side already has the composer (the Correspondence reply surface) to drive it.

## Why the webhook matters

The action hook is what turns Ledger from a notebook into a system of record:

- **It's server-side.** Your device writes once; the server can do ten things afterward
  without the device staying awake.
- **It's neutral.** FluentCampaign, n8n, Zapier, a custom `add_action` in your theme —
  anything that speaks WordPress can listen.
- **It carries the artifact.** The payload includes the image URL, so downstream systems
  can show the actual handwriting, not just a transcription.
- **It composes.** Tag → automation → email → reply → **back into Correspondence**,
  where you can answer it by hand and save that reply into your own Ledger. The loop
  closes without leaving your infrastructure.

## Setting it up

1. Install WordPress with the Fluent plugins you want (FluentCommunity, FluentBoards,
   FluentCRM — each is optional; the bridge degrades gracefully and returns a clear
   "not active on this site" if one is missing).
2. Install `ledgr-fb-bridge.php` (in `server-bridge/` in this repo).
3. Create a WordPress **application password** for your user.
4. In Ledger: Settings → Community & Boards → site URL, username, application password,
   and (optionally) the FluentBoards board id you want as your inbox.

Permissions are split: gram/comment/spaces/courses/correspondence work for any logged-in
member; boards, CRM and essay routes stay staff-only.

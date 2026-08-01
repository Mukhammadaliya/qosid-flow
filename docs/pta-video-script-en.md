# QosID Flow — three-minute video script (EN)

**For:** President Tech Award, incubation track — "link to project video"
**Runtime:** 2:50–3:00. Do not go over three minutes.
**Format:** screen recording + voiceover. No face required; nice to have in the last shot.

This is the English cut of `pta-video-script.md`. It is an adaptation, not a translation —
English carries the same content in fewer, shorter sentences, so the beats land differently
even though the timings and the slide order match.

> **If the figures change, change them in all three scripts.** They are spoken as words, so
> a search-and-replace over the deck will not find them. The five that appear below:
> **60 600** requests/day · **99.68 %** · **918** req/s peak · **241** DLQ in one minute ·
> **41** safety rules.

**Structure:** the script follows the deck's twelve slides, so a judge who watches the video
and then opens the PDF travels the same road twice. But **do not read the slides**: on
slides 3, 4 and 5 the screen shows the running system, not the deck.

---

## Before you record

Slide 4 needs a live demo, and the local instance has no traffic — the Statistics page will
be empty. Two options:

1. **Generate synthetic load locally** (recommended) — a real interface behaving for real,
   with no customer data. A video lives at a public link and cannot be taken back.
2. Tunnel to production — real numbers, but tenant ids, request ids and upstream hostnames
   on screen. **Not recommended.**

---

## Timing

| time | slide | on screen | words |
|---|---|---|---|
| 0:00–0:14 | 01 | Slide: title, packets flowing | ~30 |
| 0:14–0:30 | 02 | Slide: four failures | ~35 |
| 0:30–0:50 | 03 | **Live:** topology animation | ~42 |
| 0:50–1:22 | 04 | **Live system:** request → console | ~66 |
| 1:22–1:50 | 05 | **Live:** statistics, then slide | ~58 |
| 1:50–2:00 | 06 | Slide: business model | ~21 |
| 2:00–2:09 | 07 | Slide: market | ~19 |
| 2:09–2:21 | 08 | Slide: competition | ~25 |
| 2:21–2:30 | 09 | Slide: channels | ~19 |
| 2:30–2:38 | 10 | Slide: team | ~15 |
| 2:38–2:52 | 11 | Slide: have / don't have | ~29 |
| 2:52–3:00 | 12 | Slide: the close | ~16 |

**447 words** (counted). At 2.5 words a second, about 2:59.
English runs dense — slow down rather than adding words, and let the figures sit on screen.

---

## Script

### 0:00–0:14 · Slide 01 — The hook

**On screen:** the title slide, packets crossing the gate. No cursor. Let it breathe.

> Enterprise systems talk to each other all day. When that conversation breaks, an order
> vanishes, a payment never lands, a report never arrives.
> And usually the customer is the one who notices first.
> I'm Muxammadali. This is QosID Flow.

---

### 0:14–0:30 · Slide 02 — The problem

**On screen:** the four failures arrive one by one. Hold a beat on each.

> Integrations are usually hand-written scripts.
> A thousand requests at once and the server gives way. The API slows down and the
> script blocks. Something fails and the error is swallowed.
> And nobody re-sends what was lost.

---

### 0:30–0:50 · Slide 03 — The solution

**On screen:** the topology. **Follow one packet with the cursor** as it crosses the gate
and turns from blue to green — these are the clearest five seconds in the video.

> QosID Flow stands in front of your main application, in both directions — the requests
> coming in, and the ones going out to third-party APIs.
> One pipeline for both: queued, retried, never lost.
> Normally this takes two separate products.

---

### 0:50–1:22 · Slide 04 — The product *(the heart of the video)*

Three short shots. The live system, not the slide.

**A · 0:50–1:06 — Send a request**
**On screen:** a terminal. One `POST`. The answer: `202 ACCEPTED` and a polling URL. **Zoom in.**

> I'll send a request. The answer is immediate: two-oh-two, and a link to poll.
> Notice **when** it is returned — after the request is durably queued.
> So "accepted" doesn't mean "we'll try". It means "it is safe with us".

**B · 1:06–1:14 — Errors**
**On screen:** the Errors page. Open one entry so the detail shows.

> A request that cannot be delivered is never dropped — it lands in a dead-letter queue,
> Telegram gets an alert, and the reason is right here.

**C · 1:14–1:22 — Operator console**
**On screen:** the Ops page, live log streaming.

> And settings can only be changed while the system is stopped — a bad setting can never
> reach live traffic.

---

### 1:22–1:50 · Slide 05 — Not a prototype

**On screen:** the live Statistics page for five seconds, then cut to the slide and let the
figures count up. **Keep numbers on screen for at least three seconds.**

> Not a prototype. In production since April: sixty thousand six hundred requests a day,
> ninety-nine point six eight percent success, measured peak nine hundred and eighteen
> a second — nothing lost.
>
> On the twenty-seventh of July, in one minute, two hundred and forty-one live requests
> fell into the error queue.
> We found the cause, shipped three changes, measured the result.
> Next day a burst five times denser went through — not one request lost.

---

### 1:50–2:00 · Slide 06 — Business model

> Five revenue streams; three in focus today: implementation services, per-connector
> pricing, managed cloud.

---

### 2:00–2:09 · Slide 07 — Market

> We sit where two segments overlap: integration platforms and API gateways.
> The beachhead is Uzbek enterprise and government.

---

### 2:09–2:21 · Slide 08 — Competition

> API gateways take inbound traffic, but synchronously — if the app is down, the request
> is lost. Integration platforms handle outbound, but are not an HTTP front door.
> Workflow engines were never built for this volume.

---

### 2:21–2:30 · Slide 09 — Go to market

> Three channels, lowest friction first: pilot-ecosystem customers, then IT Park residents
> and ERP integrators, then managed cloud.

---

### 2:30–2:38 · Slide 10 — Team

> Five of us. Three build. Two go and test the market.

---

### 2:38–2:52 · Slide 11 — What we have, what we don't

**On screen:** two columns. **Hold on the right-hand one** — it is the most honest frame in
the video, and it is the reason we are applying.

> We have a system in production, forty-one safety rules, a console in three languages.
> What we don't have: a customer outside the parent company, a pricing model, a sales
> channel, market validation.
> That is why we are here. Not to build the product — **to find out who buys it**.

---

### 2:52–3:00 · Slide 12 — The close

**On screen:** the closing slide, four rows striking through. Logo and links in the last two
seconds.

> In three months that column empties.
> The system already delivers sixty thousand requests a day. What's left is knowing who to
> deliver the product to.
> QosID Flow. Thank you.

---

## Recording notes

**Screen**
- 1920×1080, browser fullscreen (`f` puts the deck in fullscreen)
- Turn off notifications; close spare tabs; use a clean browser profile
- Zoom the console pages to 125–150 % — a judge may watch on a phone
- Move the cursor slowly; rest on anything that matters for a second or two

**Voice**
- Record it **separately** from the screen. A fumbled line then costs one take, not the
  whole video
- Microphone 15–20 cm from your mouth, quiet room, window shut
- One take per slide — the script is already split that way

**Editing**
- Half a second of air between slides
- Numbers stay on screen three seconds minimum
- Music, if any, well under the voice. None at all is fine
- Export MP4, H.264, 1080p, 30 fps

**Don't**
- Read the slides — the most common mistake in this format
- Explain the architecture; that is what the repository is for
- Say "we are the best" — the numbers already said it
- Run over. Re-cut even at 3:01

---

## Links in the final frame

- GitHub: `github.com/Mukhammadaliya/qosid-flow`
- Demo: *(added once the demo instance is up)*

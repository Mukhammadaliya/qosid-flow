# QosID Flow — three-minute video script (EN)

**For:** President Tech Award, incubation track — "link to project video"
**Runtime:** 2:50–3:00. Do not go over three minutes.
**Format:** **a recording of the animated deck + voiceover.** No live system on screen.

This is the English cut of `pta-video-script.md`. An adaptation, not a translation — English
carries the same content in more, shorter words, so it is read faster; the timings and the
slide order match.

> **If the figures change, change them in all three scripts.** They are spoken as words, so
> a search-and-replace over the deck will not find them. The five that appear below:
> **60 600** requests/day · **99.68 %** · **918** req/s peak · **241** DLQ in one minute ·
> **41** safety rules.

**Structure:** the script follows the deck's twelve slides, and the deck is all that is on
screen. The slides are animated: the headline settles, blocks arrive in sequence, packets
cross the gate on slide 3, the figures count up on slide 5, the rows strike through on
slide 12. **The voice does not read the slide** — the screen shows, the voice says what the
screen does not. That is the trap in this format: repeat what is written and the video adds
nothing.

**Pace to the animation.** Each slide takes about 0.6 s to arrive — start the line after
that, or the first words land on an empty screen.

---

## Timing

| time | slide | on screen | words |
|---|---|---|---|
| 0:00–0:14 | 01 | Slide: title, packets flowing | ~30 |
| 0:14–0:30 | 02 | Slide: four failures | ~35 |
| 0:30–0:54 | 03 | Topology, packets crossing the gate | ~55 |
| 0:54–1:16 | 04 | Three console screens | ~48 |
| 1:16–1:44 | 05 | Figures count up, then the incident | ~62 |
| 1:44–1:54 | 06 | Business model | ~21 |
| 1:54–2:03 | 07 | Market | ~19 |
| 2:03–2:15 | 08 | Competition | ~25 |
| 2:15–2:24 | 09 | Channels | ~19 |
| 2:24–2:32 | 10 | Team | ~15 |
| 2:32–2:48 | 11 | Have / don't have columns | ~32 |
| 2:48–3:00 | 12 | The close, rows striking through | ~22 |

**472 words.** Between 2:55 (2.7 words a second) and 3:17 (2.4) depending on pace — time your
first read-through. The four marked lines are 29 words, about 12 seconds: drop them if you run long.
Time your first read-through; if it runs long, drop the four lines marked *(drop this if you run long)*
and you are at 3:05.
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
> A thousand requests at once and the server gives way. The API slows and the script
> blocks. Something fails, the error is swallowed, and nobody re-sends what was lost.

---

### 0:30–0:54 · Slide 03 — The solution

**On screen:** the topology. **Follow one packet with the cursor** as it crosses the gate
and turns from blue to green — these are the clearest five seconds in the video.

> QosID Flow stands in front of your main application, in both directions — the requests
> coming in, and the ones going out to third-party APIs.
> One pipeline for both: queued, retried, never lost.
>
> One detail matters. The client is told "accepted" only **after** the request is durably
> queued. So it does not mean "we'll try" — it means "it is safe with us".
> Normally this takes two separate products.  *(drop this if you run long)*

---

### 0:54–1:16 · Slide 04 — The product

**On screen:** three captures of the console. Point at each with the cursor and rest there
for two or three seconds — give the judge time to read it.

> And all of it is visible. First, live statistics: throughput, success rate, latency.
> Second, errors. A request that cannot be delivered is never dropped — it lands in a
> dead-letter queue, Telegram gets an alert, and AI explains the reason.
> Third, the operator console: stop, start, live log.
> And settings can only be changed while the system is stopped — a bad setting can never
> reach live traffic.

---

### 1:16–1:44 · Slide 05 — Not a prototype

**On screen:** four figures count up from zero (~0.9 s). **Wait for the count to finish** —
saying a number while it is still climbing sounds muddled.

> Not a prototype. In production since April: sixty thousand six hundred requests a day,
> ninety-nine point six eight percent success, measured peak nine hundred and eighteen
> a second — nothing lost.
>
> On the twenty-seventh of July, in one minute, two hundred and forty-one live requests
> fell into the error queue.
> We found the cause, shipped three changes, measured the result.
> Next day a burst five times denser went through — not one request lost.

---

### 1:44–1:54 · Slide 06 — Business model

> Five revenue streams, three in focus: implementation services, per-connector pricing,
> managed cloud.

---

### 1:54–2:03 · Slide 07 — Market

> We sit where two segments overlap: integration platforms and API gateways.
> The beachhead is Uzbek enterprise and government integration.  *(drop this if you run long)*

---

### 2:03–2:15 · Slide 08 — Competition

> API gateways take inbound traffic, but synchronously — if the app is down, the request
> is lost. Integration platforms handle outbound, but are no HTTP front door.
> Workflow engines were never built for this volume.  *(drop this if you run long)*

---

### 2:15–2:24 · Slide 09 — Go to market

> Three channels, lowest friction first: pilot-ecosystem customers, IT Park residents and
> ERP integrators, then managed cloud.

---

### 2:24–2:32 · Slide 10 — Team

> Five of us. Three build. Two go and test the market.

---

### 2:32–2:48 · Slide 11 — What we have, what we don't

**On screen:** two columns. **Hold on the right-hand one** — it is the most honest frame in
the video, and it is the reason we are applying.

> We have a system in production, forty-one safety rules, a console in three languages.
> What we don't have: a customer outside the parent company, a pricing model, a sales
> channel, market validation.
> That is why we are here. Not to build the product — **to find out who buys it**.

---

### 2:48–3:00 · Slide 12 — The close

**On screen:** the closing slide, four rows striking through. Logo and links in the last two
seconds.

> In three months that column empties: a first paying pilot, a confirmed price, a first
> partnership through IT Park, twenty customer interviews.  *(drop this if you run long)*
> The system already delivers sixty thousand requests a day. What's left is knowing who to
> deliver the product to.
> QosID Flow. Thank you.

---

## Recording notes

**Deck**
- `f` for fullscreen; arrow keys move between slides
- Language: `1` `2` `3`, or `?lang=uz|ru|en`. Open the right one **before** you record
- 1920×1080, notifications off, spare tabs closed

**Work with the animation**
- Each slide takes ~0.6 s to arrive — start the line after that
- On slide 5 the figures count for 0.9 s — wait for them
- Slides 3 and 12 move continuously; don't rush, the frame carries itself

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
- Read the slides — don't repeat what is written, add to it
- Explain the architecture; that is what the repository is for
- Say "we are the best" — the numbers already said it
- Run over. Re-cut even at 3:01

---

## Links in the final frame

- GitHub: `github.com/Mukhammadaliya/qosid-flow`
- Demo: *(added once the demo instance is up)*

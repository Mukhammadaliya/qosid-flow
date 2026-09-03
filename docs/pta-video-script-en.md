# QosID Flow — three-minute video script (EN)

**For:** President Tech Award, incubation track — "link to project video"
**Runtime:** 2:50–3:00. Do not go over three minutes.
**Format:** **a recording of the animated deck + voiceover.** No live system on screen.

This is the English cut of `pta-video-script.md`. An adaptation, not a translation — English
carries the same content in more, shorter words, so it is read faster; the timings and the
slide order match.

> **If the figures change, change them in all three scripts.** They are spoken as words, so
> a search-and-replace over the deck will not find them.
>
> **UNRESOLVED: the voice and the deck disagree.** All three scripts now speak rounded
> figures — "more than sixty thousand", "ninety-nine percent", "almost a thousand", "more
> than forty" — and the 27 July incident is not mentioned at all. The deck on screen still
> shows the exact values: **60 600** requests/day · **99.68 %** · **918** req/s peak ·
> **241** DLQ in one minute · **41** safety rules. A judge therefore reads one number and
> hears another. **Pick one before recording:** either put the exact figures back into the
> scripts, or round them in the deck. Measured sources: 60 591/day, 99.68 %, 918/s (28.07),
> 241 DLQ (27.07 14:27 UTC), 41 rules.

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

Time is the window the deck's animation sets; words are the text as it now stands, at
2.5 words a second.

| time (window) | slide | on screen | words | ~duration | status |
|---|---|---|---|---|---|
| 0:00–0:14 | 01 | Title, packets flowing | 59 | 23.6s | **10s over** |
| 0:14–0:30 | 02 | Four failures, one by one | 38 | 15.2s | ✓ |
| 0:30–0:54 | 03 | Topology, packets crossing the gate | 51 | 20.4s | 4s spare |
| 0:54–1:16 | 04 | Three console screens | 68 | 27.2s | **5s over** |
| 1:16–1:44 | 05 | Figures count up, then the incident | 35 | 14.0s | **14s spare** |
| 1:44–1:54 | 06 | Business model | 20 | 8.0s | ✓ |
| 1:54–2:03 | 07 | Market | 11 | 4.4s | 5s spare |
| 2:03–2:15 | 08 | Competition | 44 | 17.6s | **6s over** |
| 2:15–2:24 | 09 | Channels | 16 | 6.4s | 3s spare |
| 2:24–2:32 | 10 | Team | 15 | 6.0s | 2s spare |
| 2:32–2:48 | 11 | Have / don't have columns | 55 | 22.0s | **6s over** |
| 2:48–3:00 | 12 | The close, rows striking through | 29 | 11.6s | ✓ |

**441 words ≈ 2:56** at 2.5 words a second — that is only four seconds inside the limit, and
a slower read goes over it. Time your first read-through, and cut rather than speed up.

Three things to know:

- **Slide 01 runs 10 seconds over.** The cause is the second block, the positioning line. It
  is **also on screen**, and the script's own rule is that the voice does not read the slide.
  That makes it the obvious cut: drop it and slide 01 becomes 35 words and 14.0s, inside the
  window (417 words, 2:47 overall).
- **Slide 05 leaves about 14 seconds of silence** — the 27 July incident was cut from the
  text while the screen still animates it: the voice is quiet, the screen talks.
- **Slides 04, 08 and 11 run over** by 5–6 seconds. English is the densest of the three cuts;
  lengthen the animation or shorten the lines.

---

## Script

### 0:00–0:14 · Slide 01 — The hook

**On screen:** the title slide, packets crossing the gate. No cursor. Let it breathe.

> Enterprise systems exchange data constantly.
> When that exchange breaks, an order vanishes, a payment never lands, a report never arrives.
> And usually, unfortunately, the customer is the one who notices first.
> This is QosID Flow.

> A platform that reliably delivers every request between enterprise systems and spreads
> load over time: nothing lost, nothing duplicated, and above all, everything visible.

---

### 0:14–0:30 · Slide 02 — The problem

**On screen:** the four failures arrive one by one. Hold a beat on each.

> Integrations are usually hand-written scripts.
> A thousand requests arrive at once and the server gives way.
> A third-party API slows down and the script blocks.
> Something fails and the error is swallowed.
> And nobody re-sends what was lost.

---

### 0:30–0:54 · Slide 03 — The solution

**On screen:** the topology. **Follow one packet with the cursor** as it crosses the gate
and turns from blue to green — these are the clearest five seconds in the video.

> QosID Flow stands in front of your main application, in both directions: it governs the
> requests coming in, and the ones going out to third-party APIs.
> Both flows run through one pipeline: written to a queue, retried on failure — and, above
> all, never lost.
> Normally this takes two separate products.

---

### 0:54–1:16 · Slide 04 — The product

**On screen:** three captures of the console. Point at each with the cursor and rest there
for two or three seconds — give the judge time to read it.

> Every process is visible.
> First, live statistics: throughput, success rate, latency.
> Second, errors. A request that cannot be delivered is never dropped — it lands in a
> separate queue, Telegram gets an alert, and the cause can be analysed with AI.
> Third, the operator console: stop, start, live log.
> And settings can only be changed while the system is stopped — a bad setting cannot reach
> live traffic.

---

### 1:16–1:44 · Slide 05 — Not a prototype

**On screen:** four figures count up from zero (~0.9 s). **Wait for the count to finish** —
saying a number while it is still climbing sounds muddled.

> Not a prototype. Running on a production server since April:
> more than sixty thousand requests a day,
> ninety-nine percent success.
> Almost a thousand requests a second, with nothing lost.
> And that is not the ceiling.

---

### 1:44–1:54 · Slide 06 — Business model

> Our project has five revenue streams; three are in focus today:
> implementation services,
> per-connector pricing,
> and a managed cloud offering.

---

### 1:54–2:03 · Slide 07 — Market

> We sit where two segments overlap: integration platforms and API gateways.

---

### 2:03–2:15 · Slide 08 — Competition

> API gateways take inbound traffic, but if the application is down the request is lost.
> Integration platforms handle outbound, but they cannot be the front door for HTTP requests.
> And in many cases their workflow engines were not built for this kind of volume.

---

### 2:15–2:24 · Slide 09 — Go to market

> Three channels, mainly:
> pilot-ecosystem customers,
> IT Park residents,
> ERP integrators,
> then we move to managed cloud.

---

### 2:24–2:32 · Slide 10 — Team

> Five of us: three of us develop the system, two of us analyse the market.

---

### 2:32–2:48 · Slide 11 — What we have, what we don't

**On screen:** two columns. **Hold on the right-hand one** — it is the most honest frame in
the video, and it is the reason we are applying.

> We have a system running in production, more than forty safety rules, a management console
> in three languages.
> What we don't have: a customer outside the parent company; no pricing model; no sales
> channel, no market validation.
> That is why we are here: not to build the product, but to find out who buys it.

---

### 2:48–3:00 · Slide 12 — The close

**On screen:** the closing slide, four rows striking through. Logo and links in the last two
seconds.

> Our three-month plan is three outcomes:
> a first customer,
> a confirmed price and business model,
> a partnership through IT Park.
> This is QosID Flow. Thank you for your attention.

---

## Practical notes for the recording

**The deck**
- `f` for full screen; arrow keys move between slides
- Language: keys `1` `2` `3`, or `?lang=uz|ru|en`. Open the right one **before** you record
- 1920×1080, notifications off, other tabs closed

**Work with the animation**
- A slide takes ~0.6 s to arrive — start the line after that
- On slide 5 the figures count for 0.9 s — wait for them
- On slides 3 and 12 the motion is continuous; do not rush, the frame carries itself

**Voice**
- Record it **separately** from the screen. Then a fluffed line costs one slide, not the film
- Mic 15–20 cm from the mouth, quiet room, window shut
- One take per slide — the script is already cut that way

**Edit**
- Half a second of air between slides
- Figures stay on screen for at least three seconds
- Music, if any, well under the voice. None at all is fine too
- Export: MP4, H.264, 1080p, 30 fps

**Do not**
- Read the slides — do not repeat what is written, add to it
- Go deep on architecture; the repository is there for that
- Say "we are the best" — the figures already said it
- Run past three minutes. Re-cut even for 3:01

---

## Links in the closing frame

- GitHub: `github.com/Mukhammadaliya/qosid-flow-showcase`
- Demo: *(to be added once the demo instance is up)*

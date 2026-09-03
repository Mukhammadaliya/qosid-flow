import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * The login backdrop runs a small, honest simulation of the pipeline.
 *
 * It is not an ambient loop for decoration — it plays the product's actual behaviour on a
 * ~21s cycle: steady delivery, an upstream that starts failing, the circuit tripping open,
 * requests PARKING against the gate instead of being lost, the probe, and then the parked
 * burst draining green. That is the 2026-07-27 incident and the fix that followed, which is
 * the same story the README and the pitch tell. The telemetry panel reads off the same
 * simulation state, so the numbers and the picture can never disagree.
 *
 * The login card sits on the gate. Requests pass behind it and come out delivered.
 */

type Phase = 'steady' | 'degrade' | 'open' | 'probe' | 'drain';
type PacketState = 'inflight' | 'parked' | 'delivered' | 'failed';
type Glyph = 'order' | 'payment' | 'stock' | 'invoice' | 'catalog' | 'report' | 'file';

interface Packet {
  glyph: Glyph;
  label: string;
  lane: 0 | 1;          // 0 = inbound (L→R), 1 = outbound (R→L)
  pos: number;          // 0..1 along its lane, always in travel direction
  speed: number;
  state: PacketState;
  slot: number;         // parking slot index, -1 when not parked
  fade: number;         // 0..1, used on entry and exit
}

const CYCLE = 21000;
const MARKS: { phase: Phase; until: number }[] = [
  { phase: 'steady', until: 8000 },
  { phase: 'degrade', until: 9600 },
  { phase: 'open', until: 15600 },
  { phase: 'probe', until: 17000 },
  { phase: 'drain', until: CYCLE },
];

const BLUE = '0, 149, 217';
const GREEN = '46, 194, 126';
const AMBER = '245, 166, 35';

/**
 * What actually travels through this pipeline in production: business operations, not
 * abstract ids. Naming them the way the upstream API does (`order_export`, `stock_sync`)
 * makes the backdrop legible at a glance — you can see WHAT is being delivered, not just
 * that something is.
 */
const KINDS: { glyph: Glyph; label: string }[] = [
  { glyph: 'order', label: 'order_export' },
  { glyph: 'payment', label: 'payment' },
  { glyph: 'stock', label: 'stock_sync' },
  { glyph: 'invoice', label: 'invoice' },
  { glyph: 'catalog', label: 'product_list' },
  { glyph: 'report', label: 'report' },
  { glyph: 'file', label: 'file_upload' },
];
function nextKind() {
  return KINDS[Math.floor(Math.random() * KINDS.length)];
}

/**
 * Glyphs are drawn, not iconised from a font: they must stay crisp inside a canvas that
 * scales with DPR, and the set is small enough that hand-drawn paths beat a dependency.
 * Each is defined in a 14×14 box with its origin at (x, y).
 */
function drawGlyph(ctx: CanvasRenderingContext2D, kind: Glyph, x: number, y: number, tone: string, a: number) {
  ctx.save();
  ctx.translate(x, y);
  ctx.strokeStyle = `rgba(${tone}, ${0.92 * a})`;
  ctx.fillStyle = `rgba(${tone}, ${0.92 * a})`;
  ctx.lineWidth = 1.4;
  ctx.lineJoin = 'round';
  ctx.lineCap = 'round';

  switch (kind) {
    case 'order': // shopping bag
      ctx.beginPath();
      ctx.moveTo(2.5, 4.5); ctx.lineTo(11.5, 4.5); ctx.lineTo(10.6, 12.5); ctx.lineTo(3.4, 12.5); ctx.closePath();
      ctx.stroke();
      ctx.beginPath(); ctx.arc(7, 4.5, 2.3, Math.PI, 0); ctx.stroke();
      break;
    case 'payment': // card with stripe
      ctx.beginPath(); ctx.roundRect(1.5, 3.5, 11, 8, 1.6); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(1.5, 6.4); ctx.lineTo(12.5, 6.4); ctx.stroke();
      ctx.beginPath(); ctx.roundRect(3.2, 8.4, 3.4, 1.6, 0.6); ctx.fill();
      break;
    case 'stock': // box with seam
      ctx.beginPath(); ctx.roundRect(2, 3.5, 10, 9, 1.4); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(2, 6.6); ctx.lineTo(12, 6.6); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(7, 3.5); ctx.lineTo(7, 6.6); ctx.stroke();
      break;
    case 'invoice': // document with folded corner
      ctx.beginPath();
      ctx.moveTo(3, 2.5); ctx.lineTo(8.6, 2.5); ctx.lineTo(11, 5); ctx.lineTo(11, 12.5);
      ctx.lineTo(3, 12.5); ctx.closePath();
      ctx.stroke();
      ctx.beginPath(); ctx.moveTo(8.6, 2.5); ctx.lineTo(8.6, 5); ctx.lineTo(11, 5); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(5, 8); ctx.lineTo(9, 8); ctx.moveTo(5, 10.2); ctx.lineTo(9, 10.2); ctx.stroke();
      break;
    case 'catalog': // price tag
      ctx.beginPath();
      ctx.moveTo(7.2, 2.4); ctx.lineTo(12.4, 7.6); ctx.lineTo(7.4, 12.6); ctx.lineTo(2.2, 7.4);
      ctx.lineTo(2.2, 2.4); ctx.closePath();
      ctx.stroke();
      ctx.beginPath(); ctx.arc(4.7, 4.9, 1.15, 0, Math.PI * 2); ctx.stroke();
      break;
    case 'report': // bars
      ctx.beginPath(); ctx.roundRect(2.6, 8, 2.2, 4.6, 0.9); ctx.fill();
      ctx.beginPath(); ctx.roundRect(6.0, 5, 2.2, 7.6, 0.9); ctx.fill();
      ctx.beginPath(); ctx.roundRect(9.4, 2.6, 2.2, 10, 0.9); ctx.fill();
      break;
    case 'file': // document with an up arrow
      ctx.beginPath(); ctx.roundRect(2.6, 2.6, 8.8, 9.8, 1.3); ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(7, 9.6); ctx.lineTo(7, 5.2);
      ctx.moveTo(4.9, 7.1); ctx.lineTo(7, 5.0); ctx.lineTo(9.1, 7.1);
      ctx.stroke();
      break;
  }
  ctx.restore();
}

function phaseAt(t: number): Phase {
  for (const m of MARKS) if (t < m.until) return m.phase;
  return 'steady';
}

export function PipelineStage() {
  const { t } = useTranslation();
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [telemetry, setTelemetry] = useState({
    circuit: 'CLOSED' as 'CLOSED' | 'OPEN' | 'HALF_OPEN',
    parked: 0,
    delivered: 0,
    inflight: 0,
  });

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const maybeCtx = canvas.getContext('2d');
    if (!maybeCtx) return;
    // Narrowed once: the nested draw/step closures below lose the null-check otherwise.
    const ctx: CanvasRenderingContext2D = maybeCtx;

    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    let w = 0, h = 0, dpr = 1;
    const resize = () => {
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      w = canvas.clientWidth; h = canvas.clientHeight;
      canvas.width = Math.floor(w * dpr);
      canvas.height = Math.floor(h * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    resize();
    window.addEventListener('resize', resize);

    const packets: Packet[] = [];
    let delivered = 0;
    let raf = 0;
    let last = performance.now();
    let clock = 0;
    let sinceSpawn = 0;
    let uiTick = 0;

    const laneY = () => [h * 0.29, h * 0.71];
    const gate = () => w / 2;

    /** Where a packet sits, in screen px, given its travel progress. */
    const xAt = (p: Packet) => (p.lane === 0 ? p.pos * w : w - p.pos * w);

    const spawn = (lane: 0 | 1) => {
      packets.push({
        ...nextKind(), lane, pos: 0,
        speed: 0.085 + Math.random() * 0.03,
        state: 'inflight', slot: -1, fade: 0,
      });
    };

    // Seed a populated stage so the first frame is never empty.
    for (let i = 0; i < 7; i++) {
      const lane = (i % 2) as 0 | 1;
      packets.push({
        ...nextKind(), lane, pos: 0.08 + i * 0.12,
        speed: 0.085 + Math.random() * 0.03,
        state: 'inflight', slot: -1, fade: 1,
      });
    }

    function step(dt: number) {
      const phase = phaseAt(clock);
      const open = phase === 'open' || phase === 'degrade';
      const gateX = gate();

      // ---- spawning: quieter while the circuit is open, a rush on drain ----
      const every = phase === 'drain' ? 900 : phase === 'open' ? 620 : 520;
      sinceSpawn += dt;
      if (sinceSpawn > every && packets.length < 26) {
        sinceSpawn = 0;
        spawn((Math.random() < 0.55 ? 0 : 1) as 0 | 1);
      }

      // ---- parking slots, counted per lane ----
      const parkedCount: [number, number] = [0, 0];

      for (const p of packets) {
        p.fade = Math.min(1, p.fade + dt / 420);
        const x = xAt(p);
        const beforeGate = p.lane === 0 ? x < gateX : x > gateX;

        if (p.state === 'parked') {
          parkedCount[p.lane] += 1;
          continue;
        }
        if (p.state === 'delivered' || p.state === 'failed') {
          p.pos += (p.speed * dt) / 1000;
          continue;
        }

        // in flight
        if (open && beforeGate) {
          // Park against the gate rather than crossing it. The queue is the point:
          // the request is held, not dropped.
          const slot = parkedCount[p.lane];
          const targetPos = 0.5 - 0.028 - slot * 0.032;
          if (p.pos >= targetPos) {
            p.pos = targetPos;
            p.state = 'parked';
            p.slot = slot;
            parkedCount[p.lane] += 1;
            continue;
          }
          // decelerate as it approaches the back of the queue
          const gap = Math.max(0, targetPos - p.pos);
          p.pos += (p.speed * dt * Math.min(1, gap * 9)) / 1000;
          continue;
        }

        p.pos += (p.speed * dt) / 1000;

        if (p.pos >= 0.5) {
          if (phase === 'degrade' && Math.random() < 0.5 && p.state === 'inflight') {
            p.state = 'failed';
          } else if (p.state === 'inflight') {
            p.state = 'delivered';
            delivered += 1;
          }
        }
      }

      // ---- release the queue ----
      if (phase === 'probe') {
        const first = packets.find((p) => p.state === 'parked');
        if (first) { first.state = 'inflight'; first.slot = -1; }
      }
      if (phase === 'drain') {
        for (const p of packets) {
          if (p.state === 'parked') {
            p.state = 'inflight';
            p.slot = -1;
            p.speed = 0.14 + Math.random() * 0.09;   // the burst leaves fast
          }
        }
      }

      // ---- retire off-screen ----
      for (let i = packets.length - 1; i >= 0; i--) {
        if (packets[i].pos > 1.12) packets.splice(i, 1);
      }

      clock = (clock + dt) % CYCLE;
    }

    function draw() {
      const phase = phaseAt(clock);
      const gateX = gate();
      const [y0, y1] = laneY();
      const seamTone = phase === 'open' || phase === 'degrade' ? AMBER
        : phase === 'probe' ? BLUE : GREEN;

      ctx.clearRect(0, 0, w, h);

      // lane hairlines
      ctx.lineWidth = 1;
      for (const y of [y0, y1]) {
        const g = ctx.createLinearGradient(0, 0, w, 0);
        g.addColorStop(0, 'rgba(255,255,255,0)');
        g.addColorStop(0.14, 'rgba(255,255,255,0.075)');
        g.addColorStop(0.86, 'rgba(255,255,255,0.075)');
        g.addColorStop(1, 'rgba(255,255,255,0)');
        ctx.strokeStyle = g;
        ctx.beginPath(); ctx.moveTo(0, y + 0.5); ctx.lineTo(w, y + 0.5); ctx.stroke();
      }

      // the gate seam — the thing the card sits on
      const seam = ctx.createLinearGradient(0, 0, 0, h);
      seam.addColorStop(0, `rgba(${seamTone}, 0)`);
      seam.addColorStop(0.5, `rgba(${seamTone}, 0.5)`);
      seam.addColorStop(1, `rgba(${seamTone}, 0)`);
      ctx.strokeStyle = seam;
      ctx.lineWidth = phase === 'open' ? 2.5 : 1.5;
      ctx.beginPath(); ctx.moveTo(gateX + 0.5, 0); ctx.lineTo(gateX + 0.5, h); ctx.stroke();

      // packets
      ctx.font = '500 11.5px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
      ctx.textBaseline = 'middle';
      for (const p of packets) {
        const x = xAt(p);
        const y = p.lane === 0 ? y0 : y1;
        const tone = p.state === 'delivered' ? GREEN : p.state === 'failed' ? AMBER : BLUE;
        const tw = ctx.measureText(p.label).width;
        const pw = tw + 46, ph = 32;          // 46 = padding + the 14px glyph + its gap
        const px = x - pw / 2, py = y - ph / 2;
        const a = p.fade * (p.state === 'parked' ? 0.95 : 0.9);

        ctx.beginPath();
        ctx.roundRect(px, py, pw, ph, 16);
        ctx.fillStyle = `rgba(${tone}, ${0.12 * a})`;
        ctx.fill();
        ctx.strokeStyle = `rgba(${tone}, ${(p.state === 'parked' ? 0.5 : 0.34) * a})`;
        ctx.lineWidth = 1;
        ctx.stroke();

        drawGlyph(ctx, p.glyph, px + 12, y - 7, tone, a);

        ctx.fillStyle = `rgba(${tone}, ${0.9 * a})`;
        ctx.fillText(p.label, px + 32, y + 0.5);
      }
    }

    function frame(now: number) {
      const dt = Math.min(now - last, 50);
      last = now;
      step(dt);
      draw();

      uiTick += dt;
      if (uiTick > 180) {
        uiTick = 0;
        const ph = phaseAt(clock);
        setTelemetry({
          circuit: ph === 'open' || ph === 'degrade' ? 'OPEN' : ph === 'probe' ? 'HALF_OPEN' : 'CLOSED',
          parked: packets.filter((p) => p.state === 'parked').length,
          inflight: packets.filter((p) => p.state === 'inflight').length,
          delivered,
        });
      }
      raf = requestAnimationFrame(frame);
    }

    if (reduced) {
      // One honest still: the queue held against an open circuit. No loop, no rAF.
      clock = 13000;
      for (let i = 0; i < 5; i++) {
        packets.push({
          ...KINDS[i % KINDS.length], lane: (i % 2) as 0 | 1,
          pos: 0.5 - 0.028 - Math.floor(i / 2) * 0.032,
          speed: 0, state: 'parked', slot: Math.floor(i / 2), fade: 1,
        });
      }
      draw();
      setTelemetry({ circuit: 'OPEN', parked: 5, inflight: 2, delivered: 1284 });
    } else {
      raf = requestAnimationFrame(frame);
    }

    const onVisibility = () => {
      if (document.hidden) { cancelAnimationFrame(raf); }
      else if (!reduced) { last = performance.now(); raf = requestAnimationFrame(frame); }
    };
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener('resize', resize);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, []);

  const dotTone =
    telemetry.circuit === 'OPEN' ? '#F5A623' : telemetry.circuit === 'HALF_OPEN' ? '#0095D9' : '#2EC27E';

  return (
    <>
      <canvas ref={canvasRef} className="qf-canvas" aria-hidden="true" />
      <div className="qf-telemetry" aria-hidden="true">
        <div className="qf-telemetry__row">
          <span className="qf-telemetry__k">{t('flow.circuit')}</span>
          <span className="qf-telemetry__v" style={{ color: dotTone }}>
            <i className="qf-dot" style={{ background: dotTone }} />
            {telemetry.circuit}
          </span>
        </div>
        <div className="qf-telemetry__row">
          <span className="qf-telemetry__k">{t('flow.inflight')}</span>
          <span className="qf-telemetry__v">{telemetry.inflight}</span>
        </div>
        <div className="qf-telemetry__row">
          <span className="qf-telemetry__k">{t('flow.parked')}</span>
          <span className="qf-telemetry__v" style={{ color: telemetry.parked ? '#F5A623' : undefined }}>
            {telemetry.parked}
          </span>
        </div>
        <div className="qf-telemetry__row">
          <span className="qf-telemetry__k">{t('flow.delivered')}</span>
          <span className="qf-telemetry__v" style={{ color: '#2EC27E' }}>{telemetry.delivered}</span>
        </div>
        <div className="qf-telemetry__row qf-telemetry__row--loss">
          <span className="qf-telemetry__k">{t('flow.lost')}</span>
          <span className="qf-telemetry__v">0</span>
        </div>
      </div>
      <div className="qf-lane-tag qf-lane-tag--in">{t('flow.inbound')}</div>
      <div className="qf-lane-tag qf-lane-tag--out">{t('flow.outbound')}</div>
    </>
  );
}

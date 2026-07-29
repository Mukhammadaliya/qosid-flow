// QosID Flow mark: a gateway wall (signal blue) with a delivered request breaking out
// through its lower-right edge (flow green). That break sits exactly where a Q's tail
// sits, so the mark carries both readings at once — the letter and the mechanic.
//
// An earlier version drew the wall as a circle with a diagonal stem; at a glance it read
// as the Mars/Venus glyph. A rounded SQUARE with a HORIZONTAL tail kills that reading,
// which is why the geometry below is not negotiable at the shape level.
//
// Geometry is defined on a 32-unit grid and scaled, so the SVG here and the favicon in
// public/favicon.svg stay identical. Verified legible down to 24px; at 16px the wall
// still reads and the tail becomes a dot.

type Props = {
  /** Mark height in px. The wordmark scales with it. */
  size?: number;
  /** 'full' = mark + wordmark, 'mark' = mark only (favicon, tight rails). */
  variant?: 'full' | 'mark';
  /** Wordmark colour — pass a light value on dark backgrounds. */
  tone?: string;
};

export function LogoMark({ size = 28 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      role="img"
      aria-label="QosID Flow"
      style={{ display: 'block', flexShrink: 0 }}
    >
      {/* the gateway wall */}
      <rect x="3" y="3" width="24" height="26" rx="7.5" fill="none" stroke="#0095D9" strokeWidth="4.2" />
      {/* the delivered request, breaking through — and the Q's tail */}
      <rect x="19" y="18.6" width="12" height="5.4" rx="2.7" fill="#2EC27E" />
    </svg>
  );
}

export function Logo({ size = 28, variant = 'full', tone = '#16232E' }: Props) {
  if (variant === 'mark') return <LogoMark size={size} />;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: size * 0.36, padding: '0 8px' }}>
      <LogoMark size={size} />
      <span
        style={{
          fontSize: size * 0.64,
          fontWeight: 700,
          color: tone,
          letterSpacing: '-0.02em',
          whiteSpace: 'nowrap',
        }}
      >
        QosID&nbsp;Flow
      </span>
    </div>
  );
}

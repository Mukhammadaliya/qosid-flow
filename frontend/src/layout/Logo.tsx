// QosID Flow mark: a Q drawn as a delivery ring. The ring is the request in flight
// (signal blue); the gap and the tail are where it leaves the system, delivered (flow
// green). It reads as a letter at 20px and as a flow diagram at 200px — the name and the
// mechanic are the same idea.

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
      {/* delivery ring — open at the lower right, where the request exits */}
      <path
        d="M15.74 23.85 A 10 10 0 1 1 23.85 15.74"
        fill="none"
        stroke="#0095D9"
        strokeWidth="4.6"
        strokeLinecap="round"
      />
      {/* the tail: the Q descender, and the delivered request leaving the ring */}
      <path d="M17.2 17.2 L24 24" stroke="#2EC27E" strokeWidth="4.6" strokeLinecap="round" />
      <path d="M29.6 29.6 L22.4 28.2 L28.2 22.4 Z" fill="#2EC27E" />
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

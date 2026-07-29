// Qosid logo: an interlocking "flow" chevron mark (courier/messenger motif) in the
// Brand blue→green pair, next to the "Qosid" wordmark in ink. Iterate on the mark
// during design review; this is a clean first version.
export function Logo() {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '0 8px' }}>
      <svg width="28" height="28" viewBox="0 0 32 32" aria-label="Qosid" role="img">
        <path d="M6 16 L14 8 L14 13 L22 13 L22 19 L14 19 L14 24 Z" fill="#0095D9" />
        <path d="M26 16 L18 24 L18 19 L10 19 L10 13 L18 13 L18 8 Z" fill="#2EC27E" opacity="0.85" />
      </svg>
      <span style={{ fontSize: 18, fontWeight: 700, color: '#1A2333', letterSpacing: 0.2 }}>Qosid</span>
    </div>
  );
}

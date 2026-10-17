// অ্যাপের আইকন (Android vector থেকে বানানো) — <Icon name="call" />
import type { CSSProperties } from 'react';
import { ICONS } from '@/icons/paths';

export type IconName = keyof typeof ICONS | string;

export function Icon({ name, size, className, title, style }: { name: IconName; size?: number; className?: string; title?: string; style?: CSSProperties }) {
  const ic = ICONS[name as string];
  if (!ic) return null;
  const [vb, paths] = ic;
  return (
    <svg
      viewBox={vb}
      width={size}
      height={size}
      fill="currentColor"
      className={className}
      style={style}
      aria-hidden={title ? undefined : true}
      role={title ? 'img' : undefined}
      focusable="false"
    >
      {title ? <title>{title}</title> : null}
      {paths.map((p, i) => (typeof p === 'string' ? <path key={i} d={p} /> : <path key={i} d={p[0]} fillRule="evenodd" />))}
    </svg>
  );
}

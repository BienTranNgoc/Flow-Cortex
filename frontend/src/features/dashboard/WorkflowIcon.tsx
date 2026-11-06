import {
  Image,
  Sparkle,
  ShoppingCart,
  Terminal,
  TiktokLogo,
  VideoCamera,
  Warning,
  Waveform,
  type Icon,
} from '@phosphor-icons/react';
import { useTranslation } from 'react-i18next';
import type { WorkflowIconKey } from './workflowIconKey';

interface IconProps {
  readonly size?: number;
  readonly title?: string;
}

const ICONS: Record<WorkflowIconKey, Icon> = {
  video: VideoCamera,
  tiktok: TiktokLogo,
  terminal: Terminal,
  alert: Warning,
  cart: ShoppingCart,
  audio: Waveform,
  image: Image,
  spark: Sparkle,
};

export function WorkflowIcon({ icon, size = 20, title }: Readonly<{ icon: WorkflowIconKey } & IconProps>) {
  const { t } = useTranslation();
  const Component = ICONS[icon];
  return <Component size={size} role="img" aria-label={title ?? t(`dashboard.icon.${icon}`)} />;
}

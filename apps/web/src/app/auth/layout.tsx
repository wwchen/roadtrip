import type { ReactNode } from 'react';
import './auth.css';

const THEME_CLASS = 'theme-roadtrip-zion auth-page';

export default function AuthLayout({ children }: { children: ReactNode }) {
  return <main className={THEME_CLASS}>{children}</main>;
}

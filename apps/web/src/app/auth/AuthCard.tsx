import { Card } from '@lew-ds/lds-react';
import type { ReactNode } from 'react';

const BRAND = 'Roadtrip';

export interface AuthCardProps {
  title: string;
  lede?: string;
  footer?: ReactNode;
  children: ReactNode;
}

export function AuthCard({ title, lede, footer, children }: AuthCardProps) {
  return (
    <Card className="auth-card" kicker={BRAND} title={<h1 className="auth-title">{title}</h1>} body={lede}>
      <div className="auth-body">{children}</div>
      {footer === undefined ? null : <footer className="auth-footer">{footer}</footer>}
    </Card>
  );
}

export function AuthDivider({ label }: { label: string }) {
  return (
    <p className="auth-divider">
      <span>{label}</span>
    </p>
  );
}

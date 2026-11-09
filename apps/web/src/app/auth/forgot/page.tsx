import Link from 'next/link';
import { RETURN_TO_PARAM, safeReturnTo, withReturnTo } from '@/server/auth/returnTo';
import { requestPasswordReset } from '../actions';
import { AuthForm, type FieldSpec } from '../AuthForm';
import { firstOf, type AuthSearchParams } from '../searchParams';

export const dynamic = 'force-dynamic';

const FIELDS: readonly FieldSpec[] = [{ name: 'email', label: 'Email', type: 'email', autoComplete: 'email' }];

export default async function ForgotPage({ searchParams }: { searchParams: AuthSearchParams }) {
  const returnTo = safeReturnTo(firstOf((await searchParams)[RETURN_TO_PARAM]));
  return (
    <section className="auth-panel">
      <h1>Reset your password</h1>
      <AuthForm action={requestPasswordReset} returnTo={returnTo} submitLabel="Email me a reset link" fields={FIELDS} />
      <nav className="auth-links">
        <Link href={withReturnTo('/auth/sign-in', returnTo)}>Back to sign in</Link>
      </nav>
    </section>
  );
}

import Link from 'next/link';
import { RETURN_TO_PARAM, safeReturnTo, withReturnTo } from '@/server/auth/returnTo';
import { signUp } from '../actions';
import { AuthForm, type FieldSpec } from '../AuthForm';
import { firstOf, type AuthSearchParams } from '../searchParams';

export const dynamic = 'force-dynamic';

const FIELDS: readonly FieldSpec[] = [
  { name: 'name', label: 'Name', type: 'text', autoComplete: 'name' },
  { name: 'email', label: 'Email', type: 'email', autoComplete: 'email' },
  { name: 'password', label: 'Password', type: 'password', autoComplete: 'new-password' },
];

export default async function SignUpPage({ searchParams }: { searchParams: AuthSearchParams }) {
  const returnTo = safeReturnTo(firstOf((await searchParams)[RETURN_TO_PARAM]));
  return (
    <section className="auth-panel">
      <h1>Create an account</h1>
      <AuthForm action={signUp} returnTo={returnTo} submitLabel="Create account" fields={FIELDS} />
      <nav className="auth-links">
        <Link href={withReturnTo('/auth/sign-in', returnTo)}>I have an account</Link>
      </nav>
    </section>
  );
}

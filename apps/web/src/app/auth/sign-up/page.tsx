import { Link } from '@lew-ds/lds-react';
import NextLink from 'next/link';
import { SIGN_IN_PATH } from '@/server/auth/authPaths';
import { RETURN_TO_PARAM, safeReturnTo, withReturnTo } from '@/server/auth/returnTo';
import { signUp } from '../actions';
import { AuthCard } from '../AuthCard';
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
    <AuthCard
      title="Create an account"
      footer={
        <>
          Already have one?{' '}
          <Link as={NextLink} href={withReturnTo(SIGN_IN_PATH, returnTo)}>
            Sign in
          </Link>
        </>
      }
    >
      <AuthForm action={signUp} hidden={{ [RETURN_TO_PARAM]: returnTo }} submitLabel="Create account" fields={FIELDS} />
    </AuthCard>
  );
}

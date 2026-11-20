import { Link } from '@lew-ds/lds-react';
import NextLink from 'next/link';
import { SIGN_IN_PATH } from '@/server/auth/authPaths';
import { RETURN_TO_PARAM, safeReturnTo, withReturnTo } from '@/server/auth/returnTo';
import { requestPasswordReset } from '../actions';
import { AuthCard } from '../AuthCard';
import { AuthForm, type FieldSpec } from '../AuthForm';
import { firstOf, type AuthSearchParams } from '../searchParams';

export const dynamic = 'force-dynamic';

const FIELDS: readonly FieldSpec[] = [{ name: 'email', label: 'Email', type: 'email', autoComplete: 'email' }];

export default async function ForgotPage({ searchParams }: { searchParams: AuthSearchParams }) {
  const returnTo = safeReturnTo(firstOf((await searchParams)[RETURN_TO_PARAM]));
  return (
    <AuthCard
      title="Reset your password"
      lede="We'll email you a link to choose a new one."
      footer={
        <Link as={NextLink} href={withReturnTo(SIGN_IN_PATH, returnTo)}>
          Back to sign in
        </Link>
      }
    >
      <AuthForm action={requestPasswordReset} hidden={{ [RETURN_TO_PARAM]: returnTo }} submitLabel="Email me a reset link" fields={FIELDS} />
    </AuthCard>
  );
}

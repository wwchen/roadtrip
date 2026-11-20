import { Link } from '@lew-ds/lds-react';
import NextLink from 'next/link';
import { ERROR_PARAM, RESET_PARAM, signInNotice } from '@/server/auth/authActions';
import { FORGOT_PATH, SIGN_UP_PATH } from '@/server/auth/authPaths';
import { RETURN_TO_PARAM, safeReturnTo, withReturnTo } from '@/server/auth/returnTo';
import { resendVerification, sendMagicLink, signInWithGoogle, signInWithPassword } from '../actions';
import { AuthCard, AuthDivider } from '../AuthCard';
import { AuthForm, type FieldSpec } from '../AuthForm';
import { firstOf, type AuthSearchParams } from '../searchParams';

export const dynamic = 'force-dynamic';

const EMAIL: FieldSpec = { name: 'email', label: 'Email', type: 'email', autoComplete: 'email' };
const PASSWORD: FieldSpec = { name: 'password', label: 'Password', type: 'password', autoComplete: 'current-password' };
const NO_FIELDS: readonly FieldSpec[] = [];

export default async function SignInPage({ searchParams }: { searchParams: AuthSearchParams }) {
  const params = await searchParams;
  const returnTo = safeReturnTo(firstOf(params[RETURN_TO_PARAM]));
  return (
    <AuthCard
      title="Sign in"
      footer={
        <>
          New here?{' '}
          <Link as={NextLink} href={withReturnTo(SIGN_UP_PATH, returnTo)}>
            Create an account
          </Link>
        </>
      }
    >
      <AuthForm action={signInWithGoogle} hidden={{ [RETURN_TO_PARAM]: returnTo }} submitLabel="Continue with Google" fields={NO_FIELDS} variant="tertiary" />
      <AuthDivider label="or" />
      <AuthForm
        action={signInWithPassword}
        hidden={{ [RETURN_TO_PARAM]: returnTo }}
        submitLabel="Sign in"
        fields={[EMAIL, PASSWORD]}
        message={signInNotice(firstOf(params[ERROR_PARAM]), firstOf(params[RESET_PARAM]))}
        resendAction={resendVerification}
        resendLabel="Send it again"
        alternate={{ action: sendMagicLink, label: 'Email me a sign-in link instead' }}
      />
      <Link as={NextLink} className="auth-aside" href={withReturnTo(FORGOT_PATH, returnTo)}>
        Forgot your password?
      </Link>
    </AuthCard>
  );
}

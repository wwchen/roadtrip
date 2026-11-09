import Link from 'next/link';
import { PASSWORD_CHANGED, RETRY } from '@/server/auth/authCopy';
import { ERROR_PARAM, ERROR_RETRY, RESET_DONE, RESET_PARAM } from '@/server/auth/authActions';
import { FORGOT_PATH, SIGN_UP_PATH } from '@/server/auth/authPaths';
import { RETURN_TO_PARAM, safeReturnTo, withReturnTo } from '@/server/auth/returnTo';
import { resendVerification, sendMagicLink, signInWithGoogle, signInWithPassword } from '../actions';
import { AuthForm, type FieldSpec } from '../AuthForm';
import { firstOf, type AuthSearchParams } from '../searchParams';

export const dynamic = 'force-dynamic';

const EMAIL: FieldSpec = { name: 'email', label: 'Email', type: 'email', autoComplete: 'email' };
const PASSWORD: FieldSpec = { name: 'password', label: 'Password', type: 'password', autoComplete: 'current-password' };
const NO_FIELDS: readonly FieldSpec[] = [];

function noticeFor(params: Record<string, string | string[] | undefined>): string | null {
  if (firstOf(params[ERROR_PARAM]) === ERROR_RETRY) return RETRY;
  if (firstOf(params[RESET_PARAM]) === RESET_DONE) return PASSWORD_CHANGED;
  return null;
}

export default async function SignInPage({ searchParams }: { searchParams: AuthSearchParams }) {
  const params = await searchParams;
  const returnTo = safeReturnTo(firstOf(params[RETURN_TO_PARAM]));
  return (
    <section className="auth-panel">
      <h1>Sign in</h1>
      <AuthForm action={signInWithGoogle} hidden={{ [RETURN_TO_PARAM]: returnTo }} submitLabel="Continue with Google" fields={NO_FIELDS} variant="secondary" />
      <AuthForm
        action={signInWithPassword}
        hidden={{ [RETURN_TO_PARAM]: returnTo }}
        submitLabel="Sign in"
        fields={[EMAIL, PASSWORD]}
        message={noticeFor(params)}
        resendAction={resendVerification}
        resendLabel="Send it again"
      />
      <AuthForm action={sendMagicLink} hidden={{ [RETURN_TO_PARAM]: returnTo }} submitLabel="Email me a link" fields={[EMAIL]} variant="secondary" />
      <nav className="auth-links">
        <Link href={withReturnTo(SIGN_UP_PATH, returnTo)}>Create an account</Link>
        <Link href={withReturnTo(FORGOT_PATH, returnTo)}>Forgot your password?</Link>
      </nav>
    </section>
  );
}

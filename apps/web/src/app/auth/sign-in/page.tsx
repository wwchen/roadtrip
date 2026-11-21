import { ERROR_PARAM, RESET_PARAM, signInNotice } from '@/server/auth/authActions';
import { FORGOT_PATH, SIGN_UP_PATH } from '@/server/auth/authPaths';
import { RETURN_TO_PARAM, safeReturnTo, withReturnTo } from '@/server/auth/returnTo';
import { resendVerification, sendMagicLink, signInWithGoogle, signInWithPassword } from '../actions';
import { SignInCard } from '../AuthViews';
import { firstOf, type AuthSearchParams } from '../searchParams';

export const dynamic = 'force-dynamic';

const ACTIONS = {
  google: signInWithGoogle,
  password: signInWithPassword,
  magicLink: sendMagicLink,
  resend: resendVerification,
};

export default async function SignInPage({ searchParams }: { searchParams: AuthSearchParams }) {
  const params = await searchParams;
  const returnTo = safeReturnTo(firstOf(params[RETURN_TO_PARAM]));
  return (
    <SignInCard
      actions={ACTIONS}
      hidden={{ [RETURN_TO_PARAM]: returnTo }}
      message={signInNotice(firstOf(params[ERROR_PARAM]), firstOf(params[RESET_PARAM]))}
      signUpHref={withReturnTo(SIGN_UP_PATH, returnTo)}
      forgotHref={withReturnTo(FORGOT_PATH, returnTo)}
    />
  );
}

import { SIGN_IN_PATH } from '@/server/auth/authPaths';
import { RETURN_TO_PARAM, safeReturnTo, withReturnTo } from '@/server/auth/returnTo';
import { signUp } from '../actions';
import { SignUpCard } from '../AuthViews';
import { firstOf, type AuthSearchParams } from '../searchParams';

export const dynamic = 'force-dynamic';

export default async function SignUpPage({ searchParams }: { searchParams: AuthSearchParams }) {
  const returnTo = safeReturnTo(firstOf((await searchParams)[RETURN_TO_PARAM]));
  return <SignUpCard action={signUp} hidden={{ [RETURN_TO_PARAM]: returnTo }} signInHref={withReturnTo(SIGN_IN_PATH, returnTo)} />;
}

import { SIGN_IN_PATH } from '@/server/auth/authPaths';
import { RETURN_TO_PARAM, safeReturnTo, withReturnTo } from '@/server/auth/returnTo';
import { requestPasswordReset } from '../actions';
import { ForgotCard } from '../AuthViews';
import { firstOf, type AuthSearchParams } from '../searchParams';

export const dynamic = 'force-dynamic';

export default async function ForgotPage({ searchParams }: { searchParams: AuthSearchParams }) {
  const returnTo = safeReturnTo(firstOf((await searchParams)[RETURN_TO_PARAM]));
  return <ForgotCard action={requestPasswordReset} hidden={{ [RETURN_TO_PARAM]: returnTo }} signInHref={withReturnTo(SIGN_IN_PATH, returnTo)} />;
}

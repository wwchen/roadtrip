import { TOKEN_FIELD } from '@/server/auth/authActions';
import { RETURN_TO_PARAM, safeReturnTo } from '@/server/auth/returnTo';
import { resetPassword } from '../actions';
import { ResetCard } from '../AuthViews';
import { firstOf, type AuthSearchParams } from '../searchParams';

export const dynamic = 'force-dynamic';

const NO_TOKEN = '';

export default async function ResetPage({ searchParams }: { searchParams: AuthSearchParams }) {
  const params = await searchParams;
  const returnTo = safeReturnTo(firstOf(params[RETURN_TO_PARAM]));
  const token = firstOf(params[TOKEN_FIELD]) ?? NO_TOKEN;
  return <ResetCard action={resetPassword} hidden={{ [RETURN_TO_PARAM]: returnTo, [TOKEN_FIELD]: token }} />;
}

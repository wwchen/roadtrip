import { TOKEN_FIELD } from '@/server/auth/authActions';
import { RETURN_TO_PARAM, safeReturnTo } from '@/server/auth/returnTo';
import { resetPassword } from '../actions';
import { AuthForm, type FieldSpec } from '../AuthForm';
import { firstOf, type AuthSearchParams } from '../searchParams';

export const dynamic = 'force-dynamic';

const FIELDS: readonly FieldSpec[] = [
  { name: 'password', label: 'New password', type: 'password', autoComplete: 'new-password' },
];
const NO_TOKEN = '';

export default async function ResetPage({ searchParams }: { searchParams: AuthSearchParams }) {
  const params = await searchParams;
  const returnTo = safeReturnTo(firstOf(params[RETURN_TO_PARAM]));
  const token = firstOf(params[TOKEN_FIELD]) ?? NO_TOKEN;
  return (
    <section className="auth-panel">
      <h1>Choose a new password</h1>
      <AuthForm action={resetPassword} submitLabel="Save password" fields={FIELDS} hidden={{ [RETURN_TO_PARAM]: returnTo, [TOKEN_FIELD]: token }} />
    </section>
  );
}

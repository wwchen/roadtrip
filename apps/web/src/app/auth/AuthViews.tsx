import { Link } from '@lew-ds/lds-react';
import NextLink from 'next/link';
import { AuthCard, AuthDivider } from './AuthCard';
import { AuthForm, type FieldSpec, type FormAction } from './AuthForm';

type HiddenFields = Readonly<Record<string, string>>;

const EMAIL: FieldSpec = { name: 'email', label: 'Email', type: 'email', autoComplete: 'email' };
const CURRENT_PASSWORD: FieldSpec = { name: 'password', label: 'Password', type: 'password', autoComplete: 'current-password' };
const NO_FIELDS: readonly FieldSpec[] = [];
const SIGN_UP_FIELDS: readonly FieldSpec[] = [
  { name: 'name', label: 'Name', type: 'text', autoComplete: 'name' },
  EMAIL,
  { name: 'password', label: 'Password', type: 'password', autoComplete: 'new-password' },
];
const FORGOT_FIELDS: readonly FieldSpec[] = [EMAIL];
const RESET_FIELDS: readonly FieldSpec[] = [
  { name: 'password', label: 'New password', type: 'password', autoComplete: 'new-password' },
];

export interface SignInActions {
  google: FormAction;
  password: FormAction;
  magicLink: FormAction;
  resend: FormAction;
}

export interface SignInCardProps {
  actions: SignInActions;
  hidden: HiddenFields;
  message: string | null;
  signUpHref: string;
  forgotHref: string;
}

export function SignInCard({ actions, hidden, message, signUpHref, forgotHref }: SignInCardProps) {
  return (
    <AuthCard
      title="Sign in"
      footer={
        <>
          New here?{' '}
          <Link as={NextLink} href={signUpHref}>
            Create an account
          </Link>
        </>
      }
    >
      <AuthForm action={actions.google} hidden={hidden} submitLabel="Continue with Google" fields={NO_FIELDS} variant="tertiary" />
      <AuthDivider label="or" />
      <AuthForm
        action={actions.password}
        hidden={hidden}
        submitLabel="Sign in"
        fields={[EMAIL, CURRENT_PASSWORD]}
        message={message}
        resendAction={actions.resend}
        resendLabel="Send it again"
        alternate={{ action: actions.magicLink, label: 'Email me a sign-in link instead' }}
      />
      <Link as={NextLink} className="auth-aside" href={forgotHref}>
        Forgot your password?
      </Link>
    </AuthCard>
  );
}

export interface SignUpCardProps {
  action: FormAction;
  hidden: HiddenFields;
  signInHref: string;
}

export function SignUpCard({ action, hidden, signInHref }: SignUpCardProps) {
  return (
    <AuthCard
      title="Create an account"
      footer={
        <>
          Already have one?{' '}
          <Link as={NextLink} href={signInHref}>
            Sign in
          </Link>
        </>
      }
    >
      <AuthForm action={action} hidden={hidden} submitLabel="Create account" fields={SIGN_UP_FIELDS} />
    </AuthCard>
  );
}

export interface ForgotCardProps {
  action: FormAction;
  hidden: HiddenFields;
  signInHref: string;
}

export function ForgotCard({ action, hidden, signInHref }: ForgotCardProps) {
  return (
    <AuthCard
      title="Reset your password"
      lede="We'll email you a link to choose a new one."
      footer={
        <Link as={NextLink} href={signInHref}>
          Back to sign in
        </Link>
      }
    >
      <AuthForm action={action} hidden={hidden} submitLabel="Email me a reset link" fields={FORGOT_FIELDS} />
    </AuthCard>
  );
}

export interface ResetCardProps {
  action: FormAction;
  hidden: HiddenFields;
}

export function ResetCard({ action, hidden }: ResetCardProps) {
  return (
    <AuthCard title="Choose a new password">
      <AuthForm action={action} hidden={hidden} submitLabel="Save password" fields={RESET_FIELDS} />
    </AuthCard>
  );
}

import type { Meta, StoryObj } from '@storybook/react-vite';
import {
  MAGIC_LINK_SENT,
  PASSWORD_CHANGED,
  PASSWORD_TOO_SHORT_MESSAGE,
  RESET_LINK_SENT,
  VERIFY_EMAIL,
  WRONG_PASSWORD,
} from '../../server/auth/authCopy';
import type { AuthFormState, FormAction } from './AuthForm';
import { ForgotCard, ResetCard, SignInCard, SignUpCard, type SignInActions } from './AuthViews';
import './auth.css';

const PAGE_CLASS = 'theme-roadtrip-zion auth-page';
const RETURN_TO = { return_to: '/' };
const SIGN_IN_HREF = '/auth/sign-in?return_to=/';
const SIGN_UP_HREF = '/auth/sign-up?return_to=/';
const FORGOT_HREF = '/auth/forgot?return_to=/';
const NO_MESSAGE = null;

function answers(message: string | null, canResendVerification = false): FormAction {
  const state: AuthFormState = { message, canResendVerification };
  return async () => state;
}

const SIGN_IN_ACTIONS: SignInActions = {
  google: answers(NO_MESSAGE),
  password: answers(WRONG_PASSWORD),
  magicLink: answers(MAGIC_LINK_SENT),
  resend: answers(VERIFY_EMAIL),
};

const meta = {
  title: 'Auth/Pages',
  decorators: [
    (Story) => (
      <main className={PAGE_CLASS}>
        <Story />
      </main>
    ),
  ],
  parameters: {
    colorMode: 'light',
    docs: {
      description: {
        component:
          'The sign-in, sign-up, forgot and reset pages from apps/web. Each submit answers with a canned state instead of calling Neon Auth.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const SignIn: Story = {
  render: () => (
    <SignInCard actions={SIGN_IN_ACTIONS} hidden={RETURN_TO} message={NO_MESSAGE} signUpHref={SIGN_UP_HREF} forgotHref={FORGOT_HREF} />
  ),
};

export const SignInAfterPasswordChange: Story = {
  render: () => (
    <SignInCard actions={SIGN_IN_ACTIONS} hidden={RETURN_TO} message={PASSWORD_CHANGED} signUpHref={SIGN_UP_HREF} forgotHref={FORGOT_HREF} />
  ),
};

export const SignInUnverified: Story = {
  render: () => (
    <SignInCard
      actions={{ ...SIGN_IN_ACTIONS, password: answers(VERIFY_EMAIL, true) }}
      hidden={RETURN_TO}
      message={NO_MESSAGE}
      signUpHref={SIGN_UP_HREF}
      forgotHref={FORGOT_HREF}
    />
  ),
};

export const SignUp: Story = {
  render: () => <SignUpCard action={answers(VERIFY_EMAIL)} hidden={RETURN_TO} signInHref={SIGN_IN_HREF} />,
};

export const ForgotPassword: Story = {
  render: () => <ForgotCard action={answers(RESET_LINK_SENT)} hidden={RETURN_TO} signInHref={SIGN_IN_HREF} />,
};

export const ResetPassword: Story = {
  render: () => <ResetCard action={answers(PASSWORD_TOO_SHORT_MESSAGE)} hidden={RETURN_TO} />,
};

'use server';

import { redirect } from 'next/navigation';
import {
  requestPasswordResetAction,
  resendVerificationAction,
  resetPasswordAction,
  sendMagicLinkAction,
  signInWithGoogleAction,
  signInWithPasswordAction,
  signUpAction,
  type AuthAction,
  type FormState,
} from '@/server/auth/authActions';
import { authPort } from '@/server/auth/neonAuth';

async function run(action: AuthAction, form: FormData): Promise<FormState> {
  const outcome = await action(authPort(), form);
  if (outcome.kind === 'redirect') redirect(outcome.url);
  return outcome.state;
}

export async function signInWithPassword(_previous: FormState, form: FormData): Promise<FormState> {
  return run(signInWithPasswordAction, form);
}

export async function signInWithGoogle(_previous: FormState, form: FormData): Promise<FormState> {
  return run(signInWithGoogleAction, form);
}

export async function sendMagicLink(_previous: FormState, form: FormData): Promise<FormState> {
  return run(sendMagicLinkAction, form);
}

export async function signUp(_previous: FormState, form: FormData): Promise<FormState> {
  return run(signUpAction, form);
}

export async function resendVerification(_previous: FormState, form: FormData): Promise<FormState> {
  return run(resendVerificationAction, form);
}

export async function requestPasswordReset(_previous: FormState, form: FormData): Promise<FormState> {
  return run(requestPasswordResetAction, form);
}

export async function resetPassword(_previous: FormState, form: FormData): Promise<FormState> {
  return run(resetPasswordAction, form);
}

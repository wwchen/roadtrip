'use client';

import { Button, TextField } from '@lew-ds/lds-react';
import { useActionState } from 'react';
import type { FormState } from '@/server/auth/authActions';
import { RETURN_TO_PARAM } from '@/server/auth/returnTo';

export type FormAction = (previous: FormState, form: FormData) => Promise<FormState>;

export interface FieldSpec {
  name: string;
  label: string;
  type: string;
  autoComplete: string;
}

export interface AuthFormProps {
  action: FormAction;
  returnTo: string;
  submitLabel: string;
  fields: readonly FieldSpec[];
  hidden?: Readonly<Record<string, string>>;
  message?: string | null;
  resendAction?: FormAction;
  resendLabel?: string;
  variant?: 'primary' | 'secondary';
}

const EMPTY_STATE: FormState = { message: null, canResendVerification: false };
const EMAIL_FIELD: FieldSpec = { name: 'email', label: 'Email', type: 'email', autoComplete: 'email' };

export function AuthForm({
  action,
  returnTo,
  submitLabel,
  fields,
  hidden = {},
  message = null,
  resendAction,
  resendLabel,
  variant = 'primary',
}: AuthFormProps) {
  const [state, formAction, pending] = useActionState(action, { ...EMPTY_STATE, message });
  return (
    <>
      <form action={formAction} className="auth-form">
        <input type="hidden" name={RETURN_TO_PARAM} value={returnTo} />
        {Object.entries(hidden).map(([name, value]) => (
          <input key={name} type="hidden" name={name} value={value} />
        ))}
        {fields.map((field) => (
          <TextField
            key={field.name}
            id={`auth-${field.name}`}
            name={field.name}
            label={field.label}
            type={field.type}
            autoComplete={field.autoComplete}
            required
          />
        ))}
        <Button type="submit" variant={variant} disabled={pending}>
          {submitLabel}
        </Button>
        {state.message === null ? null : (
          <p className="auth-message" role="status">
            {state.message}
          </p>
        )}
      </form>
      {state.canResendVerification && resendAction !== undefined && resendLabel !== undefined ? (
        <AuthForm action={resendAction} returnTo={returnTo} submitLabel={resendLabel} fields={[EMAIL_FIELD]} variant="secondary" />
      ) : null}
    </>
  );
}

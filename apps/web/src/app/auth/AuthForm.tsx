'use client';

import { Button, TextField } from '@lew-ds/lds-react';
import { useActionState, useId } from 'react';
export interface AuthFormState {
  message: string | null;
  canResendVerification: boolean;
}

export type FormAction = (previous: AuthFormState, form: FormData) => Promise<AuthFormState>;

export interface FieldSpec {
  name: string;
  label: string;
  type: string;
  autoComplete: string;
}

export interface AuthFormProps {
  action: FormAction;
  submitLabel: string;
  fields: readonly FieldSpec[];
  hidden: Readonly<Record<string, string>>;
  message?: string | null;
  resendAction?: FormAction;
  resendLabel?: string;
  variant?: 'primary' | 'secondary';
}

const EMPTY_STATE: AuthFormState = { message: null, canResendVerification: false };
const EMAIL_FIELD: FieldSpec = { name: 'email', label: 'Email', type: 'email', autoComplete: 'email' };

export function AuthForm({
  action,
  submitLabel,
  fields,
  hidden,
  message = null,
  resendAction,
  resendLabel,
  variant = 'primary',
}: AuthFormProps) {
  const [state, formAction, pending] = useActionState(action, { ...EMPTY_STATE, message });
  const formId = useId();
  return (
    <>
      <form action={formAction} className="auth-form">
        {Object.entries(hidden).map(([name, value]) => (
          <input key={name} type="hidden" name={name} value={value} />
        ))}
        {fields.map((field) => (
          <TextField
            key={field.name}
            id={`${formId}-${field.name}`}
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
        <AuthForm action={resendAction} hidden={hidden} submitLabel={resendLabel} fields={[EMAIL_FIELD]} variant="secondary" />
      ) : null}
    </>
  );
}

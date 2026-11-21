'use client';

import { Button, ButtonGroup, TextField } from '@lew-ds/lds-react';
import { useActionState, useId, useState, type FormEvent } from 'react';
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

export interface AlternateSubmit {
  action: FormAction;
  label: string;
}

export interface AuthFormProps {
  action: FormAction;
  submitLabel: string;
  fields: readonly FieldSpec[];
  hidden: Readonly<Record<string, string>>;
  message?: string | null;
  resendAction?: FormAction;
  resendLabel?: string;
  alternate?: AlternateSubmit;
  variant?: 'primary' | 'secondary' | 'tertiary';
}

type Submitter = 'main' | 'alternate';

const EMPTY_STATE: AuthFormState = { message: null, canResendVerification: false };
const EMAIL_FIELD: FieldSpec = { name: 'email', label: 'Email', type: 'email', autoComplete: 'email' };
const UNUSED_ALTERNATE: FormAction = async (previous) => previous;
const STRONG = 'strong';
const MAIN: Submitter = 'main';
const ALTERNATE: Submitter = 'alternate';

function submitterOf(event: FormEvent<HTMLFormElement>): Submitter {
  const { submitter } = event.nativeEvent as SubmitEvent;
  return submitter instanceof HTMLElement && submitter.dataset.submitter === ALTERNATE ? ALTERNATE : MAIN;
}

export function AuthForm({
  action,
  submitLabel,
  fields,
  hidden,
  message = null,
  resendAction,
  resendLabel,
  alternate,
  variant = 'primary',
}: AuthFormProps) {
  const [state, formAction, pending] = useActionState(action, { ...EMPTY_STATE, message });
  const [alternateState, alternateAction, alternatePending] = useActionState(alternate?.action ?? UNUSED_ALTERNATE, EMPTY_STATE);
  const [lastSubmitter, setLastSubmitter] = useState<Submitter>(MAIN);
  const formId = useId();
  const busy = pending || alternatePending;
  const shown = lastSubmitter === ALTERNATE ? alternateState : state;
  return (
    <>
      <form action={formAction} className="auth-form" onSubmit={(event) => setLastSubmitter(submitterOf(event))}>
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
        <ButtonGroup orientation="vertical" width="fill">
          <Button
            type="submit"
            emphasis={variant === 'primary' ? STRONG : undefined}
            variant={variant === 'primary' ? undefined : variant}
            disabled={busy}
          >
            {submitLabel}
          </Button>
          {alternate === undefined ? null : (
            <Button
              type="submit"
              variant="secondary"
              formAction={alternateAction}
              formNoValidate
              disabled={busy}
              data-submitter={ALTERNATE}
            >
              {alternate.label}
            </Button>
          )}
        </ButtonGroup>
        {shown.message === null ? null : (
          <p className="auth-message" role="status">
            {shown.message}
          </p>
        )}
      </form>
      {lastSubmitter === MAIN && state.canResendVerification && resendAction !== undefined && resendLabel !== undefined ? (
        <AuthForm action={resendAction} hidden={hidden} submitLabel={resendLabel} fields={[EMAIL_FIELD]} variant="secondary" />
      ) : null}
    </>
  );
}

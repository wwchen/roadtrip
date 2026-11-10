import { renderToStaticMarkup } from 'react-dom/server';
import { expect, test } from 'vitest';
import { AuthForm, type AuthFormState, type FieldSpec } from './AuthForm';

const EMAIL: FieldSpec = { name: 'email', label: 'Email', type: 'email', autoComplete: 'email' };
const idle = async (state: AuthFormState) => state;

test('two forms on one page give their fields distinct ids, each label pointing at its own input', () => {
  const html = renderToStaticMarkup(
    <>
      <AuthForm action={idle} hidden={{ return_to: "/" }} submitLabel="Sign in" fields={[EMAIL]} />
      <AuthForm action={idle} hidden={{ return_to: "/" }} submitLabel="Email me a link" fields={[EMAIL]} />
    </>,
  );
  const inputIds = [...html.matchAll(/<input[^>]*\sid="([^"]+)"/g)].map((match) => match[1]);
  const labelTargets = [...html.matchAll(/<label[^>]*\sfor="([^"]+)"/g)].map((match) => match[1]);
  expect(inputIds).toHaveLength(2);
  expect(new Set(inputIds).size).toBe(2);
  expect(labelTargets).toEqual(inputIds);
});

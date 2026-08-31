import type { ButtonHTMLAttributes } from 'react';
import './link-button.css';

export type LinkButtonProps = ButtonHTMLAttributes<HTMLButtonElement>;

/**
 * A button that reads as a link, for an action inside a sentence.
 *
 * "Couldn't load sites · **Retry**", "checked 4m ago · **refresh**". The word is
 * part of the prose, so it takes the prose's colour and metrics and only the
 * button chrome is stripped — see `link-button.css` for why neither `Button` nor
 * `Link` from LDS can be this.
 *
 * `type` defaults to `button`: every one of these lives in a paragraph today, but
 * the default is `submit`, and the first one placed inside a form would otherwise
 * submit it.
 */
export function LinkButton({ className, type = 'button', ...props }: LinkButtonProps) {
  return (
    <button
      {...props}
      type={type}
      className={className ? `rt-link-button ${className}` : 'rt-link-button'}
    />
  );
}

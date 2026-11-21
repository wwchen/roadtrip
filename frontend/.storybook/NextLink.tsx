import type { AnchorHTMLAttributes } from 'react';

type NextLinkProps = AnchorHTMLAttributes<HTMLAnchorElement> & { href: string };

export default function NextLink(props: NextLinkProps) {
  return <a {...props} />;
}

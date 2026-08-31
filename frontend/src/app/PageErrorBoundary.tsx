// The last line between a throwing component and a white page.
//
// jsdom cannot see a page that fails to mount, so a render-time throw is exactly
// the class of bug the suite is structurally blind to — it reaches a user as an
// empty document with nothing but a console trace. One boundary above every page
// (mounted by `AppProviders`, so all four entries get it) turns that into a
// banner the user can act on.
//
// A class component because React has no hook equivalent: `getDerivedStateFromError`
// and `componentDidCatch` are the only way to catch a descendant's throw.
import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Banner, Button } from '@ui';

export interface PageErrorBoundaryProps {
  children: ReactNode;
}

interface PageErrorBoundaryState {
  failed: boolean;
}

const FALLBACK_TITLE = 'Something went wrong';
const FALLBACK_BODY = "This page hit an error it couldn't recover from. Reloading usually clears it.";

export class PageErrorBoundary extends Component<PageErrorBoundaryProps, PageErrorBoundaryState> {
  state: PageErrorBoundaryState = { failed: false };

  static getDerivedStateFromError(): PageErrorBoundaryState {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // The component stack is the half a bare stack trace does not carry, and it
    // is what names the component that threw.
    console.error('page render failed:', error, info.componentStack);
  }

  render(): ReactNode {
    if (!this.state.failed) return this.props.children;

    return (
      // `.shell` from `app/shell.css`, the same centred column every page uses,
      // so the fallback needs no stylesheet of its own.
      <div className="shell">
        <Banner
          status="error"
          title={FALLBACK_TITLE}
          role="alert"
          actions={
            <Button variant="secondary" size="sm" iconStart="refresh" onClick={() => window.location.reload()}>
              Reload the page
            </Button>
          }
        >
          <p>{FALLBACK_BODY}</p>
        </Banner>
      </div>
    );
  }
}

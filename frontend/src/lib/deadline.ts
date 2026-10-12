// A time limit on an abortable task, measured as silence: the task is cut off
// when it has gone too long without reporting progress, however long it runs in
// all. For work whose total length is legitimately open-ended — a stream that
// answers many things in turn — but which should never go quiet.

/** The task went quiet for longer than its limit, as opposed to its caller cancelling it. */
export class DeadlineExceeded extends Error {
  constructor() {
    super('deadline exceeded');
    this.name = 'DeadlineExceeded';
  }
}

/**
 * [task], given a signal that aborts when [parent] does or once [idleMs] pass
 * without the task calling `alive()`; each call restarts the wait.
 *
 * Rejects with `DeadlineExceeded` when it was the silence that ran out, and
 * with the task's own error otherwise — a cancel through [parent] included. The
 * timer is cleared however the task ends.
 */
export async function withIdleTimeout<T>(
  task: (signal: AbortSignal, alive: () => void) => Promise<T>,
  parent: AbortSignal,
  idleMs: number,
): Promise<T> {
  const controller = new AbortController();
  let expired = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const arm = (): void => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      expired = true;
      controller.abort();
    }, idleMs);
  };
  const cancel = (): void => controller.abort();
  parent.addEventListener('abort', cancel);
  arm();
  try {
    return await task(controller.signal, arm);
  } catch (error) {
    throw expired && !parent.aborted ? new DeadlineExceeded() : error;
  } finally {
    clearTimeout(timer);
    parent.removeEventListener('abort', cancel);
  }
}

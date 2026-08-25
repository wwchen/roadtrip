// `?watch=<id>&t=<token>`, built by `WatchNotificationTargetResolver`. These
// URLs sit in mailboxes and never expire, so neither param can be renamed.
import {
  MAGIC_LINK_ACTION_PARAM,
  MAGIC_LINK_STOP_ACTION,
  MAGIC_LINK_TOKEN_PARAM,
  MAGIC_LINK_WATCH_PARAM,
} from '@/api/watches-api';


export interface MagicLink {
  watchId: string;
  token: string;
  /** True for the email's "Stop watch" link, which the page carries out on arrival. */
  stopOnArrival: boolean;
}

/**
 * Both halves required: an id alone is an ordinary signed-in deep link. Nothing
 * strips the query afterwards — the token is the page's only credential.
 */
export function readMagicLink(search: string): MagicLink | null {
  const params = new URLSearchParams(search);
  const watchId = params.get(MAGIC_LINK_WATCH_PARAM);
  const token = params.get(MAGIC_LINK_TOKEN_PARAM);
  if (!watchId || !token) return null;
  return {
    watchId,
    token,
    stopOnArrival: params.get(MAGIC_LINK_ACTION_PARAM) === MAGIC_LINK_STOP_ACTION,
  };
}

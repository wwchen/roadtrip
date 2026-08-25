import '@ui/styles.css';
import { initWatchLink, stripWatchTokenFromUrl } from '@/api/watch-link';
import { mountPage } from '@/app/mount';
import { WatchesPage } from './WatchesPage';

// Read the alert-email magic link before anything renders, and take the token
// out of the address bar immediately — it is a bearer credential, and every
// extra moment it spends in the URL is another place it can be copied to.
initWatchLink(window.location.search);
stripWatchTokenFromUrl();

mountPage(<WatchesPage />);

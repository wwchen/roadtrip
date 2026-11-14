import { currentAppUser } from '../auth/currentUser';
import { signInConfigured } from '../auth/signInConfigured';
import { readerStanding } from '../service/booking/addToCartCapability';
import type { ReaderStanding } from '../service/booking/model';
import type { ReaderPorts } from '../service/booking/ports';
import { liveVault } from './bookingAdapters';

const vaultOpenedOnUse: ReaderPorts['vault'] = { status: (userId) => liveVault().status(userId) };

export function liveReaderStanding(): Promise<ReaderStanding> {
  return readerStanding({ signInConfigured, currentUser: currentAppUser, vault: vaultOpenedOnUse });
}

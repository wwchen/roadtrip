import { currentAppUser } from '../auth/currentUser';
import { signedInRoutes } from '../mapper/http/signedIn';

export const signedIn = signedInRoutes(currentAppUser);

import '@ui/styles.css';
import { loadClientConfig } from '@/api/client-config-api';
import { mountPage } from '@/app/mount';
import { MapPage } from './MapPage';

void loadClientConfig()
  .catch(() => undefined)
  .finally(() => mountPage(<MapPage />));

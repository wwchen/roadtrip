import { mount as mountJobs } from '/web/components/availability/jobs-tab.js';
import { mount as mountRuns } from '/web/components/availability/runs-tab.js';
import { mount as mountSnapshots } from '/web/components/availability/snapshots-tab.js';

const TABS = {
  jobs: mountJobs,
  runs: mountRuns,
  snapshots: mountSnapshots,
};

const tabRoot = document.getElementById('tab-root');
const tabLinks = document.querySelectorAll('.tabs a[data-tab]');

tabLinks.forEach((a) => {
  a.addEventListener('click', (e) => {
    e.preventDefault();
    const tab = a.dataset.tab;
    setTab(tab, {});
  });
});

const initial = readUrlState();
setTab(initial.tab, initial.params);

function readUrlState() {
  const qs = new URLSearchParams(window.location.search);
  const tab = qs.get('tab') || 'jobs';
  const params = {};
  for (const [k, v] of qs) {
    if (k !== 'tab') params[k] = v;
  }
  return { tab: TABS[tab] ? tab : 'jobs', params };
}

function setTab(tab, params) {
  if (!TABS[tab]) return;
  const qs = new URLSearchParams({ tab });
  for (const [k, v] of Object.entries(params)) {
    if (v != null && v !== '') qs.set(k, v);
  }
  window.history.replaceState(null, '', `/availability?${qs}`);
  tabLinks.forEach((a) => {
    if (a.dataset.tab === tab) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  });
  tabRoot.innerHTML = '';
  TABS[tab](tabRoot, {
    onTabSwitch: (nextTab, nextParams) => setTab(nextTab, nextParams || {}),
    urlParams: params,
  });
}

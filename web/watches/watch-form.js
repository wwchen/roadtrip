import { mountFormSection } from '../design-system/form-section.js';
import { mountTriggerSelector } from './trigger-selector.js';
import { watchFormTemplate } from './watch-form-template.js';
import {
  TRIGGER_KIND_SLACK_NOTIFY,
  TRIGGER_KIND_EMAIL_NOTIFY,
  watchHasTrigger,
  watchEmailTo,
  watchSlackChannel,
  watchStopWhenTriggered,
} from '../availability/watch-editor.js';

const STYLE_ID = 'rt-watch-form-styles';

export function mountWatchForm(container, config) {
  injectStyles();
  let mode = config.mode || 'create';
  let watch = config.watch || null;
  let loading = false;
  let error = null;
  let editingId = null;
  const children = [];

  function render() {
    container.innerHTML = '';
    children.forEach((c) => c.dispose());
    children.length = 0;

    container.innerHTML = watchFormTemplate({ mode, error, loading });

    const poiHost = container.querySelector('[data-field="poi_id"]');
    const startHost = container.querySelector('[data-field="start_date"]');
    const endHost = container.querySelector('[data-field="end_date"]');
    const triggerHost = container.querySelector('[data-field="triggers"]');

    const poiField = mountFormSection(poiHost, {
      label: 'POI ID',
      name: 'poi_id',
      placeholder: 'e.g. 42',
      value: watch?.poi_id != null ? String(watch.poi_id) : '',
      disabled: loading || mode === 'edit',
    });
    children.push(poiField);

    const startField = mountFormSection(startHost, {
      label: 'Start date',
      name: 'start_date',
      type: 'date',
      value: watch?.start_date || '',
      disabled: loading || mode === 'edit',
    });
    children.push(startField);

    const endField = mountFormSection(endHost, {
      label: 'End date',
      name: 'end_date',
      type: 'date',
      value: watch?.end_date || '',
      disabled: loading || mode === 'edit',
    });
    children.push(endField);

    const triggerSelector = mountTriggerSelector(triggerHost, {
      slackEnabled: watch ? watchHasTrigger(watch, TRIGGER_KIND_SLACK_NOTIFY) : true,
      slackChannel: watch ? watchSlackChannel(watch) : '',
      emailEnabled: watch ? watchHasTrigger(watch, TRIGGER_KIND_EMAIL_NOTIFY) : false,
      emailTo: watch ? watchEmailTo(watch) : '',
      stopWhenTriggered: watchStopWhenTriggered(watch, true),
      disabled: loading,
    });
    children.push(triggerSelector);

    container.querySelector('.rt-watch-form-submit')?.addEventListener('click', () => {
      const triggerState = triggerSelector.getState();
      const triggerKinds = [];
      const triggerConfig = {};
      if (triggerState.slackEnabled) {
        triggerKinds.push(TRIGGER_KIND_SLACK_NOTIFY);
        const channel = (triggerState.slackChannel || '').trim();
        if (channel) triggerConfig[TRIGGER_KIND_SLACK_NOTIFY] = { channel };
      }
      if (triggerState.emailEnabled) {
        triggerKinds.push(TRIGGER_KIND_EMAIL_NOTIFY);
        const to = triggerState.emailTo.trim();
        if (to) triggerConfig[TRIGGER_KIND_EMAIL_NOTIFY] = { to };
      }
      const payload = {
        poi_id: poiField.getValue(),
        start_date: startField.getValue(),
        end_date: endField.getValue(),
        trigger_kinds: triggerKinds,
        trigger_config: triggerConfig,
        stop_when_triggered: triggerState.stopWhenTriggered,
      };
      if (mode === 'edit' && watch?.status === 'done') {
        payload.status = 'active';
      }
      config.onSubmit(payload);
    });

    container.querySelector('.rt-watch-form-cancel')?.addEventListener('click', () => {
      config.onCancel?.();
    });
  }

  render();

  return {
    getEditingId() { return editingId; },
    setMode(newMode, newWatch) {
      mode = newMode;
      watch = newWatch || null;
      editingId = newWatch?.id || null;
      error = null;
      render();
    },
    setLoading(val) {
      loading = val;
      render();
    },
    setError(msg) {
      error = msg || null;
      render();
    },
    dispose() {
      children.forEach((c) => c.dispose());
      children.length = 0;
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/watches/watch-form.css';
  document.head.appendChild(link);
}

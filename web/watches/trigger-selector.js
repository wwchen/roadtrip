import { mountToggleSwitch } from '../design-system/toggle-switch.js';
import { mountFormSection } from '../design-system/form-section.js';

export function mountTriggerSelector(container, config) {
  let state = {
    slackEnabled: config.slackEnabled ?? true,
    slackChannel: config.slackChannel || '',
    emailEnabled: config.emailEnabled ?? false,
    emailTo: config.emailTo || '',
    stopWhenTriggered: config.stopWhenTriggered ?? true,
  };
  const children = [];

  function render() {
    container.innerHTML = '';
    children.forEach((c) => c.dispose());
    children.length = 0;

    const slackHost = document.createElement('div');
    const slackFieldHost = document.createElement('div');
    const emailHost = document.createElement('div');
    const emailFieldHost = document.createElement('div');
    const stopHost = document.createElement('div');
    container.appendChild(slackHost);
    container.appendChild(slackFieldHost);
    container.appendChild(emailHost);
    container.appendChild(emailFieldHost);
    container.appendChild(stopHost);

    children.push(mountToggleSwitch(slackHost, {
      name: 'slack_notify',
      label: 'Slack',
      help: 'Post when a matching site opens.',
      checked: state.slackEnabled,
      disabled: config.disabled,
      onChange(checked) {
        state = { ...state, slackEnabled: checked };
        config.onChange?.(state);
        render();
      },
    }));

    if (state.slackEnabled) {
      const channelInput = mountFormSection(slackFieldHost, {
        label: 'Channel',
        name: 'slack_channel',
        type: 'text',
        placeholder: '#alerts',
        value: state.slackChannel,
        disabled: config.disabled,
      });
      slackFieldHost.addEventListener('input', (e) => {
        if (e.target.name === 'slack_channel') {
          state = { ...state, slackChannel: e.target.value };
          config.onChange?.(state);
        }
      });
      children.push(channelInput);
    }

    children.push(mountToggleSwitch(emailHost, {
      name: 'email_notify',
      label: 'Email',
      help: 'Send email when a matching site opens.',
      checked: state.emailEnabled,
      disabled: config.disabled,
      onChange(checked) {
        state = { ...state, emailEnabled: checked };
        config.onChange?.(state);
        render();
      },
    }));

    if (state.emailEnabled) {
      const emailInput = mountFormSection(emailFieldHost, {
        label: 'Email address',
        name: 'email_to',
        type: 'text',
        placeholder: 'you@example.com, other@example.com',
        value: state.emailTo,
        disabled: config.disabled,
      });
      emailFieldHost.addEventListener('input', (e) => {
        if (e.target.name === 'email_to') {
          state = { ...state, emailTo: e.target.value };
          config.onChange?.(state);
        }
      });
      children.push(emailInput);
    }

    children.push(mountToggleSwitch(stopHost, {
      name: 'stop_when_triggered',
      label: 'Stop when triggered',
      help: 'Mark done after a successful trigger.',
      checked: state.stopWhenTriggered,
      disabled: config.disabled,
      onChange(checked) {
        state = { ...state, stopWhenTriggered: checked };
        config.onChange?.(state);
      },
    }));
  }

  render();

  return {
    getState() { return { ...state }; },
    update(newConfig) {
      if (newConfig.slackEnabled != null) state.slackEnabled = newConfig.slackEnabled;
      if (newConfig.slackChannel != null) state.slackChannel = newConfig.slackChannel;
      if (newConfig.emailEnabled != null) state.emailEnabled = newConfig.emailEnabled;
      if (newConfig.emailTo != null) state.emailTo = newConfig.emailTo;
      if (newConfig.stopWhenTriggered != null) state.stopWhenTriggered = newConfig.stopWhenTriggered;
      if (newConfig.disabled != null) config.disabled = newConfig.disabled;
      render();
    },
    dispose() {
      children.forEach((c) => c.dispose());
      children.length = 0;
      container.innerHTML = '';
    },
  };
}

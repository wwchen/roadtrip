(function (root) {
  const Campsite = root.Campsite || {};
  const { api, el, showToast } = Campsite;
  const MASKED = '\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022';

  function createCampsiteAlertForms({ clearCampground, loadAlerts, selectedCampgrounds }) {
    function initControls() {
      el('alert-form')?.addEventListener('submit', submitAlertForm);
      el('edit-close')?.addEventListener('click', closeEditAlert);
      el('edit-cancel')?.addEventListener('click', closeEditAlert);
      el('edit-modal')?.querySelector('.modal-backdrop')?.addEventListener('click', closeEditAlert);
      el('edit-form')?.addEventListener('submit', submitEditForm);

      document.addEventListener('focusin', e => {
        if (e.target.type === 'date') {
          try { e.target.showPicker(); } catch {}
        }
      });

      root.openEditAlert = openEditAlert;
    }

    async function preflightChecks(autoCart, notifySlack) {
      const errors = [];

      const [settings, loginResult] = await Promise.all([
        api('GET', '/api/campsite/settings'),
        autoCart ? api('POST', '/api/campsite/settings/test-chrome', {}).catch(err => ({ error: err.message })) : null,
      ]);

      if (notifySlack) {
        const hasToken = settings.slack_token === MASKED;
        const hasChannel = !!settings.slack_channel;
        if (!hasToken || !hasChannel) {
          errors.push('Slack notifications enabled but token/channel not configured \u2014 add them in Settings');
        }
      }

      if (autoCart) {
        if (loginResult?.error) {
          errors.push(`Auto-cart check failed: ${loginResult.error}`);
        } else if (!loginResult?.loggedIn) {
          errors.push('Auto-cart requires a logged-in browser session \u2014 use "Test browser session" in Settings');
        }
      }

      return errors;
    }

    async function submitAlertForm(e) {
      e.preventDefault();

      if (selectedCampgrounds.size === 0) {
        showToast('Please select at least one campground', 'error');
        return;
      }

      const startDate = el('start-date').value;
      const endDate = el('end-date').value;

      if (!startDate || !endDate) {
        showToast('Please set arrival and departure dates', 'error');
        return;
      }

      if (startDate >= endDate) {
        showToast('Departure must be after arrival', 'error');
        return;
      }

      const campsiteTypes = [...document.querySelectorAll('#campsite-types input:checked')].map(i => i.value);
      const equipmentTypes = [...document.querySelectorAll('#equipment-types input:checked')].map(i => i.value);
      const specificRaw = el('specific-sites').value.trim();
      const specificSites = specificRaw ? specificRaw.split(',').map(s => s.trim().padStart(3, '0')).filter(Boolean) : [];

      const base = {
        start_date: startDate,
        end_date: endDate,
        min_nights: parseInt(el('min-nights').value || '1', 10),
        campsite_types: campsiteTypes,
        equipment_types: equipmentTypes,
        max_people: parseInt(el('max-people').value || '0', 10) || null,
        specific_sites: specificSites,
        notify_slack: el('notify-slack').checked,
        auto_cart: el('auto-cart').checked,
        stop_after_match: el('stop-after-match').checked,
      };

      try {
        showToast('Checking integrations\u2026', 'info', 3000);
        const errs = await preflightChecks(base.auto_cart, base.notify_slack);
        if (errs.length) {
          errs.forEach(msg => showToast(msg, 'error', 8000));
          return;
        }
        for (const [id, cg] of selectedCampgrounds) {
          await api('POST', '/api/campsite/alerts', { ...base, campground_id: id, campground_name: cg.name, parent_name: cg.parent_name || null, parent_id: cg.parent_id || null });
        }
        const n = selectedCampgrounds.size;
        showToast(`${n} alert${n > 1 ? 's' : ''} created \u2014 polling started!`, 'success');
        e.target.reset();
        el('auto-cart').checked = true;
        el('stop-after-match').checked = true;
        clearCampground();
        loadAlerts();
      } catch (err) {
        showToast(`Error: ${err.message}`, 'error');
      }
    }

    function openEditAlert(id) {
      api('GET', '/api/campsite/alerts').then(alerts => {
        const a = alerts.find(x => x.id === id);
        if (!a) return;

        el('edit-alert-id').value = a.id;
        el('edit-campground-label').textContent = `Campground: ${a.campground_name} (#${a.campground_id})`;
        el('edit-start-date').value = a.start_date;
        el('edit-end-date').value = a.end_date;
        el('edit-min-nights').value = a.min_nights;
        el('edit-max-people').value = a.max_people || '';
        el('edit-notify-slack').checked = a.notify_slack !== false;
        el('edit-auto-cart').checked = Boolean(a.auto_cart);
        el('edit-stop-after-match').checked = a.stop_after_match !== false;

        for (const cb of el('edit-campsite-types').querySelectorAll('input')) {
          cb.checked = (a.campsite_types || []).includes(cb.value);
        }
        for (const cb of el('edit-equipment-types').querySelectorAll('input')) {
          cb.checked = (a.equipment_types || []).includes(cb.value);
        }
        el('edit-specific-sites').value = (a.specific_sites || []).join(', ');

        const reactivateRow = el('edit-reactivate-row');
        const reactivateCb = el('edit-reactivate');
        if (a.status === 'done') {
          reactivateRow.classList.remove('hidden');
          reactivateCb.checked = false;
        } else {
          reactivateRow.classList.add('hidden');
          reactivateCb.checked = false;
        }

        el('edit-modal').classList.remove('hidden');
      }).catch(err => showToast(`Error: ${err.message}`, 'error'));
    }

    function closeEditAlert() {
      el('edit-modal').classList.add('hidden');
    }

    async function submitEditForm(e) {
      e.preventDefault();

      const id = parseInt(el('edit-alert-id').value, 10);
      const startDate = el('edit-start-date').value;
      const endDate = el('edit-end-date').value;

      if (startDate >= endDate) {
        showToast('Departure must be after arrival', 'error');
        return;
      }

      const specificRaw = el('edit-specific-sites').value.trim();
      const specificSites = specificRaw ? specificRaw.split(',').map(s => s.trim().padStart(3, '0')).filter(Boolean) : [];

      const payload = {
        start_date: startDate,
        end_date: endDate,
        min_nights: parseInt(el('edit-min-nights').value || '1', 10),
        max_people: parseInt(el('edit-max-people').value || '0', 10) || null,
        campsite_types: [...el('edit-campsite-types').querySelectorAll('input:checked')].map(i => i.value),
        equipment_types: [...el('edit-equipment-types').querySelectorAll('input:checked')].map(i => i.value),
        specific_sites: specificSites,
        notify_slack: el('edit-notify-slack').checked,
        auto_cart: el('edit-auto-cart').checked,
        stop_after_match: el('edit-stop-after-match').checked,
      };

      if (el('edit-reactivate').checked) {
        payload.status = 'active';
      }

      try {
        showToast('Checking integrations\u2026', 'info', 3000);
        const errs = await preflightChecks(payload.auto_cart, payload.notify_slack);
        if (errs.length) {
          errs.forEach(msg => showToast(msg, 'error', 8000));
          return;
        }
        await api('PATCH', `/api/campsite/alerts/${id}`, payload);
        showToast('Alert updated', 'success');
        closeEditAlert();
        loadAlerts();
      } catch (err) {
        showToast(`Error: ${err.message}`, 'error');
      }
    }

    return {
      initControls,
      openEditAlert,
      preflightChecks,
    };
  }

  root.Campsite = {
    ...Campsite,
    createCampsiteAlertForms,
  };
})(window);

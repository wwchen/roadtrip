// web/account/settings-modal-template.js
// Pure template — no DOM access, no side effects.

/**
 * Returns the HTML shell for the settings modal body.
 * Contains hosts for the tabs rail, banner, panel content, and footer.
 *
 * @returns {string}
 */
export function settingsModalBodyTemplate() {
  return `
    <div class="rt-settings-modal-body">
      <div class="rt-settings-modal-banner" data-host="banner"></div>
      <div class="rt-settings-modal-main">
        <div class="rt-settings-modal-tabs" data-host="tabs"></div>
        <div class="rt-settings-modal-panel" data-host="panel"></div>
      </div>
      <div class="rt-settings-modal-footer">
        <button
          type="button"
          class="rt-settings-modal-save-btn"
          data-action="save"
          disabled
        >Save</button>
      </div>
    </div>
  `;
}

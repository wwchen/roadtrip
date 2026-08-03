// Roadtrip Map — Slack watch notification payloads (Block Kit)
//
// One object per watch state. Each is a ready-to-send `chat.postMessage`
// argument set: the color bar is the legacy `attachments[].color`, and all
// Block Kit blocks live INSIDE the attachment so the stripe applies.
//
// Button styles: Slack supports ONLY default / "primary" (green) / "danger"
// (red). The main CTA uses "primary"; destructive uses "danger". There is no
// brand-blue filled button in Slack.
//
// "Reserve" is a URL button (opens Recreation.gov, no backend). All other
// buttons are interactive — your app receives the payload and matches on
// `action_id` + the JSON in `value`. `unfurl_links:false` suppresses the
// Recreation.gov photo unfurl.
//
// Replace {{...}} placeholders when building the payload server-side.

const WATCH_ID = "{{watchId}}"; // echoed in every button's value

// Attachment color bars. Slack's API takes a literal hex over the wire — it
// cannot resolve a CSS custom property — so this is the one sanctioned mirror
// of the availability tokens outside `tokens.css`. Each entry names the token
// it mirrors; change both together.
const BAR = {
  available: "#4cb96a", // --rt-avail
  watching: "#3b82f6",  // --rt-watching
  paused: "#8a8f96",    // --rt-paused
  expiring: "#f1a04a",  // --rt-first-come
  error: "#f56565",     // --rt-error
};

module.exports = {
  // ── A · Sites available ────────────────────────────────────────────────
  available: {
    channel: "{{channel}}",
    unfurl_links: false,
    unfurl_media: false,
    text: "🏕️ 6 sites opened at Red Bridge Campground (Jul 9 → Jul 10)",
    attachments: [
      {
        color: BAR.available,
        blocks: [
          {
            type: "section",
            text: { type: "mrkdwn", text: "*🏕️ 6 sites just opened*" }
          },
          {
            type: "section",
            fields: [
              { type: "mrkdwn", text: "*Campground*\nRed Bridge · Mt. Baker-Snoqualmie NF" },
              { type: "mrkdwn", text: "*Your window*\n`Jul 9 → Jul 10`" }
            ]
          },
          {
            type: "section",
            text: {
              type: "mrkdwn",
              text:
                "🟢 *002* — Area Red Bridge · Standard nonelectric\n" +
                "🟢 *003* — Area Red Bridge · Standard nonelectric\n" +
                "🟢 *009* — Area Red Bridge · Standard nonelectric\n" +
                "_+ 3 more — 011, 012, 013_"
            }
          },
          {
            type: "actions",
            elements: [
              {
                type: "button",
                style: "primary",
                text: { type: "plain_text", emoji: true, text: "🎟️ Reserve site 002" },
                url: "https://www.recreation.gov/camping/campsites/{{siteId}}",
                action_id: "reserve_site"
              },
              {
                type: "button",
                text: { type: "plain_text", emoji: true, text: "Availability grid" },
                url: "{{appUrl}}/watch/{{watchId}}/grid",
                action_id: "open_grid"
              },
              {
                type: "button",
                text: { type: "plain_text", emoji: true, text: "⏸ Pause" },
                action_id: "watch_pause",
                value: WATCH_ID
              },
              {
                type: "button",
                style: "danger",
                text: { type: "plain_text", emoji: true, text: "🗑 Delete" },
                action_id: "watch_delete",
                value: WATCH_ID,
                confirm: {
                  title: { type: "plain_text", text: "Delete this watch?" },
                  text: { type: "mrkdwn", text: "You'll stop getting alerts for Red Bridge." },
                  confirm: { type: "plain_text", text: "Delete" },
                  deny: { type: "plain_text", text: "Keep" },
                  style: "danger"
                }
              }
            ]
          },
          {
            type: "context",
            elements: [
              { type: "mrkdwn", text: "Checked just now · every 5 min  ·  Reserve links straight to Recreation.gov" }
            ]
          }
        ]
      }
    ]
  },

  // ── B · Watching (idle) ────────────────────────────────────────────────
  watching: {
    channel: "{{channel}}",
    text: "👀 Watching Upper Pines Campground (Jul 10 → Jul 11)",
    attachments: [
      {
        color: BAR.watching,
        blocks: [
          { type: "section", text: { type: "mrkdwn", text: "*👀 Watching for openings*" } },
          {
            type: "section",
            fields: [
              { type: "mrkdwn", text: "*Campground*\nUpper Pines Campground" },
              { type: "mrkdwn", text: "*Window*\n`Jul 10 → Jul 11`" }
            ]
          },
          {
            type: "section",
            text: { type: "mrkdwn", text: "Nothing open right now — I'll ping you the moment a site frees up." }
          },
          {
            type: "actions",
            elements: [
              { type: "button", text: { type: "plain_text", text: "Availability grid" }, url: "{{appUrl}}/watch/{{watchId}}/grid", action_id: "open_grid" },
              { type: "button", text: { type: "plain_text", text: "View on map" }, url: "{{appUrl}}/watch/{{watchId}}", action_id: "open_map" },
              { type: "button", text: { type: "plain_text", emoji: true, text: "⏸ Pause" }, action_id: "watch_pause", value: WATCH_ID },
              { type: "button", style: "danger", text: { type: "plain_text", emoji: true, text: "🗑 Delete" }, action_id: "watch_delete", value: WATCH_ID }
            ]
          },
          { type: "context", elements: [{ type: "mrkdwn", text: "Armed · checked 2 min ago" }] }
        ]
      }
    ]
  },

  // ── C · Paused ─────────────────────────────────────────────────────────
  paused: {
    channel: "{{channel}}",
    text: "⏸ Watch paused — Upper Pines Campground",
    attachments: [
      {
        color: BAR.paused,
        blocks: [
          { type: "section", text: { type: "mrkdwn", text: "*⏸ Watch paused*" } },
          {
            type: "section",
            fields: [
              { type: "mrkdwn", text: "*Campground*\nUpper Pines Campground" },
              { type: "mrkdwn", text: "*Window*\n`Jul 10 → Jul 11`" }
            ]
          },
          { type: "section", text: { type: "mrkdwn", text: "Paused — I won't alert until you resume this watch." } },
          {
            type: "actions",
            elements: [
              { type: "button", style: "primary", text: { type: "plain_text", emoji: true, text: "▶ Resume" }, action_id: "watch_resume", value: WATCH_ID },
              { type: "button", text: { type: "plain_text", text: "Availability grid" }, url: "{{appUrl}}/watch/{{watchId}}/grid", action_id: "open_grid" },
              { type: "button", style: "danger", text: { type: "plain_text", emoji: true, text: "🗑 Delete" }, action_id: "watch_delete", value: WATCH_ID }
            ]
          },
          { type: "context", elements: [{ type: "mrkdwn", text: "Paused 3 min ago" }] }
        ]
      }
    ]
  },

  // ── D · Window expiring ────────────────────────────────────────────────
  expiring: {
    channel: "{{channel}}",
    text: "⏳ Watch expires tomorrow — Kirk Creek Campground",
    attachments: [
      {
        color: BAR.expiring,
        blocks: [
          { type: "section", text: { type: "mrkdwn", text: "*⏳ Watch expires tomorrow*" } },
          {
            type: "section",
            fields: [
              { type: "mrkdwn", text: "*Campground*\nKirk Creek Campground" },
              { type: "mrkdwn", text: "*Window*\n`ends Jul 7`" }
            ]
          },
          { type: "section", text: { type: "mrkdwn", text: "No luck this window — *0 openings* in 6 days of checking. Extend the dates or let it expire?" } },
          {
            type: "actions",
            elements: [
              { type: "button", style: "primary", text: { type: "plain_text", text: "Extend window" }, action_id: "watch_extend", value: WATCH_ID },
              { type: "button", text: { type: "plain_text", text: "Keep watching" }, action_id: "watch_keep", value: WATCH_ID },
              { type: "button", style: "danger", text: { type: "plain_text", emoji: true, text: "🗑 Delete" }, action_id: "watch_delete", value: WATCH_ID }
            ]
          },
          { type: "context", elements: [{ type: "mrkdwn", text: "Auto-expires in ~22 hours" }] }
        ]
      }
    ]
  },

  // ── E · Check failed ───────────────────────────────────────────────────
  error: {
    channel: "{{channel}}",
    text: "⚠️ Couldn't check availability — Limekiln SP",
    attachments: [
      {
        color: BAR.error,
        blocks: [
          { type: "section", text: { type: "mrkdwn", text: "*⚠️ Couldn't check availability*" } },
          {
            type: "section",
            fields: [
              { type: "mrkdwn", text: "*Campground*\nLimekiln SP" },
              { type: "mrkdwn", text: "*Last good check*\n`6 hours ago`" }
            ]
          },
          { type: "section", text: { type: "mrkdwn", text: "Recreation.gov didn't respond on the last 3 tries. Still armed — I'll keep retrying with backoff." } },
          {
            type: "actions",
            elements: [
              { type: "button", text: { type: "plain_text", emoji: true, text: "🔁 Retry now" }, action_id: "watch_retry", value: WATCH_ID },
              { type: "button", style: "danger", text: { type: "plain_text", emoji: true, text: "🗑 Delete" }, action_id: "watch_delete", value: WATCH_ID }
            ]
          },
          { type: "context", elements: [{ type: "mrkdwn", text: "Next retry in ~10 min" }] }
        ]
      }
    ]
  }
};

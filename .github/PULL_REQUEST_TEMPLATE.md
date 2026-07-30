## Summary

<!-- 1-3 bullets on what changed and why. Focus on the "why". -->

## Test plan

- [ ] `./gradlew :backend:test` passes locally
- [ ] `./gradlew :backend:ktlintCheck` clean
- [ ] If touching the request path: `make qa` smoke passes against local stack
- [ ] If touching data shape: ran `make data-import` and spot-checked
- [ ] If touching campsite availability, watches, or a reservation-provider adapter: smoked the topbar alerts UI too

## Notes

<!-- Migration steps, deploy ordering, follow-ups, etc. Delete if N/A. -->

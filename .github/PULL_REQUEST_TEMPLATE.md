## Summary

<!-- 1-3 bullets on what changed and why. Focus on the "why". -->

## Test plan

- [ ] `./gradlew test` passes locally (`make pois-test`)
- [ ] `./gradlew ktlintCheck` clean
- [ ] If touching the request path: `make qa` smoke passes against local stack
- [ ] If touching data shape: ran `make pois-import-all` and spot-checked
- [ ] If touching `/api/campsite/*` or the `/campsite` UI: smoked the campsite tool too

## Notes

<!-- Migration steps, deploy ordering, follow-ups, etc. Delete if N/A. -->

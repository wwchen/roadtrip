## Summary

<!-- 1-3 bullets on what changed and why. Focus on the "why". -->

## Test plan

- [ ] `cd backend && ./gradlew test` passes locally
- [ ] `cd backend && ./gradlew ktlintCheck` clean
- [ ] If touching the request path: `make qa` smoke passes against local stack
- [ ] If touching data shape: ran `make pois-import SOURCE=all` and spot-checked
- [ ] If touching `/api/campsite/*` or the `/campsite` UI: smoked the campsite tool too

## Notes

<!-- Migration steps, deploy ordering, follow-ups, etc. Delete if N/A. -->

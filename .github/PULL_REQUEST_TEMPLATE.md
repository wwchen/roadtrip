## Summary

<!-- 1-3 bullets on what changed and why. Focus on the "why". -->

## Test plan

- [ ] `gradle test` passes locally (`make pois-test`)
- [ ] `gradle ktlintCheck` clean
- [ ] If touching the request path: `make qa` smoke passes against local stack
- [ ] If touching data shape: ran `make pois-import-all` and spot-checked

## Notes

<!-- Migration steps, deploy ordering, follow-ups, etc. Delete if N/A. -->

# Release checklist

This alpha candidate is not a public release. Every item below must stay checked only when the matching evidence exists.

- [x] Java 17 clean `./gradlew --no-daemon clean test build` from the candidate root
- [x] Artifact path is exactly `build/libs/threaded-horizons-mc1.18.2-0.1.0-alpha.1.jar`
- [x] Completion scan over `src/` returns zero active markers
- [x] Storage state-machine tests execute (not NO-SOURCE)
- [ ] Independent Sol review of the repaired pin
- [ ] Full three-dimension crash/reopen suite against stock Forge tooling
- [ ] Public publication authorization

Do not describe the candidate as production-ready, corruption-safe, bug-free, fully implemented, complete asynchronous chunk I/O, or 1:1 C2ME parity until the independent review items above exist.

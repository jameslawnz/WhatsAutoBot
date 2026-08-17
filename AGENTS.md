# AGENTS.md

Guidance for AI agents working on this repository.

## Skills (mandatory)

Engineering workflow skills are installed in `.opencode/skills/` (reference
checklists in `.opencode/references/`). Before acting on any request, check
whether a skill applies and load it with the `skill` tool — see
`using-agent-skills` for the entry point. Never skip a required workflow
(spec, plan, test, review) or jump straight to implementation.

## Git workflow (mandatory)

Every time the codebase is updated (any bug fix, feature, or change is committed),
push a versioned commit so we can always revert.

1. **Commit with a versioned tag.** After committing code changes, immediately create
   a Git tag using the schema `v1`, `v2`, `v3`, ... always increment the highest
   existing tag by one.

2. **Steps to follow after every update:**

   ```bash
   git add -A
   git commit -m "<what changed>"
   # determine next version: bump the highest existing tag by one
   git tag vN
   git push origin main
   git push origin vN
   ```

3. **Never squash or amend** the latest versioned commit once it has been pushed.
   Each new change should be a new commit + a new incremented tag.

4. **Reverting:** to roll back to a known-good state use `git reset --hard vN` or
   `git checkout vN -- <path>` for a single file.

5. The file `.gitignore` excludes `build/`, `.gradle/`, `.kotlin/`, caches, and
   `local.properties` — never commit those.

## Build, install & commit (mandatory)

After every code change, always build, install to the device, then commit:

1. **Build + test:** `gradle :app:assembleDebug :app:testDebugUnitTest`
2. **Install the new build to the physical device:**
   `adb -s 3B162800GCS00000 install -r app/build/outputs/apk/debug/app-debug.apk`
3. **Re-enable the accessibility service** (a fresh `install -r` resets the
   binding), then verify it stuck. Use `--user 0` — the physical device has a
   MultiApp profile (user 999) and settings written without an explicit user
   can land in the wrong profile:

   ```bash
   adb -s 3B162800GCS00000 shell settings put secure --user 0 enabled_accessibility_services "com.whatsautobot.app/com.whatsautobot.app.WaAutoSendService"
   adb -s 3B162800GCS00000 shell settings put secure --user 0 accessibility_enabled 1
   adb -s 3B162800GCS00000 shell settings get secure --user 0 enabled_accessibility_services
   ```

4. **Commit, tag and push** per the Git workflow above (`vN`, `vN+1`, ...).

Never leave the device on an older build than `main`.

## Project notes

- Android app (Kotlin). Build with `gradle :app:assembleDebug` from the project root.
- Install to device with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- After `install -r`, the WhatsApp accessibility service is reset and must be
  re-enabled via `settings put secure enabled_accessibility_services ...` (or the
  in-app prompt).
- The app drives WhatsApp via a `wa.me` deep link + accessibility service
  (`WaAutoSendService`). No secret/API key required; do not add any.
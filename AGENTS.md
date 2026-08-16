# AGENTS.md

Guidance for AI agents working on this repository.

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

## Project notes

- Android app (Kotlin). Build with `gradle :app:assembleDebug` from the project root.
- Install to device with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- After `install -r`, the WhatsApp accessibility service is reset and must be
  re-enabled via `settings put secure enabled_accessibility_services ...` (or the
  in-app prompt).
- The app drives WhatsApp via a `wa.me` deep link + accessibility service
  (`WaAutoSendService`). No secret/API key required; do not add any.
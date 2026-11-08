# Photography on Unraid

Follow this runbook for authorized publication, installation, updates and recovery. [Operations](../specs/operational.md#run-on-the-nas) owns deployment topology; [local safety](../.agents/skills/sdd/local.md#storage-and-deployment-safety) owns retained-state and original-photo protection.

## Publish

With maintainer authorization, dispatch [publish](../.github/workflows/release.yml) from main. Use [release contracts](../ci/specs/contracts.md) to interpret image identity and registry access.

| Result | Action |
| --- | --- |
| Release completes | Record the image version, digest and revision from the workflow. |
| First publication succeeds, public-visibility verification fails | Set the photography package to public in GitHub package settings, verify anonymous pulls, then recover the matching Git tag below. Preserve the published image version. |
| Existing package is private | Set it to public and verify access before another authorized dispatch. |
| Duplicate image/Git tag or registry lookup error | Inspect the failure; the workflow stops rather than replace an existing version or assume absence. |
| Image publication succeeds, Git tagging fails | Verify the published revision and recover the matching tag below. |

### Recover a partial release

Inspect the image's `org.opencontainers.image.revision` label and confirm it equals the built main revision. Preserve the immutable version under the [partial-publication lifecycle](../ci/specs/design.md#release-boundary).

Set `VERSION` to the published image tag and `VERIFIED_REVISION` to its verified commit. After confirming public visibility, anonymous pulls and an absent Git tag, annotate **that exact commit**:

```sh
git fetch origin main --tags
git tag -a "v$VERSION" "$VERIFIED_REVISION" -m "Photography $VERSION"
git push origin "refs/tags/v$VERSION"
```

Use the configured personal signing identity and obtain authorization for the recovery writes.

## First installation

Set the approved NAS connection before running the following procedures:

```sh
NAS_SSH=root@192.168.1.3
```

Recheck the [template](photography.xml) immediately before installation.

Keep photography's installation separate from the existing `layout-tool` and its retained albums.

### Isolated smoke deployment

1. Obtain authorization for an isolated smoke deployment before production installation.
2. Use the directory-preparation and template-Apply steps below with a temporary container name and disposable data/thumbnail paths. Disable WUD monitoring for that container.
3. Check runtime UID/GID, mounts and permissions on the NAS.
4. Confirm frontend/API delivery, editing SSE, actual photo processing and thumbnail delivery. Restart and confirm saved state remains available.
5. Remove only the temporary container and its identified disposable state.

### Install the production container

1. Obtain installation authorization and complete [state-directory preparation](#prepare-state-directories) before Apply.
2. Verify the image identity can read/traverse the selected originals and every chapter subdirectory. NAS user-share ACL inheritance alone does not prove container access; resolve issues with narrowly scoped permissions.
3. Verify the selected release meets the registry contract. Preserve existing NAS credentials and other applications' private-registry configuration.
4. Copy `unraid/photography.xml` to `/boot/config/plugins/dockerMan/templates-user/my-photography.xml` on the approved NAS. Select a published version in the Docker page and compare every setting with the source template before Apply.
5. Arrange the [update checkpoint procedure](#updates) before enabling unattended replacement.
6. Apply once and inspect startup logs. Set `APP_URL` to the configured WebUI origin, then inspect runtime identity/mounts and check readiness:

```sh
ssh "$NAS_SSH" 'docker exec photography id'
ssh "$NAS_SSH" 'docker inspect photography --format "{{json .Mounts}}"'
curl -fsS "$APP_URL/health"
```

Compare runtime identity and mount permissions with the template and [runtime image](../containers/photography/Dockerfile). Open the configured application from LAN and Tailscale and confirm API-backed navigation before seeding; [readiness interpretation](../specs/operational.md#readiness-logs-and-metrics) belongs to application operations.

### Prepare state directories

Unraid can create missing bind-mount directories as `99:100`, excluding the image user from writes. Prepare them explicitly to avoid SQLite startup failing with `SQLITE_CANTOPEN`.

For an authorized first installation, confirm both state directories are absent or empty. Set `DATA_DIR` and `THUMBNAIL_DIR` from the approved template, and `APP_UID`/`APP_GID` from the runtime image.

```sh
ssh "$NAS_SSH" "install -d -m 0755 -o '$APP_UID' -g '$APP_GID' '$DATA_DIR' '$THUMBNAIL_DIR'"
ssh "$NAS_SSH" "stat -c '%a %u:%g %n' '$DATA_DIR' '$THUMBNAIL_DIR'"
```

Verify both directories have the requested ownership and mode before Apply. Limit these commands to the identified empty/new state directories; retained state follows local safety.

### Access boundary

Restrict the unauthenticated application to trusted LAN and Tailscale clients at the NAS/network boundary. Docker port publication is not an access restriction; do not forward the application publicly.

Keep the browser origin stable for browser-local state. If a proxy is used, configure it to preserve the [API/event transport contract](../specs/http-api.openapi.json), including private headers and separate media caching; disable event-stream buffering, caching and compression and allow long-lived connections.

## One-time album population

Populate albums **after** the new app is running and its mounts/health are verified. Obtain explicit authorization for this one-time operator action; keep seeding separate from startup, release gates and update hooks.

The seed client and its extensions are private local artifacts outside the reviewed repository and release gates. Preserve the client, inputs and journals independently of Git and the production image.

| Before execution | Required condition |
| --- | --- |
| Approve destination writes | Revalidate client compatibility with the installed API and schema; historical local approvals do not authorize the NAS destination. |
| Prepare plans and journals | Use fresh NAS destination plans, identities and receipts; preserve the rehearsal database independently rather than copy it. |
| Approve source access | Confirm the selected original-photo mount and input plans against the [template](photography.xml). |
| Run an offline stage | Obtain separate runtime authorization; stop the backend and use an accessible local SQLite mount with the same read-only originals. Never operate live SQLite over a network filesystem. |

## Updates

1. Confirm the host Docker trigger is configured; labels alone do not establish replacement or backup scheduling.
2. Before each update, pause photography replacement, stop its sole backend and take the [paired checkpoint](#backup-and-recovery). Allow replacement only after backup completes; arrange unattended backup coordination before leaving automatic updates enabled.
3. After replacement, confirm the trigger and saved Unraid template retain the selected version so a later Apply does not revert it.
4. Check logs, `/health`, frontend assets, saved albums/chapters and thumbnail delivery on LAN and Tailscale.

Follow [editing-access recovery](../specs/operational.md#editing-access-and-recovery) after replacement.

## Backup and recovery

SQLite and referenced thumbnails form one recovery unit. Keep independent originals backups and preserve the image version/digest, template, application configuration and seed journals with each checkpoint.

1. Pause WUD replacement and stop `photography` in Unraid. Confirm no backend uses the database before taking a backup.
2. Back up **both** approved photography data and thumbnail directories in the same stopped-state checkpoint. Preserve SQLite sidecars if present; copying only a live `.db` can omit committed WAL data.
3. Verify the checkpoint completed and both directories are included before restarting or updating. Retain earlier checkpoints independently; backup of appdata must not omit thumbnails.

### Restore

For failed startup, preserve logs and the failed database/thumbnail pair before intervention. Investigate migration, configuration and permission errors; they do not authorize data deletion or reseeding.

1. Pause replacement, then stop the backend.
2. Choose a compatible published image **and its matching database/thumbnail checkpoint**.
3. Restore both directories while stopped, verify ownership, then apply that image version.
4. Check health, saved content and thumbnail delivery before reenabling replacement.

**Selecting an older image does not reverse database migrations.** It may not read the newer database; restoring an earlier checkpoint can lose later edits. Preserve that loss decision and never restore newer confirmed seed journals against an older database checkpoint.

## Monitor

Inspect `docker logs photography` for startup, migration and native-library failures; the [template](photography.xml) owns log-rotation settings.

Follow [application monitoring](../specs/operational.md#readiness-logs-and-metrics) for health, processing feedback and host checks.

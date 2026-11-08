# Photography on Unraid

Use one non-root backend to serve the packaged browser application and API. The production image contains Java and application libraries; the [deployment diagram](../specs/operational.md#run-on-the-nas) shows publication, the container and its mounts.

## Publish

Dispatch [publish](../.github/workflows/release.yml) from **main** only with maintainer authorization. Publication runs repository code with `contents: write` and `packages: write`; it uses the ephemeral Actions token rather than NAS credentials.

The workflow runs module validation and every existing application/pipeline gate, then Dagger builds and publishes the image. It publishes `linux/amd64` with a UTC version such as `2026.11.8-T010203`, OCI version/commit/creation/source labels, and the matching annotated Git tag `v2026.11.8-T010203`; there is no mutable `latest`.

Require **public** GHCR package visibility for anonymous NAS and WUD pulls. New packages default to private, independently of GitHub repository visibility.

| Result | Action |
| --- | --- |
| All gates, anonymous manifest read and Git tagging succeed | Record the exact image version, digest and revision from the workflow. |
| First publication succeeds, public-visibility verification fails | Set the photography package to public in GitHub package settings, verify anonymous pulls, then recover the matching Git tag below. Preserve the published image version. |
| Existing package is private | Set it to public and verify access before another authorized dispatch. |
| Duplicate image/Git tag or registry lookup error | Inspect the failure; the workflow stops rather than replace an existing version or assume absence. |
| Image publication succeeds, Git tagging fails | Verify the published revision and recover the matching tag below. |

### Recover a partial release

Preserve the published version; do not republish or replace it. Inspect the image's `org.opencontainers.image.revision` label and confirm it equals the built main revision.

After confirming public visibility, anonymous pulls and an absent Git tag, annotate **that exact commit**:

```sh
git fetch origin main --tags
git tag -a "v$VERSION" "$VERIFIED_REVISION" -m "Photography $VERSION"
git push origin "refs/tags/v$VERSION"
```

Use the configured personal signing identity. These commands require operator authorization; a successful image publication does not itself authorize recovery writes or NAS installation.

## First installation

Use the [template](photography.xml) for the configured NAS at `root@192.168.1.3`. It owns exact image, network, port and host-path settings; recheck them immediately before installation.

Preserve the existing `layout-tool`, its retained albums and all originals. Install photography into its approved independent state directories.

### Isolated smoke deployment

Before production installation, obtain authorization for an isolated NAS smoke deployment. Use a temporary container name, disposable data/thumbnail directories and a read-only original-photo mount; disable WUD monitoring for that container.

Confirm frontend/API delivery, editing SSE, actual photo processing and thumbnail delivery, then restart and confirm saved state remains available. Check runtime UID/GID, mounts and access permissions on the NAS; remove only the temporary container and its disposable state afterward.

### Install the production container

1. Obtain explicit installation authorization. Create only the approved photography data/thumbnail directories, granting UID/GID `1000:1000` write/traverse access; do not recursively change ownership or permissions on originals or existing appdata.
2. Verify UID/GID `1000:1000` can read/traverse the selected originals and all chapter subdirectories. NAS user-share ACL inheritance alone does not prove container access; resolve any access issue with narrowly scoped permissions.
3. Confirm the published version is anonymously pullable from GHCR. Public images need no Docker login or WUD registry credentials; remove neither existing NAS credentials nor other applications' private GHCR configuration.
4. Copy `unraid/photography.xml` to `/boot/config/plugins/dockerMan/templates-user/my-photography.xml` on the approved NAS. In the Docker page, replace `REPLACE_WITH_PUBLISHED_VERSION` with an existing timestamped version; confirm network, port, read-only originals and both writable mounts before Apply.
5. Confirm the saved template retains `--restart=unless-stopped`, bounded logs and both WUD labels. Arrange backup/update coordination before allowing unattended updates; WUD labels enable monitoring, not backups.
6. Apply once, inspect startup logs, and check the application from both LAN and Tailscale. Inspect the actual runtime UID/GID and mounts before any seeding.

```sh
ssh root@192.168.1.3 'docker exec photography id'
ssh root@192.168.1.3 'docker inspect photography --format "{{json .Mounts}}"'
curl -fsS http://192.168.1.3:10002/health
```

Expect UID/GID `1000:1000`, `/photos` with `RW: false`, and health JSON `{"status":"ok"}`. Open the browser UI and confirm API-backed navigation; health establishes startup completion/reachability, not ongoing database/storage health.

### Access boundary

Limit access at the NAS/network boundary to trusted LAN and Tailscale clients. The application is unauthenticated: do not forward it publicly, and do not treat Docker port publication as an access restriction.

Keep the browser origin stable for remembered identity and local drafts. If a proxy is used, preserve `X-Editor-*` request/response headers, disable event-stream buffering/caching/compression, honor `X-Accel-Buffering: no`, and allow long-lived connections with transport keepalives; preserve immutable thumbnail caching separately.

## One-time album population

Populate albums **after** the new app is running and its mounts/health are verified. Obtain explicit authorization for this one-time operator action; keep seeding separate from startup, release gates and update hooks.

The saved seed client and its **2020–2026** extension are private local delivery artifacts, outside the reviewed repository and release gates. Preserve the client, inputs and journals independently of Git and the production image.

| Before execution | Required condition |
| --- | --- |
| Approve destination writes | Revalidate client compatibility with the installed API and schema; historical local approvals do not authorize the NAS destination. |
| Prepare plans and journals | Use fresh NAS destination plans, identities and receipts; preserve the rehearsal database independently rather than copy it. |
| Approve source access | Confirm exact mounts and input plans; the runtime resolves read-only originals at `/photos`. |
| Run an offline stage | Obtain separate runtime authorization; stop the backend and use an accessible local SQLite mount with the same read-only originals. Never operate live SQLite over a network filesystem. |

## Updates and authentication

The template's WUD labels opt into monitoring and select timestamped releases. The existing WUD watcher and Docker trigger own the update schedule.

WUD tag discovery and Docker image pulls are independent. Both can be anonymous for the verified public photography package; other private applications still require their own credentials.

1. Confirm the host Docker trigger is configured; labels alone do not establish replacement or backup scheduling.
2. Before each update, pause photography replacement, stop its sole backend and take the [paired checkpoint](#backup-and-recovery). Allow replacement only after backup completes; arrange unattended backup coordination before leaving automatic updates enabled.
3. After replacement, confirm the trigger and saved Unraid template retain the selected version so a later Apply does not revert it.
4. Check logs, `/health`, frontend assets, saved albums/chapters and thumbnail delivery on LAN and Tailscale.

Restart invalidates editing credentials and grants, while saved albums remain. Reconnect clients and reconcile drafts explicitly.

## Backup and recovery

SQLite and referenced thumbnails form one recovery unit. Keep independent originals backups and preserve the image version/digest, template, application configuration and seed journals with each checkpoint.

1. Pause WUD replacement and stop `photography` in Unraid. Confirm no backend uses the database before taking a backup.
2. Back up **both** approved photography data and thumbnail directories in the same stopped-state checkpoint. Preserve SQLite sidecars if present; copying only a live `.db` can omit committed WAL data.
3. Verify the checkpoint completed and both directories are included before restarting or updating. Retain earlier checkpoints independently; backup of appdata must not omit thumbnails.

### Restore

For failed startup, preserve logs and the failed database/thumbnail pair before intervention. Investigate migration, configuration and permission errors; they do not authorize data deletion or reseeding.

1. Stop the backend and pause replacement.
2. Choose a compatible published image **and its matching database/thumbnail checkpoint**.
3. Restore both directories while stopped, verify ownership, then apply that image version.
4. Check health, saved content and thumbnail delivery before reenabling replacement.

**Selecting an older image does not reverse database migrations.** It may not read the newer database; restoring an earlier checkpoint can lose later edits. Preserve that loss decision and never restore newer confirmed seed journals against an older database checkpoint.

## Monitor

Monitor process availability, logs and free space at the NAS. Inspect `docker logs photography` for startup, migration and native-library failures; the template owns bounded log rotation.

[Application operations](../specs/operational.md#readiness-logs-and-metrics) explains health and processing feedback. This deployment adds no metrics service or automatic recovery.

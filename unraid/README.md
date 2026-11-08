# Photography on Unraid

One non-root backend serves the packaged browser application and API. The production image contains Java and application libraries, not sbt, Node or a frontend development server.

## Publish

After the deployment PR is merged, explicitly dispatch [publish](../.github/workflows/release.yml) from **main**. Only trusted maintainers should dispatch it: publishing runs repository code with `contents: write` and `packages: write`; credentials are the ephemeral Actions token, never NAS configuration.

The workflow runs module validation and every Dagger gate, including the isolated final-image smoke. It publishes `linux/amd64` with a UTC version such as `2026.11.8-T010203`, OCI version/commit/creation/source labels, and the matching annotated Git tag `v2026.11.8-T010203`; there is no mutable `latest`.

The GHCR package is intended to be **public**, as approved for this deployment. GitHub repository visibility does not establish package visibility: new GHCR packages initially default to private.

On the first dispatch, publication can succeed while public-visibility verification stops release tagging. Open the photography package settings in GitHub and change its visibility to public; verify anonymous pulls, then recover the matching Git tag as below without republishing or replacing the existing image version.

An existing non-public package or duplicate image/Git tag stops publication. Registry lookup errors also stop the workflow; a successful release requires both public-package visibility and an anonymous manifest read.

Record the exact version, digest and revision from the workflow. If publication succeeds but tag creation/push fails, inspect the published image's `org.opencontainers.image.revision` label, confirm it equals the built main revision, and annotate **that exact commit**:

```sh
git fetch origin main --tags
git tag -a "v$VERSION" "$VERIFIED_REVISION" -m "Photography $VERSION"
git push origin "refs/tags/v$VERSION"
```

Use the configured personal signing identity. These commands are operator actions, not authorization to publish; confirm the tag is absent before creating it.

## First installation

The [template](photography.xml) contains the maintainer-approved host settings below. Read-only inspection found `br_docker` and the original collection on `root@192.168.1.3`; port `10002` was free and photography appdata did not yet exist.

| Host setting | Container setting |
| --- | --- |
| `br_docker` | Single-container bridge network |
| Host TCP `10002` | Backend TCP `8080` |
| `/mnt/user/appdata/photography/data` | Writable `/data`; SQLite at `/data/photography.db` |
| `/mnt/user/appdata/photography/thumbnails` | Writable `/thumbnails` |
| `/mnt/user/media/photos/Travel` | Read-only `/photos` |

Recheck these settings immediately before installing. Preserve the existing `layout-tool`, its retained albums and all originals; installation must not replace its state.

1. Obtain explicit installation authorization. Create only the approved photography data/thumbnail directories, granting UID/GID `1000:1000` write/traverse access; do not recursively change ownership or permissions on originals or existing appdata.
2. Verify UID/GID `1000:1000` can read/traverse the selected originals and all chapter subdirectories. NAS user-share ACL inheritance alone does not prove container access; resolve any access issue with narrowly scoped permissions.
3. Confirm the published version is anonymously pullable from GHCR. Public images need no Docker login or WUD registry credentials; remove neither existing NAS credentials nor other applications' private GHCR configuration.
4. Copy `unraid/photography.xml` to `/boot/config/plugins/dockerMan/templates-user/my-photography.xml` on the approved NAS. In the Docker page, replace `REPLACE_WITH_PUBLISHED_VERSION` with an existing timestamped version; confirm network, port, read-only originals and both writable mounts before Apply.
5. Confirm the saved template retains `--restart=unless-stopped`, bounded logs and both WUD labels. Arrange backup/update coordination before allowing unattended updates; the template watches releases but does not create backups.
6. Apply once, inspect startup logs, and check the application from both LAN and Tailscale. Inspect the actual runtime UID/GID and mounts before any seeding.

```sh
ssh root@192.168.1.3 'docker exec photography id'
ssh root@192.168.1.3 'docker inspect photography --format "{{json .Mounts}}"'
curl -fsS http://192.168.1.3:10002/health
```

Expect UID/GID `1000:1000`, `/photos` with `RW: false`, and health JSON `{"status":"ok"}`. Open the browser UI and confirm API-backed navigation; health establishes startup completion/reachability, not ongoing database/storage health.

Never forward this unauthenticated application publicly. Limit access at the NAS/network boundary to trusted LAN and Tailscale clients; Docker port publication alone does not enforce that restriction.

Keep the browser origin stable for remembered identity and local drafts. If a proxy is used, preserve `X-Editor-*` request/response headers, disable event-stream buffering/caching/compression, honor `X-Accel-Buffering: no`, and allow long-lived connections with transport keepalives; preserve immutable thumbnail caching separately.

## One-time album population

Populate albums **after** the new app is running and its mounts/health are verified. Seeding is an explicitly authorized, one-time operator action, never startup, a release gate or an update hook.

The saved seed client and its **2020–2026** extension are private local delivery artifacts, outside the reviewed repository and release gates. Preserve them independently of Git.

Use fresh NAS destination plans and journals; never copy the rehearsal SQLite database or reuse its IDs/receipts. Revalidate the client's compatibility with the installed API and schema before approving its writes; historical local approvals do not authorize the NAS destination.

The client runtime must resolve this template's originals at `/photos`; approve its exact mounts and input plans before execution.

Preserve the private inputs and journals outside Git and the production image. Any offline seed stage needs a separately approved runtime with the same read-only originals, an accessible local SQLite mount and the backend stopped; do not operate live SQLite over a network filesystem.

## Updates and authentication

`wud.watch=true` and `wud.tag.include=^\d{4}\.\d{1,2}\.\d{1,2}-T\d{6}$` select timestamped releases. Read-only NAS inspection found the local WUD watcher scheduled at 04:00 with watch-by-default disabled; the photography label explicitly opts in.

WUD tag discovery and Docker image pulls are independent. Both can be anonymous for the verified public photography package; other private applications still require their own credentials.

WUD's host Docker trigger decides replacement timing; labels alone neither schedule backups nor prove updates occur. Confirm that trigger and the saved Unraid template retain the selected version after replacement, so a later Apply does not revert it.

Before each update, pause replacement for photography, stop its sole backend and take the paired checkpoint below. Allow replacement only after the backup completes; if unattended backup coordination is unavailable, the operator must arrange it before leaving WUD updates enabled.

After replacement, check logs, `/health`, frontend assets, saved albums/chapters and thumbnail delivery on LAN and Tailscale. Restart invalidates editing credentials/grants, not saved albums; clients must reconnect and reconcile drafts explicitly.

## Backup and recovery

SQLite and referenced thumbnails form one recovery unit. Keep independent originals backups and preserve the image version/digest, template, application configuration and seed journals with each checkpoint.

1. Pause WUD replacement and stop `photography` in Unraid. Confirm no backend uses the database before taking a backup.
2. Back up **both** approved photography data and thumbnail directories in the same stopped-state checkpoint. Preserve SQLite sidecars if present; copying only a live `.db` can omit committed WAL data.
3. Verify the checkpoint completed and both directories are included before restarting or updating. Retain earlier checkpoints independently; backup of appdata must not omit thumbnails.

For failed startup, preserve logs and the failed database/thumbnail pair before intervention. Migration/configuration/permission errors are failures to investigate, not permission to delete data or reseed.

To recover, stop the backend and pause replacement, choose a compatible published image **and its matching database/thumbnail checkpoint**, restore both while stopped, verify ownership, then apply that image version. Run health and saved-content/thumbnail checks before reenabling replacement.

**Selecting an older image does not reverse database migrations.** It may not read the newer database; restoring an earlier checkpoint can lose later edits. Preserve that loss decision and never restore newer confirmed seed journals against an older database checkpoint.

Monitor process availability, logs and free space at the NAS. Logs rotate at 10 MB with five files; inspect `docker logs photography` for startup/migration/native-library failures. This deployment adds no metrics service or automatic recovery.

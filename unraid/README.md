# NAS runbook

## Publish

With maintainer authorization, dispatch [release](../.github/workflows/release.yml) from main. Record its version, digest and revision; [pipeline contracts](../ci/specs/contracts.md) defines completion.

If an image is published but visibility verification fails, make the package public and verify anonymous access. If tagging remains incomplete, verify the image revision and absent tag; set `VERSION` and `VERIFIED_REVISION`, then recover without republishing:

```sh
git fetch origin main --tags
git tag -a "v$VERSION" "$VERIFIED_REVISION" -m "Photography $VERSION"
git push origin "refs/tags/v$VERSION"
```

Use the configured signing identity. Duplicate versions and registry errors require investigation, not replacement.

## Install

Obtain authorization. Preserve existing `layout-tool`, originals and retained spike/NAS state; replacing retained state requires separate owner approval. Keep secrets outside Git and stop only processes owned by the work.

1. Recheck the [template](photography.xml) and [image identity](../containers/photography/Dockerfile). Prepare only approved empty/new state directories writable by that identity; verify read/traverse access to originals and chapter directories. ACL inheritance alone does not prove container access.
2. Complete an authorized smoke deployment with a temporary name, disposable state and WUD disabled. Verify API/frontend, editing events, photo processing, thumbnails and persistence across restart; remove only identified disposable resources.
3. Copy the template to `/boot/config/plugins/dockerMan/templates-user/my-photography.xml`. Choose a verified release, compare settings with source, arrange update checkpoints, then Apply.
4. Inspect runtime identity and mounts, startup logs and readiness; confirm API-backed navigation from LAN and Tailscale before seeding.

Restrict the unauthenticated application at the network boundary; never forward it publicly. Preserve the browser origin and existing registry credentials. Proxies must preserve the [HTTP/event contract](../specs/http-api.openapi.json), disable event buffering/caching/compression and permit long-lived connections.

## Populate albums

Authorize seeding separately after installation checks. Private seed clients, inputs and journals remain outside Git and release gates.

Revalidate API/schema compatibility and exact input/mount plans. Use fresh destination identities and receipts, not rehearsal databases or journals. Offline stages require separate approval, a stopped backend and local SQLite access; never operate live SQLite over a network filesystem.

## Update

Confirm WUD's Docker trigger exists; labels alone establish no schedule or backup.

1. Pause replacement and take the checkpoint below before each update. Arrange backup coordination before enabling unattended updates.
2. Replace, then verify the trigger and saved template retain the selected version.
3. Check readiness, saved content and thumbnails from LAN/Tailscale; follow [runtime recovery](../specs/operational.md#state-and-recovery).

## Backup and recovery

Pause replacement and stop the backend; confirm no database user remains. Back up approved data and thumbnail directories together, including SQLite sidecars. Verify both before restarting; retain older checkpoints, independent originals backups, image identity, configuration and seed journals.

For failed startup, preserve logs and failed state before intervention. Migration/permission errors do not authorize deletion or reseeding.

Restore a compatible image with its matching data/thumbnail checkpoint while stopped; verify ownership and saved content before reenabling replacement. Older images do not reverse migrations. Earlier checkpoints can lose edits; obtain approval for that loss and never combine newer seed journals with older restored state.

# Album Layout Studio on Unraid

Use this runbook for authorized publication and Unraid installation/update actions. [Service operations](../specs/operational.md#run-on-the-nas) owns the deployment diagram, network/storage policy and recovery procedure.

## Publish

Explicitly dispatch [publish](../.github/workflows/release.yml) from **main**. Only trusted maintainers should dispatch it: publishing runs repository code with `contents: write` and `packages: write`; credentials are the ephemeral Actions token, never NAS configuration.

The workflow verifies the source, publishes a timestamped image and creates its matching annotated `v` Git tag. [Release workflow](../.github/workflows/release.yml) owns exact gates, registry checks and tagging; [Dagger build environment](../.dagger/src/buildEnv.ts) owns the target platform.

The GHCR package must be **public** for anonymous NAS and WUD pulls. GitHub repository visibility does not establish package visibility: new GHCR packages initially default to private.

On the first dispatch, publication can succeed while public-visibility verification stops release tagging. Open the album-layout-studio package settings in GitHub and change its visibility to public; verify anonymous pulls, then recover the matching Git tag as below without republishing or replacing the existing image version.

An existing non-public package or duplicate image/Git tag stops publication. Registry lookup errors also stop the workflow; a successful release requires both public-package visibility and an anonymous manifest read.

Record the exact version, digest and revision from the workflow. If publication succeeds but tag creation/push fails, inspect the published image's `org.opencontainers.image.revision` label, confirm it equals the built main revision, and annotate **that exact commit**:

Set `VERSION` to that published timestamped version and `VERIFIED_REVISION` to its verified full commit SHA before running the recovery commands:

```sh
git fetch origin main --tags
git tag -a "v$VERSION" "$VERIFIED_REVISION" -m "Album Layout Studio $VERSION"
git push origin "refs/tags/v$VERSION"
```

Use the configured personal signing identity. These commands are operator actions, not authorization to publish; confirm the tag is absent before creating it.

## First installation

Read the [template](album-layout-studio.xml) before installation; it owns network, listener, runtime mounts and WUD settings. Preserve the existing `layout-tool`, its retained albums and originals.

Before production installation, obtain authorization for an isolated NAS smoke deployment. Use a temporary container name and disposable data/thumbnail directories, keeping the original-photo mount read-only; disable WUD monitoring for that temporary container.

Confirm frontend/API delivery, editing SSE, actual photo processing and thumbnail delivery, then restart and confirm saved state remains available. Check runtime UID/GID, mounts and access permissions on the NAS; remove only the temporary container and its disposable state afterward.

1. Obtain explicit installation authorization. Create only the approved photography data/thumbnail directories, granting the Dockerfile's runtime identity write/traverse access; preserve permissions on originals and existing appdata.
2. Verify that identity can read/traverse the selected originals and all chapter subdirectories. NAS user-share ACL inheritance alone does not prove container access; resolve any access issue with narrowly scoped permissions.
3. Confirm the published version is anonymously pullable from GHCR. Public images need no Docker login or WUD registry credentials; remove neither existing NAS credentials nor other applications' private GHCR configuration.
4. Copy `unraid/album-layout-studio.xml` to `/boot/config/plugins/dockerMan/templates-user/my-album-layout-studio.xml` on the approved NAS. In the Docker page, replace `REPLACE_WITH_PUBLISHED_VERSION` with an existing timestamped version; confirm network, port, read-only originals and both writable mounts before Apply.
5. Confirm the saved template retains its restart policy, bounded logs and WUD labels. Arrange [stopped-state checkpoints](../specs/operational.md#recovery-and-maintenance) before allowing unattended replacement; labels do not create backups.
6. Apply once, inspect startup logs, and check the application from both LAN and Tailscale. Inspect the actual runtime UID/GID and mounts before any seeding.

```sh
ssh root@192.168.1.3 'docker exec album-layout-studio id'
ssh root@192.168.1.3 'docker inspect album-layout-studio --format "{{json .Mounts}}"'
curl -fsS http://192.168.1.3:10002/health
```

Compare runtime identity/mount modes with the template and Dockerfile; expect health JSON `{"status":"ok"}`. Open the UI and verify saved album navigation, photo processing and thumbnail delivery from LAN and Tailscale.

Keep the browser origin stable for remembered identity and local drafts. Apply the service [network/proxy requirements](../specs/operational.md#run-on-the-nas) before exposing the listener.

## One-time album population

Populate albums **after** the new app is running and its mounts/health are verified. Seeding is an explicitly authorized, one-time operator action, never startup, a release gate or an update hook.

The saved seed client and its **2020–2026** extension are private local delivery artifacts, outside the reviewed repository and release gates. Preserve them independently of Git.

Use fresh NAS destination plans and journals; never copy the rehearsal SQLite database or reuse its IDs/receipts. Revalidate the client's compatibility with the installed API and schema before approving its writes; historical local approvals do not authorize the NAS destination.

The client runtime must resolve originals at the template's container path; approve its exact mounts and input plans before execution.

Preserve the private inputs and journals outside Git and the production image. Any offline seed stage needs a separately approved runtime with the same read-only originals, an accessible local SQLite mount and the backend stopped; do not operate live SQLite over a network filesystem.

## Updates and authentication

The template's labels opt into the existing WUD watcher and restrict eligible versions. WUD's Docker trigger owns replacement timing; confirm it also retains the chosen version in the saved Unraid template so a later Apply does not revert it.

WUD tag discovery and Docker image pulls are independent. Both can be anonymous for the verified public album-layout-studio package; other private applications still require their own credentials.

Before each update, complete the service [backup procedure](../specs/operational.md#recovery-and-maintenance). If unattended backup coordination is unavailable, keep album-layout-studio replacement paused until the operator completes that checkpoint.

After replacement, rerun the installation runtime/health checks and verify saved albums/chapters and thumbnail delivery on LAN and Tailscale. Follow [editing recovery](../specs/operational.md#editing-access-and-recovery) for open browsers.

## Diagnose or recover

```sh
ssh root@192.168.1.3 'docker logs --tail 200 album-layout-studio'
```

Retain this output with the installed image version and runtime inspection. Use [service recovery](../specs/operational.md#recovery-and-maintenance) for state restoration; preserve private seed journals alongside the matching checkpoint.

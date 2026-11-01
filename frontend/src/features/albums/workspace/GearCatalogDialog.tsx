import { For, Show, createEffect, createMemo, createSignal, onCleanup, onMount } from "solid-js";
import { describeCompatibility, gearMounts, sensorFormats, type Camera, type GearCatalog, type GearClient, type Lens } from "../model/Gear";
import { AlbumRequestError } from "../model/Album";
import { describeFailure } from "../model/Presentation";
import { createContentSave, type ContentSave } from "./ContentSave";
import { buildCameraEdit, buildCatalogCommand, buildLensEdit, matchesCatalogCommand, type CatalogCommand, type CatalogEdit } from "./CatalogEdit";

export function GearCatalogDialog(props: {
  client: GearClient;
  onClose: () => void;
  returnFocus: () => void;
  onChanged: (catalog: GearCatalog) => void;
}) {
  const [catalog, setCatalog] = createSignal<GearCatalog>();
  const [edit, setEdit] = createSignal<CatalogEdit>();
  const [deleting, setDeleting] = createSignal(false);
  const [loading, setLoading] = createSignal(false);
  const [error, setError] = createSignal<string>();
  const controller = new AbortController();
  let dialog!: HTMLDialogElement;
  let status!: HTMLDivElement;
  let active = true;
  let maybeReceipt: Camera | Lens | undefined;
  const save: ContentSave<CatalogCommand, Camera | Lens | undefined> = createContentSave({
    save: async (command) => {
      if (command.operation === "delete") {
        if (command.kind === "camera") await props.client.deleteCamera(command.id);
        else await props.client.deleteLens(command.id);
      } else if (command.kind === "camera") {
        if (command.maybeId === undefined) maybeReceipt = await props.client.createCamera(command.details);
        else await props.client.updateCamera(command.maybeId, command.details);
      } else if (command.maybeId === undefined) maybeReceipt = await props.client.createLens(command.details);
      else await props.client.updateLens(command.maybeId, command.details);
      return undefined;
    },
    read: async (command) => {
      const current = await props.client.gearCatalog(controller.signal);
      if (!active) throw new Error("Catalog editor is closed");
      setCatalog(current);
      props.onChanged(current);
      const id = command.operation === "delete" ? command.id : (maybeReceipt?.id ?? command.maybeId);
      const entries: readonly (Camera | Lens)[] = command.kind === "camera" ? current.cameras : current.lenses;
      const entry =
        id === undefined && command.operation === "save"
          ? entries.find((item) => item.code === command.details.code)
          : entries.find((item) => item.id === id);
      if (entry === undefined && command.operation === "save" && save.recovery() === "acknowledged")
        throw new AlbumRequestError(404, "The saved catalog entry could not be found. Read saved gear again.");
      return entry;
    },
    equal: matchesCatalogCommand,
    onSaved: () => {
      setEdit(undefined);
      setDeleting(false);
      maybeReceipt = undefined;
      queueMicrotask(() => {
        dialog.querySelector<HTMLButtonElement>("button:not(:disabled)")?.focus();
      });
    },
  });
  const reason = createMemo(() => (loading() ? "Loading saved gear." : save.unavailable()));
  createEffect(() => {
    const failure = error() ?? save.error();
    if (failure !== undefined && !loading() && !save.pending()) status.focus();
  });
  onMount(() => {
    dialog.showModal();
    void load();
  });
  onCleanup(() => {
    active = false;
    controller.abort();
    dialog.close();
    props.returnFocus();
  });
  async function load() {
    setLoading(true);
    setError(undefined);
    try {
      const current = await props.client.gearCatalog(controller.signal);
      if (active) {
        setCatalog(current);
        props.onChanged(current);
      }
    } catch (failure) {
      if (active) setError(describeFailure(failure));
    } finally {
      if (active) setLoading(false);
    }
  }
  function choose(value: CatalogEdit) {
    setDeleting(false);
    setError(undefined);
    save.cancel();
    setEdit(value);
    queueMicrotask(() => {
      dialog.querySelector<HTMLInputElement>("input")?.focus();
    });
  }
  function cancel() {
    if (reason() !== undefined) return;
    if (edit() === undefined) props.onClose();
    else {
      save.cancel();
      setEdit(undefined);
      setDeleting(false);
      setError(undefined);
      queueMicrotask(() => {
        dialog.querySelector<HTMLButtonElement>("button:not(:disabled)")?.focus();
      });
    }
  }
  function submit(event: SubmitEvent, current: CatalogEdit) {
    event.preventDefault();
    if (reason() !== undefined) return;
    const command = buildCatalogCommand(current, deleting());
    if (typeof command === "string") {
      setError(command);
      return;
    }
    save.begin(command);
    void save.commit();
  }
  return (
    <dialog
      ref={(element) => {
        dialog = element;
      }}
      class="album-dialog content-dialog gear-catalog-dialog"
      aria-label="Gear catalog"
      onCancel={(event) => {
        event.preventDefault();
        cancel();
      }}
    >
      <header class="dialog-heading">
        <h2>Gear catalog</h2>
      </header>
      <Show
        when={edit()}
        fallback={
          <>
            <Show when={catalog()}>
              {(current) => (
                <div class="gear-catalog-columns">
                  <section aria-label="Camera catalog">
                    <header class="gear-catalog-heading">
                      <h3>Cameras</h3>
                      <button
                        type="button"
                        class="icon-button"
                        aria-label="Add camera"
                        title="Add camera"
                        disabled={reason() !== undefined}
                        aria-describedby="gear-feedback"
                        onClick={() => {
                          choose({
                            kind: "camera",
                            maybeId: undefined,
                            maybeCode: undefined,
                            name: "",
                            cameraKind: "fixed_lens",
                            mount: "",
                            format: "",
                          });
                        }}
                      >
                        <svg viewBox="0 0 24 24" aria-hidden="true">
                          <path d="M12 5v14M5 12h14" />
                        </svg>
                      </button>
                    </header>
                    <For each={current().cameras}>
                      {(camera) => (
                        <div class="gear-catalog-row">
                          <div>
                            <span>{camera.name}</span>
                            <small>{camera.kind === "fixed_lens" ? "Fixed lens" : describeCompatibility(camera.mount, camera.sensorFormat)}</small>
                          </div>
                          <button
                            type="button"
                            class="icon-button"
                            aria-label={`Edit ${camera.name}`}
                            title={`Edit ${camera.name}`}
                            disabled={reason() !== undefined}
                            aria-describedby="gear-feedback"
                            onClick={() => {
                              choose(buildCameraEdit(camera));
                            }}
                          >
                            <svg viewBox="0 0 24 24" aria-hidden="true">
                              <path d="m15 4 5 5M4 20l5-1L20 8a2 2 0 0 0-5-5L4 14z" />
                            </svg>
                          </button>
                        </div>
                      )}
                    </For>
                  </section>
                  <section aria-label="Lens catalog">
                    <header class="gear-catalog-heading">
                      <h3>Lenses</h3>
                      <button
                        type="button"
                        class="icon-button"
                        aria-label="Add lens"
                        title="Add lens"
                        disabled={reason() !== undefined}
                        aria-describedby="gear-feedback"
                        onClick={() => {
                          choose({
                            kind: "lens",
                            maybeId: undefined,
                            maybeCode: undefined,
                            name: "",
                            cameraKind: "interchangeable_lens",
                            mount: "",
                            format: "",
                          });
                        }}
                      >
                        <svg viewBox="0 0 24 24" aria-hidden="true">
                          <path d="M12 5v14M5 12h14" />
                        </svg>
                      </button>
                    </header>
                    <For each={current().lenses}>
                      {(lens) => (
                        <div class="gear-catalog-row">
                          <div>
                            <span>{lens.name}</span>
                            <small>{describeCompatibility(lens.mount, lens.coverage)}</small>
                          </div>
                          <button
                            type="button"
                            class="icon-button"
                            aria-label={`Edit ${lens.name}`}
                            title={`Edit ${lens.name}`}
                            disabled={reason() !== undefined}
                            aria-describedby="gear-feedback"
                            onClick={() => {
                              choose(buildLensEdit(lens));
                            }}
                          >
                            <svg viewBox="0 0 24 24" aria-hidden="true">
                              <path d="m15 4 5 5M4 20l5-1L20 8a2 2 0 0 0-5-5L4 14z" />
                            </svg>
                          </button>
                        </div>
                      )}
                    </For>
                  </section>
                </div>
              )}
            </Show>
            <div class="dialog-actions">
              <button type="button" class="secondary" disabled={reason() !== undefined} aria-describedby="gear-feedback" onClick={cancel}>
                Close
              </button>
            </div>
          </>
        }
      >
        {(current) => (
          <form
            aria-label={current().kind === "camera" ? "Camera details" : "Lens details"}
            onSubmit={(event) => {
              submit(event, current());
            }}
          >
            <Show
              when={deleting()}
              fallback={
                <>
                  <label>
                    Name
                    <input
                      value={current().name}
                      disabled={reason() !== undefined}
                      aria-describedby="gear-feedback"
                      onInput={(event) => {
                        setEdit({ ...current(), name: event.currentTarget.value });
                      }}
                    />
                  </label>
                  <Show when={current().kind === "camera"}>
                    <label>
                      Camera type
                      <select
                        value={current().cameraKind}
                        disabled={reason() !== undefined}
                        aria-describedby="gear-feedback"
                        onChange={(event) => {
                          const kind = event.currentTarget.value;
                          /* v8 ignore else */
                          if (kind === "fixed_lens" || kind === "interchangeable_lens") setEdit({ ...current(), cameraKind: kind });
                        }}
                      >
                        <option value="fixed_lens">Fixed lens</option>
                        <option value="interchangeable_lens">Interchangeable lens</option>
                      </select>
                    </label>
                  </Show>
                  <Show when={current().kind === "lens" || current().cameraKind === "interchangeable_lens"}>
                    <div class="gear-compatibility-fields">
                      <label>
                        Mount
                        <select
                          value={current().mount}
                          disabled={reason() !== undefined}
                          aria-describedby="gear-feedback"
                          onChange={(event) => {
                            setEdit({ ...current(), mount: event.currentTarget.value });
                          }}
                        >
                          <option value="">Choose mount</option>
                          <For each={gearMounts}>{(mount) => <option value={mount.id}>{mount.name}</option>}</For>
                        </select>
                      </label>
                      <label>
                        {current().kind === "camera" ? "Sensor format" : "Lens coverage"}
                        <select
                          value={current().format}
                          disabled={reason() !== undefined}
                          aria-describedby="gear-feedback"
                          onChange={(event) => {
                            setEdit({ ...current(), format: event.currentTarget.value });
                          }}
                        >
                          <option value="">Choose format</option>
                          <For each={sensorFormats}>{(format) => <option value={format.id}>{format.name}</option>}</For>
                        </select>
                      </label>
                    </div>
                    <p class="content-help">Mounts must match. Full-frame cameras require full-frame lens coverage.</p>
                  </Show>
                </>
              }
            >
              <p>Delete {current().name}? Gear used by a chapter cannot be deleted.</p>
            </Show>
            <div class="dialog-actions">
              <button type="button" class="secondary" disabled={reason() !== undefined} aria-describedby="gear-feedback" onClick={cancel}>
                Cancel
              </button>
              <Show when={current().maybeId !== undefined && !deleting()}>
                <button
                  type="button"
                  class="secondary"
                  disabled={reason() !== undefined}
                  aria-describedby="gear-feedback"
                  onClick={() => {
                    setDeleting(true);
                  }}
                >
                  Delete
                </button>
              </Show>
              <button type="submit" disabled={reason() !== undefined} aria-describedby="gear-feedback">
                {deleting() ? "Delete permanently" : "Save"}
              </button>
            </div>
          </form>
        )}
      </Show>
      <div
        ref={(element) => {
          status = element;
        }}
        id="gear-feedback"
        tabindex="-1"
      >
        <Show when={reason()}>
          {(message) => (
            <p role="status" class="control-help">
              {message()}
            </p>
          )}
        </Show>
        <Show when={error() ?? save.error()}>{(message) => <p role="alert">{message()}</p>}</Show>
        <Show when={save.recovery() !== undefined || (catalog() === undefined && !loading())}>
          <button
            type="button"
            disabled={loading() || save.pending()}
            aria-describedby="gear-feedback"
            onClick={() => {
              if (save.recovery() === undefined) void load();
              else void save.reconcile();
            }}
          >
            Reload saved gear
          </button>
        </Show>
      </div>
    </dialog>
  );
}

import { For, Show, createMemo, onCleanup } from "solid-js";
import { describeCompatibility, isLensCompatible, type CameraSelection, type GearCatalog } from "../model/Gear";

export function ChapterGearFields(props: {
  catalog: GearCatalog;
  selected: readonly CameraSelection[];
  disabled: boolean;
  onChange: (selection: readonly CameraSelection[]) => void;
  onManage: (opener: HTMLButtonElement) => void;
}) {
  const remainingCameras = () => props.catalog.cameras.filter((item) => !props.selected.some((selection) => selection.cameraId === item.id));
  let addCamera: HTMLSelectElement | undefined;
  let manage!: HTMLButtonElement;
  const rows = new Map<string, HTMLDivElement>();
  return (
    <fieldset class="chapter-gear-fields" disabled={props.disabled} aria-describedby="chapter-feedback">
      <legend>
        Gear
        <button
          ref={(element) => {
            manage = element;
          }}
          type="button"
          class="icon-button gear-manage"
          aria-label="Manage catalog"
          title="Manage catalog"
          disabled={props.disabled}
          aria-describedby="chapter-feedback"
          onClick={(event) => {
            props.onManage(event.currentTarget);
          }}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="m15 4 5 5M4 20l5-1L20 8a2 2 0 0 0-5-5L4 14z" />
          </svg>
        </button>
      </legend>
      <For each={props.selected.map((selection) => selection.cameraId)}>
        {(cameraId) => {
          const selection = () => props.selected.find((item) => item.cameraId === cameraId);
          const camera = () => props.catalog.cameras.find((item) => item.id === cameraId);
          const compatibleLenses = createMemo(() => {
            const body = camera();
            return body === undefined ? [] : props.catalog.lenses.filter((item) => isLensCompatible(body, item));
          });
          const cameraName = () => camera()?.name ?? "camera";
          const compatibility = () => {
            const body = camera();
            return body?.kind === "interchangeable_lens" ? describeCompatibility(body.mount, body.sensorFormat) : undefined;
          };
          let row!: HTMLDivElement;
          let lensSelect: HTMLSelectElement | undefined;
          onCleanup(() => {
            rows.delete(cameraId);
          });
          function focusLenses() {
            queueMicrotask(() => {
              if (lensSelect?.isConnected === true) lensSelect.focus();
              else row.focus();
            });
          }
          return (
            <Show when={selection()}>
              {(selected) => {
                const remainingLenses = () => compatibleLenses().filter((lens) => !selected().lensIds.includes(lens.id));
                return (
                  <div
                    ref={(element) => {
                      row = element;
                      rows.set(cameraId, element);
                    }}
                    class="chapter-camera"
                    role="group"
                    aria-label={`${camera()?.name ?? "Unavailable camera"} gear`}
                    tabindex="-1"
                  >
                    <div class="chapter-camera-heading">
                      <div>
                        <span>{camera()?.name ?? "Camera no longer available"}</span>
                        <Show when={compatibility()}>{(description) => <small class="gear-compatibility">{description()}</small>}</Show>
                      </div>
                      <button
                        type="button"
                        class="gear-remove"
                        aria-label={`Remove ${cameraName()} from chapter`}
                        aria-describedby="chapter-feedback"
                        onClick={() => {
                          props.onChange(props.selected.filter((item) => item.cameraId !== cameraId));
                          queueMicrotask(() => {
                            if (addCamera?.isConnected === true) addCamera.focus();
                            else manage.focus();
                          });
                        }}
                      >
                        ×
                      </button>
                    </div>
                    <Show when={camera()?.kind === "interchangeable_lens"}>
                      <Show when={compatibleLenses().length === 0}>
                        <p class="content-help">No compatible lenses in the catalog.</p>
                      </Show>
                      <div class="chapter-lens-tags">
                        <For each={selected().lensIds}>
                          {(id) => {
                            const lens = () => props.catalog.lenses.find((item) => item.id === id);
                            return (
                              <span class="chapter-lens-tag">
                                {lens()?.name ?? "Lens no longer available"}
                                <button
                                  type="button"
                                  class="gear-remove"
                                  aria-label={`Remove ${lens()?.name ?? "lens"} from chapter`}
                                  aria-describedby="chapter-feedback"
                                  onClick={() => {
                                    props.onChange(
                                      props.selected.map((item) =>
                                        item.cameraId === cameraId ? { ...item, lensIds: item.lensIds.filter((lensId) => lensId !== id) } : item,
                                      ),
                                    );
                                    focusLenses();
                                  }}
                                >
                                  ×
                                </button>
                              </span>
                            );
                          }}
                        </For>
                      </div>
                      <Show when={remainingLenses().length > 0}>
                        <select
                          ref={(element) => {
                            lensSelect = element;
                          }}
                          aria-label={`Add lens for ${cameraName()}`}
                          value=""
                          aria-describedby="chapter-feedback"
                          onChange={(event) => {
                            const id = event.currentTarget.value;
                            event.currentTarget.value = "";
                            if (id !== "") {
                              props.onChange(
                                props.selected.map((item) => (item.cameraId === cameraId ? { ...item, lensIds: [...item.lensIds, id] } : item)),
                              );
                              focusLenses();
                            }
                          }}
                        >
                          <option value="">Add lens…</option>
                          <For each={remainingLenses()}>{(lens) => <option value={lens.id}>{lens.name}</option>}</For>
                        </select>
                      </Show>
                    </Show>
                  </div>
                );
              }}
            </Show>
          );
        }}
      </For>
      <div class="chapter-gear-add">
        <Show when={remainingCameras().length > 0}>
          <select
            ref={(element) => {
              addCamera = element;
            }}
            aria-label="Add camera"
            value=""
            aria-describedby="chapter-feedback"
            onChange={(event) => {
              const id = event.currentTarget.value;
              event.currentTarget.value = "";
              if (id !== "") {
                props.onChange([...props.selected, { cameraId: id, lensIds: [] }]);
                queueMicrotask(() => {
                  rows.get(id)?.focus();
                });
              }
            }}
          >
            <option value="">Add camera…</option>
            <For each={remainingCameras()}>{(camera) => <option value={camera.id}>{camera.name}</option>}</For>
          </select>
        </Show>
      </div>
    </fieldset>
  );
}

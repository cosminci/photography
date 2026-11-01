import { cleanup, fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GearCatalogDialog } from "../../../src/features/albums/workspace/GearCatalogDialog";
import { AcknowledgedWriteError, AlbumRequestError } from "../../../src/features/albums/model/Album";
import type { Camera, GearCatalog, GearClient, Lens } from "../../../src/features/albums/model/Gear";

const camera: Camera = {
  id: "camera",
  code: "original camera code",
  name: "Body",
  kind: "interchangeable_lens",
  mount: "sony_e",
  sensorFormat: "aps_c",
};
const fixed: Camera = { id: "fixed", code: "fixed code", name: "Phone", kind: "fixed_lens" };
const lens: Lens = { id: "lens", code: "original lens code", name: "Lens", mount: "sony_e", coverage: "full_frame" };
const catalog: GearCatalog = { cameras: [camera, fixed], lenses: [lens] };
const nativeShow = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "showModal");
const nativeClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "close");
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.open = true;
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, "close", {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.open = false;
    },
  });
});
afterEach(() => {
  cleanup();
  if (nativeShow === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal");
  else Object.defineProperty(HTMLDialogElement.prototype, "showModal", nativeShow);
  if (nativeClose === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
  else Object.defineProperty(HTMLDialogElement.prototype, "close", nativeClose);
});

describe("gear catalog editor", () => {
  it("should ignore failed catalog acquisition after disposal and abort the owned request", async () => {
    let fail!: (error: Error) => void;
    const pending = new Promise<GearCatalog>((_resolve, reject) => {
      fail = reject;
    });
    const client = buildClient({ gearCatalog: vi.fn().mockReturnValue(pending) });
    const changed = vi.fn();
    const view = render(() => <GearCatalogDialog client={client} onChanged={changed} onClose={vi.fn()} returnFocus={vi.fn()} />);
    view.unmount();
    fail(new Error("Offline after closing"));
    await expect(pending).rejects.toThrow("Offline after closing");
    expect(vi.mocked(client.gearCatalog).mock.calls[0]?.[0]?.aborted).toBe(true);
    expect(changed).not.toHaveBeenCalled();
  });
  it("should reconcile an uncertain create by code before permitting a retry and ignore a late acknowledged read after disposal", async () => {
    const created: Camera = { id: "created", code: "New phone", name: "New phone", kind: "fixed_lens" };
    const client = buildClient({
      createCamera: vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue(created),
      gearCatalog: vi
        .fn()
        .mockResolvedValueOnce(catalog)
        .mockResolvedValueOnce(catalog)
        .mockResolvedValue({ ...catalog, cameras: [...catalog.cameras, created] }),
    });
    const changed = vi.fn();
    const view = render(() => <GearCatalogDialog client={client} onChanged={changed} onClose={vi.fn()} returnFocus={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add camera" }));
    fireEvent.input(screen.getByRole("textbox", { name: "Name" }), { target: { value: created.name } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "Reload saved gear" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Save" })).toBeEnabled());
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(created.name);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByRole("button", { name: "Edit New phone" });
    expect(client.createCamera).toHaveBeenCalledTimes(2);
    view.unmount();

    let complete!: (value: GearCatalog) => void;
    const pending = new Promise<GearCatalog>((resolve) => {
      complete = resolve;
    });
    const lateClient = buildClient({
      createCamera: vi.fn().mockResolvedValue(created),
      gearCatalog: vi.fn().mockResolvedValueOnce(catalog).mockReturnValue(pending),
    });
    const published = vi.fn();
    const late = render(() => <GearCatalogDialog client={lateClient} onChanged={published} onClose={vi.fn()} returnFocus={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add camera" }));
    fireEvent.input(screen.getByRole("textbox", { name: "Name" }), { target: { value: created.name } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    fireEvent.submit(screen.getByRole("form", { name: "Camera details" }));
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    await waitFor(() => {
      expect(lateClient.gearCatalog).toHaveBeenCalledTimes(2);
    });
    late.unmount();
    complete({ ...catalog, cameras: [...catalog.cameras, created] });
    await pending;
    expect(lateClient.createCamera).toHaveBeenCalledOnce();
    expect(published).toHaveBeenCalledOnce();
  });
  it.each(["camera", "lens"] as const)("should create a %s with the entered name as code and explicit compatibility", async (kind) => {
    const createdName = "  山  ";
    const created = kind === "camera" ? { ...camera, name: createdName, code: createdName } : { ...lens, name: createdName, code: createdName };
    const saved = kind === "camera" ? { ...catalog, cameras: [created, fixed] } : { ...catalog, lenses: [created] };
    const client = buildClient({
      gearCatalog: vi.fn().mockResolvedValueOnce(catalog).mockResolvedValue(saved),
      createCamera: vi.fn().mockResolvedValue(created),
      createLens: vi.fn().mockResolvedValue(created),
    });
    const onChanged = vi.fn();
    const close = vi.fn();
    const returnFocus = vi.fn();
    const view = render(() => <GearCatalogDialog client={client} onChanged={onChanged} onClose={close} returnFocus={returnFocus} />);
    fireEvent.click(await screen.findByRole("button", { name: `Add ${kind}` }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Name" })).toHaveFocus());
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a name.");
    fireEvent.input(screen.getByRole("textbox", { name: "Name" }), { target: { value: createdName } });
    if (kind === "camera") fireEvent.change(screen.getByRole("combobox", { name: "Camera type" }), { target: { value: "interchangeable_lens" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Choose a mount and sensor format or lens coverage.");
    fireEvent.change(screen.getByRole("combobox", { name: "Mount" }), { target: { value: "sony_e" } });
    fireEvent.change(screen.getByRole("combobox", { name: kind === "camera" ? "Sensor format" : "Lens coverage" }), {
      target: { value: kind === "camera" ? "aps_c" : "full_frame" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByRole("button", { name: "Edit 山" });

    expect(kind === "camera" ? client.createCamera : client.createLens).toHaveBeenCalledExactlyOnceWith(
      kind === "camera"
        ? { code: createdName, name: createdName, kind: "interchangeable_lens", mount: "sony_e", sensorFormat: "aps_c" }
        : { code: createdName, name: createdName, mount: "sony_e", coverage: "full_frame" },
    );
    expect(onChanged).toHaveBeenLastCalledWith(saved);
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(close).toHaveBeenCalledOnce();
    view.unmount();
    expect(returnFocus).toHaveBeenCalledOnce();
  });
  it.each(["camera", "fixed", "lens"] as const)("should edit %s preserving its existing code and delete only that resource", async (kind) => {
    const entry = kind === "camera" ? camera : kind === "fixed" ? fixed : lens;
    const changed = { ...entry, name: "Renamed" };
    const saved = kind === "lens" ? { ...catalog, lenses: [changed] } : { ...catalog, cameras: [changed] };
    const empty: GearCatalog = { cameras: [], lenses: [] };
    const client = buildClient({ gearCatalog: vi.fn().mockResolvedValueOnce(catalog).mockResolvedValueOnce(saved).mockResolvedValue(empty) });
    render(() => <GearCatalogDialog client={client} onChanged={vi.fn()} onClose={vi.fn()} returnFocus={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: `Edit ${entry.name}` }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Name" })).toBeEnabled());
    fireEvent.input(screen.getByRole("textbox", { name: "Name" }), { target: { value: changed.name } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit Renamed" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Edit Renamed" }));
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(screen.getByText(/Delete Renamed/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete permanently" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Add camera" })).toBeEnabled());

    const { id, ...details } = changed;
    expect(kind === "lens" ? client.updateLens : client.updateCamera).toHaveBeenCalledExactlyOnceWith(id, details);
    expect(kind === "lens" ? client.deleteLens : client.deleteCamera).toHaveBeenCalledExactlyOnceWith(id);
    expect(client.gearCatalog).toHaveBeenCalledTimes(3);
  });
  it.each([
    { failure: new AlbumRequestError(409, "Used catalog entry"), phase: "rejected" },
    { failure: new Error("offline"), phase: "uncertain" },
    { failure: new AcknowledgedWriteError("Malformed created response"), phase: "acknowledged" },
  ])("should retain $phase drafts and reconcile without replay after $failure", async ({ failure, phase }) => {
    const saved = { ...catalog, cameras: [{ ...fixed, name: "Changed" }] };
    const client = buildClient({
      updateCamera: vi.fn().mockRejectedValueOnce(failure).mockResolvedValue(undefined),
      gearCatalog: vi.fn().mockResolvedValueOnce(catalog).mockResolvedValue(saved),
    });
    render(() => <GearCatalogDialog client={client} onChanged={vi.fn()} onClose={vi.fn()} returnFocus={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit Phone" }));
    fireEvent.input(screen.getByRole("textbox", { name: "Name" }), { target: { value: "Changed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    if (phase === "acknowledged") await screen.findByRole("button", { name: "Edit Changed" });
    else {
      await screen.findByRole("alert");
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Changed");
      if (phase === "rejected") {
        expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
        fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
        await screen.findByRole("button", { name: "Edit Phone" });
      } else {
        expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
        fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
        expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Changed");
        fireEvent.click(screen.getByRole("button", { name: "Reload saved gear" }));
        await screen.findByRole("button", { name: "Edit Changed" });
      }
    }
    expect(client.updateCamera).toHaveBeenCalledOnce();
  });
  it("should preserve acknowledged creates through missing/failed refresh and retry reads only", async () => {
    const client = buildClient({
      createCamera: vi.fn().mockResolvedValue(fixed),
      gearCatalog: vi
        .fn()
        .mockResolvedValueOnce(catalog)
        .mockRejectedValueOnce(new Error("offline"))
        .mockResolvedValueOnce({ cameras: [], lenses: [] })
        .mockResolvedValue(catalog),
    });
    render(() => <GearCatalogDialog client={client} onChanged={vi.fn()} onClose={vi.fn()} returnFocus={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add camera" }));
    fireEvent.input(screen.getByRole("textbox", { name: "Name" }), { target: { value: fixed.name } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "Reload saved gear" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("The saved catalog entry could not be found"));
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Reload saved gear" }));
    await screen.findByRole("button", { name: "Edit Phone" });

    expect(client.createCamera).toHaveBeenCalledOnce();
    expect(client.gearCatalog).toHaveBeenCalledTimes(4);
  });
  it("should distinguish failed acquisition from an empty catalog and ignore late acquisition after closing", async () => {
    const client = buildClient({ gearCatalog: vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue({ cameras: [], lenses: [] }) });
    const close = vi.fn();
    const changed = vi.fn();
    const view = render(() => <GearCatalogDialog client={client} onChanged={changed} onClose={close} returnFocus={vi.fn()} />);
    await screen.findByRole("alert");
    expect(screen.queryByRole("region", { name: "Camera catalog" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Reload saved gear" }));
    await screen.findByRole("region", { name: "Camera catalog" });
    fireEvent.click(screen.getByRole("button", { name: "Add lens" }));
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    await screen.findByRole("button", { name: "Add lens" });
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    expect(close).toHaveBeenCalledOnce();
    view.unmount();

    let complete!: (value: GearCatalog) => void;
    const pending = new Promise<GearCatalog>((resolve) => {
      complete = resolve;
    });
    const late = render(() => (
      <GearCatalogDialog
        client={buildClient({ gearCatalog: vi.fn().mockReturnValue(pending) })}
        onChanged={changed}
        onClose={vi.fn()}
        returnFocus={vi.fn()}
      />
    ));
    late.unmount();
    complete(catalog);
    await pending;
    expect(changed).toHaveBeenCalledTimes(1);
  });
});

function buildClient(overrides: Partial<GearClient> = {}): GearClient {
  return {
    gearCatalog: vi.fn().mockResolvedValue(catalog),
    chapterGear: vi.fn(),
    saveChapterGear: vi.fn(),
    createCamera: vi.fn(),
    updateCamera: vi.fn().mockResolvedValue(undefined),
    deleteCamera: vi.fn().mockResolvedValue(undefined),
    createLens: vi.fn(),
    updateLens: vi.fn().mockResolvedValue(undefined),
    deleteLens: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

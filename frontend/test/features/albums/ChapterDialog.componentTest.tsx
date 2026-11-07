import { cleanup, fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { ChapterDialog } from "../../../src/features/albums/workspace/ChapterDialog";
import { CountryPicker } from "../../../src/features/albums/workspace/CountryPicker";
import { ChapterGearFields } from "../../../src/features/albums/workspace/ChapterGearFields";
import { GearCatalogDialog } from "../../../src/features/albums/workspace/GearCatalogDialog";
import type { ContentSave, createContentSave } from "../../../src/features/albums/workspace/ContentSave";
import type { ChapterDetails } from "../../../src/features/albums/model/Workspace";
import type { CameraSelection } from "../../../src/features/albums/model/Gear";
import { AlbumRequestError } from "../../../src/features/albums/model/Album";
import { buildAlbumClient } from "./AlbumClientStub";

const saveFactory = vi.hoisted(() => vi.fn());
vi.mock("../../../src/features/albums/workspace/ContentSave", () => ({ createContentSave: saveFactory }));
vi.mock("../../../src/features/albums/workspace/CountryPicker", () => ({ CountryPicker: vi.fn(() => null) }));
vi.mock("../../../src/features/albums/workspace/ChapterGearFields", () => ({ ChapterGearFields: vi.fn(() => null) }));
vi.mock("../../../src/features/albums/workspace/GearCatalogDialog", () => ({ GearCatalogDialog: vi.fn(() => null) }));

type Props = Parameters<typeof ChapterDialog>[0];
interface GearDraft {
  chapterId: string;
  cameras: readonly CameraSelection[];
}
type MetadataOptions = Parameters<typeof createContentSave<ChapterDetails, string | undefined>>[0];
type GearOptions = Parameters<typeof createContentSave<GearDraft, readonly CameraSelection[]>>[0];

const details: ChapterDetails = {
  kind: "vacation",
  name: "Kyoto",
  countries: ["jp"],
  start: "2026-04-02",
  end: "2026-04-14",
  sourceFolder: "/photos/Kyoto",
};
const chapter = { ...details, id: "chapter", description: "" };
const countries = [{ id: "jp", name: "Japan", code: "JP" }];
const camera = { id: "camera", code: "Camera", name: "Camera", kind: "fixed_lens" as const };
const selection = [{ cameraId: camera.id, lensIds: [] }];
const catalog = { cameras: [camera], lenses: [] };
const receipt = { id: chapter.id, scheduling: "not-requested" as const };
const nativeShow = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "showModal");
const nativeClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "close");

describe("ChapterDialog", () => {
  beforeAll(() => {
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
  beforeEach(() => {
    saveFactory.mockReset();
    vi.mocked(CountryPicker).mockClear();
    vi.mocked(ChapterGearFields).mockClear();
    vi.mocked(GearCatalogDialog).mockClear();
  });
  afterEach(cleanup);
  afterAll(() => {
    if (nativeShow === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal");
    else Object.defineProperty(HTMLDialogElement.prototype, "showModal", nativeShow);
    if (nativeClose === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
    else Object.defineProperty(HTMLDialogElement.prototype, "close", nativeClose);
  });

  it("should forward saved resources and child selections and submit complete metadata before changed gear", async () => {
    const pending = deferred<undefined>();
    const authority = () => 7;
    const built = build({ props: { maybeAuthority: authority } });
    built.metadata.state.commit.mockReturnValue(pending.promise);
    await ready();
    const picker = required(vi.mocked(CountryPicker).mock.lastCall?.[0]);
    const fields = required(vi.mocked(ChapterGearFields).mock.lastCall?.[0]);

    expect(picker.countries).toEqual(countries);
    expect(picker.selected).toEqual(details.countries);
    expect(picker.disabled).toBe(false);
    expect(fields.catalog).toEqual(catalog);
    expect(fields.selected).toEqual([]);
    expect(fields.disabled).toBe(false);
    picker.onChange(["jp"]);
    fields.onChange(selection);
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    expect(built.metadata.state.begin).toHaveBeenCalledExactlyOnceWith(details);
    expect(built.metadata.state.commit).toHaveBeenCalledExactlyOnceWith(true);
    expect(built.gear.state.begin).toHaveBeenCalledExactlyOnceWith({ chapterId: chapter.id, cameras: selection });
    expect(built.gear.state.commit).not.toHaveBeenCalled();

    const savedId = await built.metadata.options().save(details);
    built.metadata.options().onCompleted?.(details, savedId);
    built.metadata.options().onSaved(savedId, details);
    pending.resolve(undefined);
    await waitFor(() => {
      expect(built.gear.state.commit).toHaveBeenCalledExactlyOnceWith(true);
    });
    await built.gear.options().save({ chapterId: chapter.id, cameras: selection });
    built.gear.options().onCompleted?.({ chapterId: chapter.id, cameras: selection }, selection);
    built.gear.options().onSaved(selection, { chapterId: chapter.id, cameras: selection });

    expect(built.props.save).toHaveBeenCalledExactlyOnceWith(details);
    expect(built.client.saveChapterGear).toHaveBeenCalledExactlyOnceWith("album", chapter.id, selection);
    expect(built.props.onSaved).toHaveBeenCalledExactlyOnceWith(receipt);
    expect(built.props.onBookSaved).toHaveBeenLastCalledWith({ details, gear: [] }, { details, gear: selection });
    expect(built.metadata.options().maybeAuthority).toBe(authority);
    expect(built.gear.options().maybeAuthority).toBe(authority);
    expect(built.gear.options().equal(selection, { chapterId: chapter.id, cameras: selection })).toBe(true);
    expect(built.gear.options().equal([], { chapterId: chapter.id, cameras: selection })).toBe(false);
  });

  it.each([false, true])("should not start remaining gear after the editor is disposed with changedGear=%s", async (changedGear) => {
    const pending = deferred<undefined>();
    const built = build();
    built.metadata.state.commit.mockReturnValue(pending.promise);
    await ready();
    if (changedGear) required(vi.mocked(ChapterGearFields).mock.lastCall?.[0]).onChange(selection);
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    const savedId = await built.metadata.options().save(details);
    built.metadata.options().onSaved(savedId, details);

    built.view.unmount();
    pending.resolve(undefined);
    await pending.promise;
    await Promise.resolve();

    expect(built.props.save).toHaveBeenCalledExactlyOnceWith(details);
    expect(built.gear.state.commit).not.toHaveBeenCalled();
    expect(built.client.saveChapterGear).not.toHaveBeenCalled();
    expect(built.props.onSaved).not.toHaveBeenCalled();
  });

  it("should block the remaining gear phase when authority changes during metadata confirmation", async () => {
    const pending = deferred<undefined>();
    const [authority, setAuthority] = createSignal(1);
    const built = build({ props: { maybeAuthority: authority } });
    built.metadata.state.commit.mockReturnValue(pending.promise);
    await ready();
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    built.metadata.options().onSaved(chapter.id, details);

    setAuthority(2);
    pending.resolve(undefined);
    await screen.findByText(
      "Chapter details are saved; gear remains unresolved. Editing access changed. Review the retained chapter before saving its remaining gear.",
    );

    expect(built.gear.state.commit).not.toHaveBeenCalled();
    expect(built.props.onSaved).not.toHaveBeenCalled();
  });

  it("should keep unresolved metadata and gear read-only and route reconciliation to their respective save owners", async () => {
    const built = build();
    await ready();
    built.metadata.setRecovery("uncertain");
    built.metadata.setUnavailable("Read saved content before continuing.");

    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save chapter" })).toBeDisabled();
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    expect(built.props.onClose).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Read saved chapter" }));
    expect(built.metadata.state.reconcile).toHaveBeenCalledOnce();
    expect(built.gear.state.reconcile).not.toHaveBeenCalled();
    built.metadata.setRecovery(undefined);
    built.metadata.setUnavailable(undefined);
    built.gear.setRecovery("acknowledged");
    built.gear.setUnavailable("Read saved content before continuing.");
    fireEvent.click(screen.getByRole("button", { name: "Read saved chapter" }));
    expect(built.gear.state.reconcile).toHaveBeenCalledOnce();
    expect(built.props.onSaved).not.toHaveBeenCalled();
  });

  it.each(["existing", "created"] as const)("should acquire the saved $kind chapter identity without repeating metadata writes", async (kind) => {
    const built = build({
      props: {
        chapter: kind === "created" ? undefined : chapter,
        resources: {
          albumId: "album",
          maybeChapterId: kind === "created" ? undefined : chapter.id,
          client: buildAlbumClient({
            gearCatalog: vi.fn().mockResolvedValue(catalog),
            chapterGear: vi.fn().mockResolvedValue([]),
            chapters: vi.fn().mockResolvedValue([chapter]),
          }),
        },
      },
    });
    await ready(kind === "created" ? "Add chapter" : "Save chapter");
    const options = built.metadata.options();

    expect(await options.read(details)).toBe(chapter.id);
    expect(options.equal(chapter.id, details)).toBe(true);
    expect(options.equal(undefined, details)).toBe(false);
    options.onSaved(chapter.id, details);
    fireEvent.click(within(screen.getByRole("form", { name: "Chapter details" })).getByRole("button", { name: "Close" }));

    expect(built.props.onReconciled).toHaveBeenCalledExactlyOnceWith(chapter.id);
    expect(built.props.save).not.toHaveBeenCalled();
  });

  it("should preserve an acknowledged identity and report its absence instead of guessing another chapter", async () => {
    const built = build({ client: { chapters: vi.fn().mockResolvedValue([]) } });
    await ready();
    built.metadata.setRecovery("acknowledged");

    await expect(built.metadata.options().read(details)).rejects.toEqual(new AlbumRequestError(404, "The saved chapter could not be found."));
    expect(built.props.save).not.toHaveBeenCalled();
  });

  it("should compare uncertain metadata against the saved chapter and use its observed details when recording a later correction", async () => {
    const observed = { ...chapter, name: "Saved by another editor" };
    const built = build({ client: { chapters: vi.fn().mockResolvedValue([observed]) } });
    await ready();

    expect(await built.metadata.options().read(details)).toBeUndefined();
    built.metadata.options().onCompleted?.(details, chapter.id);

    expect(built.props.onBookSaved).toHaveBeenCalledExactlyOnceWith(
      { details: { ...details, name: observed.name }, gear: [] },
      { details, gear: [] },
    );
    built.metadata.setRecovery("acknowledged");
    expect(await built.metadata.options().read(details)).toBe(chapter.id);
    expect(built.props.save).not.toHaveBeenCalled();
  });

  it("should validate selected gear before metadata and allow closing a rejected gear phase without another metadata write", async () => {
    const body = {
      id: "body",
      code: "body",
      name: "Body",
      kind: "interchangeable_lens" as const,
      mount: "sony_e" as const,
      sensorFormat: "aps_c" as const,
    };
    const lens = { id: "lens", code: "lens", name: "Lens", mount: "sony_e" as const, coverage: "aps_c" as const };
    const built = build({ client: { gearCatalog: vi.fn().mockResolvedValue({ cameras: [body], lenses: [lens] }) } });
    await ready();
    const fields = required(vi.mocked(ChapterGearFields).mock.lastCall?.[0]);

    fields.onChange([{ cameraId: body.id, lensIds: [] }]);
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Choose at least one lens");
    expect(built.metadata.state.commit).not.toHaveBeenCalled();
    fields.onChange([{ cameraId: body.id, lensIds: [lens.id] }]);
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    const savedId = await built.metadata.options().save(details);
    built.metadata.options().onSaved(savedId, details);
    built.gear.setError("Gear rejected.");
    expect(screen.getByRole("alert")).toHaveTextContent("Chapter details are saved; gear remains unresolved. Gear rejected.");
    fireEvent.click(within(screen.getByRole("form", { name: "Chapter details" })).getByRole("button", { name: "Close" }));

    expect(built.props.save).toHaveBeenCalledExactlyOnceWith(details);
    expect(built.props.onSaved).toHaveBeenCalledExactlyOnceWith(receipt);
    expect(built.metadata.state.commit).toHaveBeenCalledOnce();
  });

  it("should record an unchanged source as a book edit and a changed source as a history boundary", async () => {
    const built = build();
    await ready();
    const edited = { ...details, name: "Osaka" };
    const relocated = { ...details, sourceFolder: "/photos/Osaka" };

    built.metadata.options().onCompleted?.(edited, chapter.id);
    expect(built.props.onBookSaved).toHaveBeenCalledExactlyOnceWith({ details, gear: [] }, { details: edited, gear: [] });
    built.metadata.options().onCompleted?.(relocated, chapter.id);
    built.gear.options().onCompleted?.({ chapterId: chapter.id, cameras: selection }, selection);
    expect(built.props.onHistoryBoundary).toHaveBeenCalledExactlyOnceWith();
    expect(built.props.onBookSaved).toHaveBeenCalledOnce();
  });

  it("should retain a failed gear acquisition and metadata draft until retry and forward catalog management", async () => {
    const gearCatalog = vi.fn().mockRejectedValueOnce(new Error("Catalog offline")).mockResolvedValue(catalog);
    const built = build({ client: { gearCatalog } });
    await screen.findByRole("alert");
    fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: "Retained location" } });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));

    expect(screen.getByRole("alert")).toHaveTextContent("Load saved chapter gear before saving.");
    expect(built.metadata.state.commit).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Retry gear" }));
    await ready();
    expect(screen.getByRole("textbox", { name: "Location / name" })).toHaveValue("Retained location");
    const opener = screen.getByRole<HTMLButtonElement>("button", { name: "Save chapter" });
    required(vi.mocked(ChapterGearFields).mock.lastCall?.[0]).onManage(opener);
    const managed = required(vi.mocked(GearCatalogDialog).mock.lastCall?.[0]);
    expect(managed.client).toBe(built.client);
    managed.onChanged({ cameras: [], lenses: [] });
    expect(required(vi.mocked(ChapterGearFields).mock.lastCall?.[0]).catalog).toEqual({ cameras: [], lenses: [] });
    managed.onClose();
    managed.returnFocus();
    expect(opener).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(built.props.onClose).toHaveBeenCalledOnce();
  });

  it.each(["resolve", "reject"] as const)("should abort pending initial gear acquisition and ignore its late $outcome", async (outcome) => {
    const pending = deferred<typeof catalog>();
    const gearCatalog = vi.fn<Props["resources"]["client"]["gearCatalog"]>().mockReturnValue(pending.promise);
    const built = build({ client: { gearCatalog } });
    const signal = required(gearCatalog.mock.lastCall?.[0]);

    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(built.metadata.state.commit).not.toHaveBeenCalled();
    built.view.unmount();
    expect(signal.aborted).toBe(true);
    if (outcome === "resolve") {
      pending.resolve(catalog);
      await pending.promise;
    } else {
      pending.reject(new Error("Offline after closing"));
      await expect(pending.promise).rejects.toThrow("Offline after closing");
    }
    expect(ChapterGearFields).not.toHaveBeenCalled();
    expect(built.props.onSaved).not.toHaveBeenCalled();
  });

  it("should edit day/month through typing and native calendars without changing saved years", async () => {
    const picker = vi.fn();
    const saved = { ...chapter, start: "2025-12-31", end: "2026-01-01" };
    const built = build({ props: { chapter: saved } });
    await ready();
    const first = screen.getByRole("textbox", { name: "First day" });
    const last = screen.getByRole("textbox", { name: "Last day" });
    const firstCalendar = screen.getByLabelText("Choose first day from calendar");
    const lastCalendar = screen.getByLabelText("Choose last day from calendar");
    Object.defineProperty(firstCalendar, "showPicker", { configurable: true, value: picker });

    expect(first).toHaveValue("31/12");
    expect(last).toHaveValue("01/01");
    fireEvent.input(last, { target: { value: "02/01" } });
    expect(last).toHaveValue("02/01");
    fireEvent.input(firstCalendar, { target: { value: "2026-01-01" } });
    expect(screen.getByRole("alert")).toHaveTextContent("Choose dates within 2025.");
    expect(first).toHaveValue("31/12");
    fireEvent.input(lastCalendar, { target: { value: "2025-12-31" } });
    expect(screen.getByRole("alert")).toHaveTextContent("Choose dates within 2026.");
    expect(last).toHaveValue("02/01");
    fireEvent.click(firstCalendar);
    fireEvent.keyDown(firstCalendar, { key: "Enter" });
    fireEvent.keyDown(firstCalendar, { key: " " });
    fireEvent.keyDown(firstCalendar, { key: "ArrowDown" });
    fireEvent.click(lastCalendar);
    fireEvent.input(firstCalendar, { target: { value: "" } });
    fireEvent.input(lastCalendar, { target: { value: "" } });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(first).toHaveValue("");
    expect(last).toHaveValue("");
    expect(screen.getByRole("alert")).toHaveTextContent("Enter valid calendar dates.");
    expect(built.metadata.state.commit).not.toHaveBeenCalled();
    fireEvent.input(firstCalendar, { target: { value: "2025-12-29" } });
    expect(first).toHaveValue("29/12");
    fireEvent.input(first, { target: { value: "30/12" } });
    fireEvent.input(lastCalendar, { target: { value: "2026-01-02" } });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));

    expect(picker).toHaveBeenCalledTimes(3);
    expect(last).toHaveValue("02/01");
    expect(built.metadata.state.begin).toHaveBeenCalledExactlyOnceWith({ ...details, start: "2025-12-30", end: "2026-01-02" });
  });
  it("should acquire creation defaults, require an explicit type and submit Home metadata without vacation fields", async () => {
    const client = buildAlbumClient({ gearCatalog: vi.fn().mockResolvedValue(catalog), chapters: vi.fn().mockResolvedValue([]) });
    const built = build({
      props: {
        chapter: undefined,
        homeAvailable: true,
        countries: [...countries, { id: "ro", name: "Romania", code: "RO" }],
        resources: { client, albumId: "album", maybeChapterId: undefined },
      },
    });
    await ready("Add chapter");
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Choose a chapter type.");
    const kind = screen.getByRole("combobox", { name: "Chapter type" });
    fireEvent.change(kind, { target: { value: "vacation" } });
    const picker = required(vi.mocked(CountryPicker).mock.lastCall?.[0]);
    expect(picker.selected).toEqual(["ro"]);
    expect(screen.getByRole("textbox", { name: "Location / name" })).toBeInTheDocument();
    fireEvent.change(kind, { target: { value: "" } });
    fireEvent.change(kind, { target: { value: "home" } });
    picker.onChange(["jp"]);
    fireEvent.input(screen.getByRole("textbox", { name: "Photo folder" }), { target: { value: "/photos/MyHome" } });
    const home = { kind: "home" as const, countries: ["jp"], sourceFolder: "/photos/MyHome" };

    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(built.metadata.state.begin).toHaveBeenCalledExactlyOnceWith(home);
    built.metadata.setDraft({ original: home, value: home });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(built.metadata.state.change).toHaveBeenCalledExactlyOnceWith(home);
    built.metadata.setUnavailable("Saving content. Please wait.");
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(built.metadata.state.commit).toHaveBeenCalledTimes(2);
    expect(client.chapterGear).not.toHaveBeenCalled();
  });

  it("should reacquire book metadata on a new access generation and fence a superseded read before rebasing gear", async () => {
    const [authority, setAuthority] = createSignal(1);
    const built = build({ props: { maybeAuthority: authority } });
    await ready();
    const draft = { chapterId: chapter.id, cameras: selection };
    setAuthority(2);

    expect(await built.gear.options().read(draft)).toEqual([]);
    built.gear.options().onCompleted?.(draft, selection);
    expect(built.props.onBookSaved).toHaveBeenLastCalledWith({ details, gear: [] }, { details, gear: selection });
    const metadata = deferred<readonly (typeof chapter)[]>();
    vi.mocked(built.client.chapters).mockReturnValueOnce(metadata.promise);
    setAuthority(3);
    const read = built.gear.options().read(draft);
    await waitFor(() => {
      expect(built.client.chapters).toHaveBeenCalledTimes(2);
    });
    setAuthority(4);
    metadata.resolve([chapter]);
    expect(await read).toEqual([]);
    vi.mocked(built.client.chapters).mockResolvedValue([]);
    await expect(built.gear.options().read(draft)).rejects.toBeInstanceOf(AlbumRequestError);
    vi.mocked(built.client.chapters).mockResolvedValue([chapter]);
    expect(await built.gear.options().read(draft)).toEqual([]);
    const metadataReads = vi.mocked(built.client.chapters).mock.calls.length;
    expect(await built.gear.options().read({ chapterId: chapter.id, cameras: [] })).toEqual([]);
    expect(built.client.chapters).toHaveBeenCalledTimes(metadataReads);
    expect(built.client.chapterGear).toHaveBeenCalledWith("album", chapter.id, expect.any(AbortSignal));
  });

  it("should resume remaining gear only after independently confirmed metadata readback and retain subsequent gear reconciliation", async () => {
    const readback = deferred<undefined>();
    const built = build();
    await ready();
    required(vi.mocked(ChapterGearFields).mock.lastCall?.[0]).onChange(selection);
    built.metadata.setRecovery("uncertain");
    built.metadata.state.reconcile.mockReturnValue(readback.promise);

    fireEvent.click(screen.getByRole("button", { name: "Read saved chapter" }));
    built.metadata.options().onSaved(chapter.id, details);
    built.metadata.setRecovery(undefined);
    readback.resolve(undefined);
    await waitFor(() => {
      expect(built.gear.state.commit).toHaveBeenCalledWith(false);
    });

    expect(built.gear.state.begin).toHaveBeenCalledExactlyOnceWith({ chapterId: chapter.id, cameras: selection });
    built.gear.setRecovery("acknowledged");
    fireEvent.click(screen.getByRole("button", { name: "Read saved chapter" }));
    expect(built.gear.state.reconcile).toHaveBeenCalledOnce();
    expect(built.props.save).not.toHaveBeenCalled();
    built.gear.setRecovery(undefined);
    built.gear.setDraft({ original: { chapterId: chapter.id, cameras: [] }, value: { chapterId: chapter.id, cameras: selection } });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    await waitFor(() => {
      expect(built.gear.state.change).toHaveBeenCalledWith({ chapterId: chapter.id, cameras: selection });
    });
    expect(built.metadata.state.commit).not.toHaveBeenCalled();
    required(vi.mocked(ChapterGearFields).mock.lastCall?.[0]).onChange([]);
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    await waitFor(() => {
      expect(built.props.onReconciled).toHaveBeenCalledExactlyOnceWith(chapter.id);
    });
  });
});

function build(overrides: { props?: Partial<Props>; client?: Partial<Props["resources"]["client"]> } = {}) {
  const metadata = saveState<ChapterDetails>();
  const gear = saveState<GearDraft>();
  let metadataOptions: MetadataOptions | undefined;
  let gearOptions: GearOptions | undefined;
  saveFactory.mockImplementationOnce((options: GearOptions) => {
    gearOptions = options;
    return gear.state;
  });
  saveFactory.mockImplementationOnce((options: MetadataOptions) => {
    metadataOptions = options;
    return metadata.state;
  });
  const client = buildAlbumClient({
    gearCatalog: vi.fn().mockResolvedValue(catalog),
    chapterGear: vi.fn().mockResolvedValue([]),
    chapters: vi.fn().mockResolvedValue([chapter]),
    saveChapterGear: vi.fn().mockResolvedValue(undefined),
    ...overrides.client,
  });
  const props = {
    chapter,
    countries,
    year: 2026,
    photoRoot: "/photos",
    homeAvailable: false,
    save: vi.fn().mockResolvedValue(receipt),
    onSaved: vi.fn(),
    onReconciled: vi.fn(),
    onClose: vi.fn(),
    returnFocus: vi.fn(),
    onBlock: vi.fn(),
    onBookSaved: vi.fn(),
    onHistoryBoundary: vi.fn(),
    resources: { albumId: "album", maybeChapterId: chapter.id, client },
    ...overrides.props,
  };
  const view = render(() => <ChapterDialog {...props} />);
  return {
    props,
    client: props.resources.client,
    view,
    metadata: { ...metadata, options: () => required(metadataOptions) },
    gear: { ...gear, options: () => required(gearOptions) },
  };
}

function saveState<T>() {
  const [draft, setDraft] = createSignal<{ original: T; value: T }>();
  const [pending, setPending] = createSignal(false);
  const [recovery, setRecovery] = createSignal<"acknowledged" | "uncertain">();
  const [error, setError] = createSignal<string>();
  const [unavailable, setUnavailable] = createSignal<ReturnType<ContentSave<T>["unavailable"]>>();
  const state = {
    draft,
    pending,
    recovery,
    error,
    unavailable,
    begin: vi.fn<(value: T) => void>(),
    change: vi.fn<(value: T) => void>(),
    cancel: vi.fn(),
    commit: vi.fn<(reviewed?: boolean) => Promise<void>>().mockResolvedValue(undefined),
    reconcile: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
  } satisfies ContentSave<T>;
  return { state, setDraft, setPending, setRecovery, setError, setUnavailable };
}

async function ready(name = "Save chapter") {
  await waitFor(() => expect(screen.getByRole("button", { name })).toBeEnabled());
}

function required<T>(value: T | undefined): T {
  if (value === undefined) throw new Error("Expected arranged chapter collaborator");
  return value;
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (cause: Error) => void;
  const promise = new Promise<T>((complete, fail) => {
    resolve = complete;
    reject = fail;
  });
  return { promise, resolve, reject };
}

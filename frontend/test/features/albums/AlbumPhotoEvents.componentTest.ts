import { describe, expect, it, vi } from "vitest";
import { makeAlbumPhotoEvents } from "../../../src/features/albums/api/AlbumPhotoEvents";

describe("AlbumPhotoEvents", () => {
  it("should adapt named events and interruptions independently for each browser subscription and dispose listeners", () => {
    const source = Object.assign(new EventTarget(), { close: vi.fn() });
    const otherSource = Object.assign(new EventTarget(), { close: vi.fn() });
    const construct = vi.fn<(url: string) => EventTarget & { close: () => void }>().mockReturnValueOnce(source).mockReturnValueOnce(otherSource);
    const events = makeAlbumPhotoEvents(construct);
    const receive = vi.fn();
    const otherReceive = vi.fn();
    const dispose = events.subscribe("album/id", receive);
    const disposeOther = events.subscribe("album/id", otherReceive);
    const expectedEvents = [
      { kind: "subscribed" },
      { kind: "inventory-changed", chapterId: "chapter" },
      { kind: "interrupted" },
      { kind: "subscribed" },
    ];

    source.dispatchEvent(new MessageEvent("subscribed", { data: "{}" }));
    source.dispatchEvent(new MessageEvent("inventory-changed", { data: '{"chapterId":"chapter"}' }));
    source.dispatchEvent(new Event("error"));
    source.dispatchEvent(new MessageEvent("subscribed", { data: "{}" }));

    expect(construct.mock.calls).toEqual([["/api/albums/album%2Fid/photo-events"], ["/api/albums/album%2Fid/photo-events"]]);
    expect(receive.mock.calls.flat()).toEqual(expectedEvents);
    expect(otherReceive).not.toHaveBeenCalled();

    dispose();
    source.dispatchEvent(new MessageEvent("subscribed", { data: "{}" }));

    expect(source.close).toHaveBeenCalledOnce();
    expect(receive).toHaveBeenCalledTimes(4);
    expect(otherSource.close).not.toHaveBeenCalled();
    disposeOther();
  });

  it.each([
    new Event("subscribed"),
    new MessageEvent("subscribed", { data: 42 }),
    new MessageEvent("subscribed", { data: "not json" }),
    new MessageEvent("subscribed", { data: "[]" }),
    new MessageEvent("inventory-changed", { data: "{}" }),
    new MessageEvent("inventory-changed", { data: '{"chapterId":4}' }),
    new MessageEvent("inventory-changed", { data: '{"chapterId":""}' }),
  ])("should report malformed $type without fabricating a subscription or chapter change", (event) => {
    const source = Object.assign(new EventTarget(), { close: vi.fn() });
    const receive = vi.fn();
    const dispose = makeAlbumPhotoEvents(() => source).subscribe("album", receive);

    source.dispatchEvent(event);

    expect(receive).toHaveBeenCalledExactlyOnceWith({ kind: "invalid" });
    dispose();
  });
});

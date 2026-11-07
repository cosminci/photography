import { afterAll, beforeAll } from "vitest";

export function useDialogs() {
  const showModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "showModal");
  const close = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "close");
  beforeAll(() => {
    Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.setAttribute("open", "");
      },
    });
    Object.defineProperty(HTMLDialogElement.prototype, "close", {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.removeAttribute("open");
      },
    });
  });
  afterAll(() => {
    if (showModal === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal");
    else Object.defineProperty(HTMLDialogElement.prototype, "showModal", showModal);
    if (close === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
    else Object.defineProperty(HTMLDialogElement.prototype, "close", close);
  });
}

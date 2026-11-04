import { For, type JSX } from "solid-js";
import type { CoverGeometry, CoverSnapshot } from "../model/Placement";
import "./cover.css";

export function CoverSurface(props: {
  geometry: CoverGeometry;
  spineOwner: CoverSnapshot["spineOwner"];
  back: JSX.Element;
  front: JSX.Element;
  children?: JSX.Element;
}) {
  return (
    <div class="cover-surface" style={{ "aspect-ratio": props.geometry.width / props.geometry.height }}>
      <For each={["back", "front"] as const}>
        {(side) => (
          <div
            class="cover-photo-frame"
            style={{
              left: `${String((100 * props.geometry[side].x) / props.geometry.width)}%`,
              width: `${String((100 * props.geometry[side].width) / props.geometry.width)}%`,
            }}
          >
            {props[side]}
          </div>
        )}
      </For>
      <div
        class="cover-spine"
        role="img"
        aria-label={`Spine: ${props.spineOwner} cover`}
        style={{
          left: `${String((100 * props.geometry.spineStart) / props.geometry.width)}%`,
          width: `${String((100 * props.geometry.spineWidth) / props.geometry.width)}%`,
        }}
      />
      {props.children}
    </div>
  );
}

import { createMemo } from "solid-js";
import type { Photo } from "../model/Photo";
import type { PhotoTransform } from "../model/Placement";
import { cropImage } from "./PlacementCrop";
import "./preview.css";

export function SavedPhoto(props: {
  photo: Photo;
  transform: PhotoTransform;
  width: number;
  height: number;
  retry?: number | undefined;
  onError: () => void;
  onImage?: (image: HTMLImageElement) => void;
}) {
  const crop = createMemo(() => cropImage(props.photo, props, props.transform));
  const source = () => {
    const url = props.photo.thumbnail.url;
    return props.retry === undefined || props.retry === 0 ? url : `${url}${url.includes("?") ? "&" : "?"}retry=${String(props.retry)}`;
  };
  return (
    <img
      class="saved-photo"
      ref={(image) => props.onImage?.(image)}
      src={source()}
      alt={props.photo.filename}
      draggable={false}
      onError={() => {
        props.onError();
      }}
      style={{
        width: `${String((crop().width / props.width) * 100)}%`,
        height: `${String((crop().height / props.height) * 100)}%`,
        left: `${String((crop().left / props.width) * 100)}%`,
        top: `${String((crop().top / props.height) * 100)}%`,
        transform: props.transform.mirrored ? "scaleX(-1)" : undefined,
      }}
    />
  );
}

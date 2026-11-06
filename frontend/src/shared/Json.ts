import { check, looseObject, pipe, unknown } from "valibot";

export const jsonObject = pipe(
  unknown(),
  check((value) => !Array.isArray(value)),
  looseObject({}),
);

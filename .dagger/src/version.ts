export function releaseVersion(now: Date): string {
  const day = [now.getUTCFullYear(), now.getUTCMonth() + 1, now.getUTCDate()].join(".");
  const time = [now.getUTCHours(), now.getUTCMinutes(), now.getUTCSeconds()]
    .map((value) => String(value).padStart(2, "0"))
    .join("");
  return `${day}-T${time}`;
}

export function assertReleaseVersion(tag: string): void {
  const match =
    /^(\d{4})\.([1-9]|1[0-2])\.([1-9]|[12]\d|3[01])-T(?:[01]\d|2[0-3])[0-5]\d[0-5]\d$/.exec(tag);
  if (
    match?.[0] !== tag ||
    new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))).getUTCDate() !==
      Number(match[3])
  ) {
    throw new Error(`invalid release version: ${tag}`);
  }
}

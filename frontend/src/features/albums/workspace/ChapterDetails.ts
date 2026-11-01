import type { Chapter, ChapterDetails } from "../model/Workspace";

export function validateChapter(details: ChapterDetails): string | undefined {
  if (details.kind === "vacation" && details.name.trim() === "") return "Enter a location / name.";
  if (details.kind === "vacation" && details.name.trim() === "Home") return "Choose a vacation name other than Home.";
  if (details.countries.length === 0) return "Select at least one country.";
  const folder = details.sourceFolder;
  const invalidSegment = folder
    .split("/")
    .slice(1)
    .some((segment) => segment === "" || segment === "." || segment === "..");
  const invalidFolder = !folder.startsWith("/") || folder.includes("\\") || folder.includes("\0") || invalidSegment;
  if (invalidFolder) return "Enter an absolute photo folder without parent traversal.";
  if (details.kind === "home") return undefined;
  if (!isCalendarDay(details.start) || !isCalendarDay(details.end)) return "Enter valid calendar dates.";
  return details.start > details.end ? "Last day must not precede first day." : undefined;
}

export function isCalendarDay(value: string): boolean {
  if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value)) return false;
  const year = Number(value.slice(0, 4));
  const month = Number(value.slice(5, 7));
  const day = Number(value.slice(8, 10));
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  const maximumDay = days[month - 1];
  return maximumDay !== undefined && day >= 1 && day <= maximumDay;
}

export function suggestFolder(photoRoot: string, year: number, name: string): string {
  return `${photoRoot.replace(/\/+$/, "")}/${String(year)}/selection/${name.trim()}`;
}

export function describePeriod(chapter: { kind: "vacation" | "home"; start: string; end: string }): string {
  const displayDate = (value: string) => `${value.slice(8, 10)}/${value.slice(5, 7)}`;
  return chapter.kind === "home" ? "Throughout the year" : `${displayDate(chapter.start)} - ${displayDate(chapter.end)}`;
}

export function describeCountryLabel(count: number): string {
  return count === 1 ? "Country" : "Countries";
}

export function displayCalendarDay(value: string): string {
  return `${value.slice(8, 10)}/${value.slice(5, 7)}/${value.slice(0, 4)}`;
}

export function readCalendarDay(value: string): string {
  if (!/^[0-9]{2}\/[0-9]{2}\/[0-9]{4}$/.test(value)) return "";
  const day = `${value.slice(6, 10)}-${value.slice(3, 5)}-${value.slice(0, 2)}`;
  return isCalendarDay(day) ? day : "";
}
export function matchesChapterDetails(chapter: Chapter | undefined, details: ChapterDetails): boolean {
  if (
    chapter?.kind !== details.kind ||
    chapter.sourceFolder !== details.sourceFolder ||
    chapter.countries.length !== details.countries.length ||
    !chapter.countries.every((id) => details.countries.includes(id))
  )
    return false;
  return details.kind === "home" || (chapter.name === details.name && chapter.start === details.start && chapter.end === details.end);
}

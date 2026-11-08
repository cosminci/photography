/** Base images and shared constants for the pipeline. */
export const JDK_IMAGE = "sbtscala/scala-sbt:eclipse-temurin-25.0.4_7_1.13.0_3.8.4";
export const NODE_IMAGE = "node:24-slim";
export const PYTHON_IMAGE = "python:3.14-slim";
export const TARGET_PLATFORM = "linux/amd64";
export const GHCR_REPOSITORY = "ghcr.io/cosminci/photography";
export const GHCR_USER = "cosminci";
export const SOURCE_URL = "https://github.com/cosminci/photography";

/** Trees the pipeline never needs; `.git` remains available for affected-path selection. */
export const WORKSPACE_IGNORE = [
  "**/node_modules",
  "**/target",
  "**/dist",
  "**/.bsp",
  "**/.scala-build",
  ".dagger/sdk",
  ".agent-work",
  ".local",
  "**/__pycache__",
];

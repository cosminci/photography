CREATE TABLE cover_specification (
    page_limit INTEGER PRIMARY KEY NOT NULL CHECK (page_limit > 0),
    width_pixels INTEGER NOT NULL CHECK (width_pixels > 0),
    height_pixels INTEGER NOT NULL CHECK (height_pixels > 0),
    spine_pixels INTEGER NOT NULL CHECK (spine_pixels > 0 AND spine_pixels < width_pixels)
) STRICT;

CREATE TABLE country (
    id TEXT PRIMARY KEY NOT NULL,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    flag TEXT NOT NULL
) STRICT;

CREATE TABLE destination (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL
) STRICT;

CREATE TABLE destination_country (
    destination_id TEXT NOT NULL REFERENCES destination (id) ON DELETE CASCADE,
    country_id TEXT NOT NULL REFERENCES country (id),
    position INTEGER NOT NULL CHECK (position >= 0),
    PRIMARY KEY (destination_id, country_id),
    UNIQUE (destination_id, position)
) STRICT;

CREATE TABLE album (
    id TEXT PRIMARY KEY NOT NULL,
    year INTEGER NOT NULL UNIQUE CHECK (year BETWEEN 1900 AND 2100),
    title TEXT NOT NULL CHECK (length(trim(title)) > 0),
    synopsis TEXT NOT NULL,
    authors INTEGER NOT NULL DEFAULT 7 CHECK (typeof(authors) = 'integer' AND authors BETWEEN 1 AND 7),
    created_at_ms INTEGER NOT NULL
) STRICT;

CREATE TABLE chapter (
    id TEXT PRIMARY KEY NOT NULL,
    album_id TEXT NOT NULL REFERENCES album (id) ON DELETE CASCADE,
    destination_id TEXT NOT NULL REFERENCES destination (id),
    description TEXT NOT NULL,
    start_ms INTEGER NOT NULL,
    end_ms INTEGER NOT NULL,
    position INTEGER NOT NULL,
    source_folder TEXT NOT NULL,
    UNIQUE (album_id, position)
) STRICT;

CREATE TABLE photo (
    id TEXT PRIMARY KEY NOT NULL,
    chapter_id TEXT NOT NULL REFERENCES chapter (id) ON DELETE CASCADE,
    source TEXT NOT NULL,
    source_hash TEXT NOT NULL,
    captured_local TEXT,
    thumbnail TEXT NOT NULL,
    thumbnail_version TEXT NOT NULL,
    thumbnail_width INTEGER NOT NULL CHECK (thumbnail_width BETWEEN 1 AND 2048),
    thumbnail_height INTEGER NOT NULL CHECK (thumbnail_height BETWEEN 1 AND 2048),
    thumbnail_quality TEXT NOT NULL CHECK (thumbnail_quality IN ('low', 'standard', 'high')),
    thumbnail_bytes INTEGER NOT NULL CHECK (thumbnail_bytes BETWEEN 1 AND
        CASE thumbnail_quality WHEN 'low' THEN 75000 WHEN 'standard' THEN 150000 WHEN 'high' THEN 300000 END),
    captured_at_ms INTEGER,
    width INTEGER NOT NULL CHECK (width > 0),
    height INTEGER NOT NULL CHECK (height > 0),
    caption TEXT,
    UNIQUE (chapter_id, source)
) STRICT;

CREATE TABLE photo_write_intent (
    id TEXT PRIMARY KEY NOT NULL,
    chapter_id TEXT NOT NULL,
    photo_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('publication', 'retirement')),
    location TEXT NOT NULL,
    previous TEXT,
    CHECK (kind = 'publication' OR previous IS NULL)
) STRICT;

CREATE TABLE camera (
    id TEXT PRIMARY KEY NOT NULL,
    code TEXT NOT NULL UNIQUE,
    friendly_name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('fixed_lens', 'interchangeable_lens')),
    mount TEXT CHECK (mount IN ('sony_e', 'nikon_z')),
    sensor_format TEXT CHECK (sensor_format IN ('aps_c', 'full_frame')),
    CHECK (
        (kind = 'fixed_lens' AND mount IS NULL AND sensor_format IS NULL)
        OR (kind = 'interchangeable_lens' AND mount IS NOT NULL AND sensor_format IS NOT NULL)
    )
) STRICT;

CREATE TABLE lens (
    id TEXT PRIMARY KEY NOT NULL,
    code TEXT NOT NULL UNIQUE,
    friendly_name TEXT NOT NULL,
    mount TEXT NOT NULL CHECK (mount IN ('sony_e', 'nikon_z')),
    coverage TEXT NOT NULL CHECK (coverage IN ('aps_c', 'full_frame'))
) STRICT;

CREATE TABLE chapter_camera (
    chapter_id TEXT NOT NULL REFERENCES chapter (id) ON DELETE CASCADE,
    camera_id TEXT NOT NULL REFERENCES camera (id),
    use_kind TEXT NOT NULL CHECK (use_kind IN ('fixed_lens', 'interchangeable_lenses')),
    PRIMARY KEY (chapter_id, camera_id)
) STRICT;

CREATE TABLE chapter_camera_lens (
    chapter_id TEXT NOT NULL,
    camera_id TEXT NOT NULL,
    lens_id TEXT NOT NULL REFERENCES lens (id),
    PRIMARY KEY (chapter_id, camera_id, lens_id),
    FOREIGN KEY (chapter_id, camera_id) REFERENCES chapter_camera (chapter_id, camera_id) ON DELETE CASCADE
) STRICT;

CREATE TABLE spread_template (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('album_start', 'chapter_start', 'photos_only'))
) STRICT;

CREATE TABLE spread_template_slot (
    id INTEGER PRIMARY KEY,
    template_id TEXT NOT NULL REFERENCES spread_template (id),
    kind TEXT NOT NULL CHECK (kind IN ('photo', 'text')),
    x REAL NOT NULL,
    y REAL NOT NULL,
    width REAL NOT NULL,
    height REAL NOT NULL,
    UNIQUE (template_id, kind, x, y, width, height)
) STRICT;

CREATE TABLE spread (
    id TEXT PRIMARY KEY NOT NULL,
    template_id TEXT NOT NULL REFERENCES spread_template (id),
    kind TEXT NOT NULL CHECK (kind IN ('album_start', 'chapter_start', 'photos_only')),
    album_id TEXT REFERENCES album (id) ON DELETE CASCADE,
    chapter_id TEXT REFERENCES chapter (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    CHECK (
        (kind = 'album_start' AND album_id IS NOT NULL AND chapter_id IS NULL AND position = 0)
        OR (kind = 'chapter_start' AND album_id IS NULL AND chapter_id IS NOT NULL AND position = 0)
        OR (kind = 'photos_only' AND album_id IS NULL AND chapter_id IS NOT NULL AND position > 0)
    ),
    UNIQUE (album_id, position),
    UNIQUE (chapter_id, position)
) STRICT;

CREATE TABLE spread_photo_slot (
    id TEXT PRIMARY KEY NOT NULL,
    spread_id TEXT NOT NULL REFERENCES spread (id) ON DELETE CASCADE,
    x REAL NOT NULL,
    y REAL NOT NULL,
    width REAL NOT NULL,
    height REAL NOT NULL
) STRICT;

CREATE TABLE spread_photo_slot_bleed (
    slot_id TEXT NOT NULL REFERENCES spread_photo_slot (id) ON DELETE CASCADE,
    edge TEXT NOT NULL CHECK (edge IN ('top', 'bottom', 'left', 'right')),
    PRIMARY KEY (slot_id, edge)
) STRICT;

CREATE TABLE spread_text_slot (
    id TEXT PRIMARY KEY NOT NULL,
    spread_id TEXT NOT NULL REFERENCES spread (id) ON DELETE CASCADE,
    x REAL NOT NULL,
    y REAL NOT NULL,
    width REAL NOT NULL,
    height REAL NOT NULL
) STRICT;

CREATE TABLE cover (
    album_id TEXT PRIMARY KEY NOT NULL REFERENCES album (id) ON DELETE CASCADE,
    spine_owner TEXT NOT NULL CHECK (spine_owner IN ('front', 'back'))
) STRICT;

CREATE TABLE cover_slot (
    id TEXT PRIMARY KEY NOT NULL,
    album_id TEXT NOT NULL REFERENCES cover (album_id) ON DELETE CASCADE,
    side TEXT NOT NULL CHECK (side IN ('front', 'back')),
    UNIQUE (album_id, side)
) STRICT;

CREATE TABLE placement (
    id TEXT PRIMARY KEY NOT NULL,
    photo_id TEXT NOT NULL UNIQUE REFERENCES photo (id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('spread', 'cover')),
    spread_photo_slot_id TEXT UNIQUE REFERENCES spread_photo_slot (id) ON DELETE CASCADE,
    cover_slot_id TEXT UNIQUE REFERENCES cover_slot (id) ON DELETE CASCADE,
    shift_x REAL NOT NULL,
    shift_y REAL NOT NULL,
    mirrored INTEGER NOT NULL CHECK (mirrored IN (0, 1)),
    zoom REAL NOT NULL,
    caption_corner TEXT,
    CHECK (
        (kind = 'spread'
            AND spread_photo_slot_id IS NOT NULL
            AND cover_slot_id IS NULL
            AND caption_corner IS NOT NULL
            AND caption_corner IN ('top_left', 'top_right', 'bottom_left', 'bottom_right'))
        OR (kind = 'cover'
            AND spread_photo_slot_id IS NULL
            AND cover_slot_id IS NOT NULL
            AND caption_corner IS NULL)
    )
) STRICT;

CREATE TABLE editor_session (
    id TEXT PRIMARY KEY NOT NULL,
    owner TEXT NOT NULL UNIQUE,
    last_heartbeat_ms INTEGER NOT NULL
) STRICT;

CREATE TABLE spread_editing_lock (
    spread_id TEXT PRIMARY KEY NOT NULL REFERENCES spread (id) ON DELETE CASCADE,
    session_id TEXT NOT NULL REFERENCES editor_session (id)
) STRICT;

CREATE TABLE cover_editing_lock (
    album_id TEXT PRIMARY KEY NOT NULL REFERENCES cover (album_id) ON DELETE CASCADE,
    session_id TEXT NOT NULL REFERENCES editor_session (id)
) STRICT;

CREATE INDEX album_created_at_id ON album (created_at_ms DESC, id DESC);
CREATE INDEX chapter_destination ON chapter (destination_id);
CREATE INDEX photo_chapter_id ON photo (chapter_id, id);
CREATE INDEX chapter_camera_camera ON chapter_camera (camera_id);
CREATE INDEX chapter_camera_lens_lens ON chapter_camera_lens (lens_id);
CREATE INDEX spread_photo_slot_spread ON spread_photo_slot (spread_id);
CREATE INDEX spread_text_slot_spread ON spread_text_slot (spread_id);

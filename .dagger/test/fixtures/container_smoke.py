import hashlib
from contextlib import contextmanager
import json
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


BASE = "http://photography:8080"
EVIDENCE = Path("/evidence/snapshot.json")


def request(path, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(
        urllib.request.Request(
            BASE + path, data=data, method=method, headers={"Content-Type": "application/json"}
        ),
        timeout=15,
    ) as response:
        return response.read(), response.headers


def read_json(path, method="GET", body=None):
    data, headers = request(path, method, body)
    assert headers["Cache-Control"] == "no-store", dict(headers)
    return json.loads(data)


@contextmanager
def editor_session():
    session = read_json("/api/editor-session", "POST", {
        "editorId": str(uuid.uuid4()), "alias": "Container smoke",
    })
    headers = {"X-Editor-Session": session["sessionId"], "X-Editor-Tab": str(uuid.uuid4())}
    with urllib.request.urlopen(
        urllib.request.Request(BASE + "/api/editor-session/events", headers=headers), timeout=15,
    ) as stream:
        event, data = None, []
        while True:
            raw = stream.readline()
            assert raw, "Editor stream ended before binding"
            line = raw.decode().rstrip("\r\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data.append(line[5:].strip())
            elif not line and event is not None:
                assert event == "bound", (event, data)
                binding = json.loads("\n".join(data))
                break
        assert binding["sessionId"] == headers["X-Editor-Session"]
        assert binding["tabId"] == headers["X-Editor-Tab"]
        headers["X-Editor-Generation"] = binding["generation"]

        def exchange(method, path, body=None):
            data = None if body is None else json.dumps(body).encode()
            with urllib.request.urlopen(
                urllib.request.Request(
                    BASE + path, data=data, method=method,
                    headers={**headers, "Content-Type": "application/json"},
                ), timeout=15,
            ) as response:
                content = response.read()
                return json.loads(content) if content else None

        def mutate(method, path, body, target=None):
            exchange("POST", "/api/editor-session/heartbeat")
            if target is not None:
                current = exchange("GET", "/api/editor-session/target")
                selected = exchange("PUT", "/api/editor-session/target", {
                    "revision": current["revision"], "target": target,
                })
                headers["X-Editor-Grant"] = selected["grant"]["grantId"]
            return exchange(method, path, body)

        try:
            yield headers, mutate
        finally:
            exchange("DELETE", "/api/editor-session/binding")


def verify_delivery():
    health, _ = request("/health")
    assert json.loads(health)["status"] == "ok"
    index, _ = request("/")
    assert b'<div id="app">' in index, index[:300]
    assets = re.findall(rb'(?:src|href)="(/assets/[^"]+)"', index)
    assert assets, index
    for asset in assets:
        content, _ = request(asset.decode())
        assert content, asset
    deep_link, _ = request("/workspace")
    assert deep_link == index


def create_snapshot():
    assert read_json("/api/albums")["totalAlbums"] == 0
    album = read_json("/api/albums", "POST", {"year": 2026})
    opening = next(spread for spread in read_json(f'/api/albums/{album["id"]}/composition')["spreads"] if spread["chapter"] is None)
    with editor_session() as (headers, mutate):
        mutate("PUT", f'/api/albums/{album["id"]}/synopsis', {"text": "Container restart proof"}, {"kind": "spread", "id": opening["id"]})
        mutate("PUT", f'/api/albums/{album["id"]}/authors', {"authors": ["Cosmin", "Mara"]})
        previous_credentials = dict(headers)
    countries = read_json("/api/countries")["countries"]
    assert countries
    chapter = read_json(
        f'/api/albums/{album["id"]}/chapters',
        "POST",
        {"kind": "home", "countries": [countries[0]["id"]], "sourceFolder": "/photos"},
    )
    path = f'/api/chapters/{chapter["id"]}/photos'
    for _ in range(120):
        inventory = read_json(path)
        assert not inventory["processing"]["issues"], inventory
        if inventory["processing"]["state"] == "completed":
            break
        time.sleep(1)
    else:
        raise AssertionError(f"Photo processing timed out: {inventory}")
    assert inventory["counts"] == {"total": 1, "unplaced": 1}, inventory
    photo = inventory["photos"][0]
    assert (photo["filename"], photo["width"], photo["height"]) == ("smoke.jpg", 1600, 1200)
    assert 0 < photo["thumbnail"]["width"] <= 1600
    thumbnail, headers = request(photo["thumbnail"]["url"])
    assert thumbnail.startswith(b"\xff\xd8") and thumbnail.endswith(b"\xff\xd9")
    assert "immutable" in headers["Cache-Control"]
    spread_id = exercise_editing(album, chapter, photo)
    snapshot = {
        "album": album,
        "metadata": read_json(f'/api/albums/{album["id"]}'),
        "previousCredentials": previous_credentials,
        "chapters": read_json(f'/api/albums/{album["id"]}/chapters'),
        "photo": read_json(path)["photos"][0],
        "composition": read_json(f'/api/albums/{album["id"]}/composition'),
        "spread": read_json(f'/api/albums/{album["id"]}/spreads/{spread_id}'),
        "thumbnailSha256": hashlib.sha256(thumbnail).hexdigest(),
    }
    EVIDENCE.write_text(json.dumps(snapshot))


def exercise_editing(album, chapter, photo):
    album_path = f'/api/albums/{album["id"]}'
    opening = next(spread for spread in read_json(album_path + "/composition")["spreads"] if spread["chapter"] == chapter["id"])
    with editor_session() as (_, mutate):
        mutate("PUT", album_path + f'/chapters/{chapter["id"]}/description', {"text": "Persisted chapter"}, {"kind": "spread", "id": opening["id"]})
        spread = mutate(
            "POST", album_path + f'/chapters/{chapter["id"]}/spreads', {"afterSpreadId": opening["id"]}, {"kind": "spread", "id": opening["id"]}
        )
        spread_path = album_path + f'/spreads/{spread["id"]}'
        template = read_json("/api/templates/photos-only")["templates"][0]
        target = {"kind": "spread", "id": spread["id"]}
        mutate("PUT", spread_path + "/template", {"templateId": template["id"]}, target)
        slots = read_json(spread_path)["slots"]
        photo_slot = next(slot for slot in slots if slot["kind"] == "photo")
        mutate("POST", spread_path + "/placements", {"slotId": photo_slot["id"], "photoId": photo["id"]}, target)
        mutate(
            "PUT", f'/api/chapters/{chapter["id"]}/photos/{photo["id"]}/caption', {"text": "Persisted smoke caption"}
        )
    return spread["id"]


def verify_restart():
    snapshot = json.loads(EVIDENCE.read_text())
    try:
        urllib.request.urlopen(
            urllib.request.Request(
                BASE + "/api/editor-session/heartbeat", method="POST", headers=snapshot["previousCredentials"]
            ), timeout=15,
        ).close()
    except urllib.error.HTTPError as error:
        assert error.code == 409
        assert json.loads(error.read())["code"] == "stale_session"
    else:
        raise AssertionError("Editing credentials survived the backend restart")
    album = snapshot["album"]
    assert read_json("/api/albums")["totalAlbums"] == 1
    metadata = read_json(f'/api/albums/{album["id"]}')
    assert metadata == snapshot["metadata"]
    assert read_json(f'/api/albums/{album["id"]}/chapters') == snapshot["chapters"]
    assert read_json(f'/api/albums/{album["id"]}/composition') == snapshot["composition"]
    assert read_json(f'/api/albums/{album["id"]}/spreads/{snapshot["spread"]["id"]}') == snapshot["spread"]
    chapter = snapshot["chapters"]["chapters"][0]
    inventory = read_json(f'/api/chapters/{chapter["id"]}/photos')
    assert inventory["photos"] == [snapshot["photo"]], inventory
    assert not inventory["processing"]["issues"], inventory
    thumbnail, _ = request(snapshot["photo"]["thumbnail"]["url"])
    assert hashlib.sha256(thumbnail).hexdigest() == snapshot["thumbnailSha256"]


if __name__ == "__main__":
    verify_delivery()
    if sys.argv[1] == "create":
        create_snapshot()
    elif sys.argv[1] == "restart":
        verify_restart()
    else:
        raise ValueError("Expected create or restart")
    print(f"Final-container {sys.argv[1]} smoke passed")

(function (root) {
    function bound(photo, slot, transform) {
        if (typeof transform.x !== "number" || typeof transform.y !== "number" || typeof transform.zoom !== "number" ||
            !isFinite(transform.x) || !isFinite(transform.y) || !isFinite(transform.zoom) || transform.zoom < 1 || transform.zoom > 5) {
            throw new Error("Invalid photo transform");
        }
        var scale = Math.max(slot.width / photo.width, slot.height / photo.height) * transform.zoom;
        var maxX = Math.max(0, (photo.width * scale - slot.width) / 2);
        var maxY = Math.max(0, (photo.height * scale - slot.height) / 2);
        return {x:Math.max(-maxX, Math.min(maxX, transform.x)),y:Math.max(-maxY, Math.min(maxY, transform.y)),zoom:transform.zoom,mirrored:transform.mirrored};
    }
    function crop(photo, slot, transform) {
        var bounded = bound(photo, slot, transform);
        var scale = Math.max(slot.width / photo.width, slot.height / photo.height) * bounded.zoom;
        var width = photo.width * scale;
        var height = photo.height * scale;
        return {width:width,height:height,left:(slot.width-width)/2+(bounded.mirrored ? -bounded.x : bounded.x),top:(slot.height-height)/2+bounded.y};
    }
    root.PhotographyCrop = {bound:bound,crop:crop};
})(typeof globalThis === "undefined" ? $.global : globalThis);

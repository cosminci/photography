(function (root) {
    function find(items, id, label) {
        for (var i = 0; i < items.length; i++) if (items[i].id === id) return items[i];
        throw new Error("Saved " + label + " is missing from the catalog");
    }
    function describeLenses(names) {
        var lines = [], suffixes = [], groups = [];
        for (var i = 0; i < names.length; i++) {
            var name = names[i];
            var match = /^Sigma (\d+(?:\.\d+)?)mm (.+)$/.exec(name);
            if (!match) { lines.push(name); continue; }
            var suffix = match[2], group = null;
            for (var j = 0; j < suffixes.length; j++) if (suffixes[j] === suffix) group = groups[j];
            if (!group) {
                suffixes.push(suffix);
                groups.push({index:lines.length,models:[match[1]]});
                lines.push(name);
            } else {
                group.models.push(match[1]);
                lines[group.index] = "Sigma " + group.models.join(" & ") + "mm " + suffix;
            }
        }
        return lines.join(" + ");
    }
    function describeGear(selection, catalog) {
        var lines = [], galaxyModels = [], galaxyLine = null;
        for (var i = 0; i < selection.length; i++) {
            var use = selection[i];
            var camera = find(catalog.cameras,use.cameraId,"camera");
            var lenses = [];
            for (var j = 0; j < use.lensIds.length; j++) lenses.push(find(catalog.lenses,use.lensIds[j],"lens").name);
            var galaxy = /^Samsung Galaxy (.+)$/.exec(camera.name);
            if (galaxy && lenses.length === 0) {
                galaxyModels.push(galaxy[1].replace(/^(S\d+)\s+FE$/,"$1FE"));
                if (galaxyLine === null) galaxyLine = lines.length;
                lines[galaxyLine] = "Samsung Galaxy " + galaxyModels.join(" + ");
            } else lines.push(lenses.length === 0 ? camera.name : camera.name + " w/ " + describeLenses(lenses));
        }
        return lines.join("\n");
    }
    root.PhotographyGear = {describeGear:describeGear,describeLenses:describeLenses};
})(typeof globalThis === "undefined" ? $.global : globalThis);

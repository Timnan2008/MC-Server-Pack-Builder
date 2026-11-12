"""Regenerate the small, inert JAR fixtures next to this script."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

here = Path(__file__).parent

def jar(name, entries):
    with ZipFile(here / name, "w", ZIP_DEFLATED) as archive:
        for path, contents in entries.items():
            archive.writestr(path, contents)

jar("forge-multi.jar", {"META-INF/mods.toml": '''modLoader="javafml"
loaderVersion="[47,)"
[[mods]]
modId="alpha"
version="1.0"
displayName="Alpha"
[[mods]]
modId="beta"
version="2.0"
[[dependencies.alpha]]
modId="library"
mandatory=true
versionRange="[1,)"
'''
})
jar("neoforge.jar", {"META-INF/neoforge.mods.toml": '''modLoader="javafml"
[[mods]]
modId="neo"
version="3.0"
[[dependencies.neo]]
modId="neoforge"
type="required"
versionRange="[20,)"
'''})
jar("fabric.jar", {"fabric.mod.json": '''{"schemaVersion":1,"id":"fabric-example","version":"1.0",
"name":"Example","environment":"client","depends":{"fabricloader":">=0.15"},
"suggests":{"optional":"*"}}'''})
jar("invalid-fabric.jar", {"fabric.mod.json": "{not json"})
jar("traversal.jar", {"../outside.txt": "inert", "fabric.mod.json": '{"id":"safe","version":"1"}'})
(here / "bad.jar").write_bytes(b"not a zip archive")

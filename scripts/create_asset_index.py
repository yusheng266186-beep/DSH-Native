#!/usr/bin/env python3
"""Index the final, assembled APK components; app versions never stand in for contents."""
import hashlib
import pathlib
import re
import sys

assets = pathlib.Path(sys.argv[1])
rows = []
for path in sorted((assets / 'payload').rglob('*')):
    if path.is_file():
        if path.is_symlink() or not path.stat().st_size:
            raise SystemExit(f'Invalid APK component: {path.name}')
        name = path.relative_to(assets / 'payload').as_posix()
        if not re.fullmatch(r'[A-Za-z0-9_.+-]+(?:/[A-Za-z0-9_.+-]+)*', name) or any(part in ('.', '..') for part in name.split('/')):
            raise SystemExit(f'Invalid APK component path: {name}')
        rows.append(f'{name}\t{path.stat().st_size}\t{hashlib.sha256(path.read_bytes()).hexdigest()}\n')
(assets / 'runtime-assets.tsv').write_text(''.join(rows))
print(f'Indexed {len(rows)} APK components')

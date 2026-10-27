#!/usr/bin/env python3
"""Record locked package identities without inferring missing licenses."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def inventory(root=ROOT):
    groups = {}
    for group, name in [('dsh', 'runtime/core-package-lock.json'), ('codebuddy', 'runtime/codebuddy/package-lock.json')]:
        lock = json.loads((root / name).read_text())
        rows = []
        for location, package in sorted(lock['packages'].items()):
            if not location or not package.get('version'):
                continue
            rows.append({'path': location, 'version': package['version'],
                         'license': package.get('license', 'unknown'),
                         'source': package.get('resolved', 'unknown'),
                         'integrity': package.get('integrity', 'unknown')})
        groups[group] = rows
    return {'schema': 1, 'scope': 'locked dependency metadata; package license texts remain with each distribution', 'groups': groups}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    data = inventory()
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    print('Dependency notices generated: ' + ', '.join(name + '=' + str(len(rows)) for name, rows in data['groups'].items()))

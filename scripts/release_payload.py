#!/usr/bin/env python3
"""Publish a verified runtime release, with manifest uploaded last.

Use DSH_GITHUB_TOKEN or GH_TOKEN; never pass credentials on the command line.
Does not update App manifests: release.yml/release.sh remain their sole owner.
"""
import argparse
import hashlib
import json
import os
import pathlib
import urllib.parse
import urllib.request

REPO = 'yusheng266186-beep/DSH-Native'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tag')
    parser.add_argument('directory', type=pathlib.Path)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--notes', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not __import__('re').fullmatch(r'payload-v[0-9]+', args.tag):
        raise ValueError('invalid payload tag')
    token = os.environ.get('DSH_GITHUB_TOKEN') or os.environ['GH_TOKEN']

    def api(url, method='GET', data=None, binary=False):
        if isinstance(data, dict): data = json.dumps(data).encode()
        req = urllib.request.Request(url, data=data, method=method, headers={
            'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
            'Content-Type': 'application/octet-stream' if binary else 'application/json',
            'User-Agent': 'DSH-Native-payload-release',
        })
        with urllib.request.urlopen(req, timeout=240) as response:
            return json.load(response)

    manifest = json.loads((args.directory / 'manifest.json').read_text())
    names = [p['name'] for p in manifest['parts']] + ['BUILD-INFO.txt', 'SHA256SUMS.txt', 'manifest.json']
    expected = {}
    for name in names:
        if pathlib.Path(name).name != name: raise ValueError('unsafe asset name')
        path = args.directory / name
        expected[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    for part in manifest['parts']:
        assert expected[part['name']] == part['sha256']
        assert (args.directory / part['name']).stat().st_size == part['size']
    checksums = dict(line.split('  ', 1)[::-1]
                     for line in (args.directory / 'SHA256SUMS.txt').read_text().splitlines())
    assert all(checksums[name] == value for name, value in expected.items() if name != 'SHA256SUMS.txt')
    base = f'https://api.github.com/repos/{REPO}/releases'
    release = api(base, 'POST', dict(tag_name=args.tag, target_commitish=args.commit,
        name=args.tag, body=args.notes.read_text(), draft=True, prerelease=False))
    upload = release['upload_url'].split('{')[0]
    # Draft protects readers until every asset has been fully verified. The
    # manifest is still last, preserving the payload publication contract.
    for name in names:
        path = args.directory / name
        asset = api(upload + '?name=' + urllib.parse.quote(name), 'POST', path.read_bytes(), True)
        assert asset['size'] == path.stat().st_size
        assert asset.get('digest') == 'sha256:' + expected[name], f'uploaded digest differs: {name}'
        print(name + ': uploaded and SHA-verified', flush=True)
    published = api(release['url'], 'PATCH', dict(draft=False, make_latest='false'))
    with urllib.request.urlopen(f'https://github.com/{REPO}/releases/download/{args.tag}/manifest.json', timeout=60) as response:
        assert hashlib.sha256(response.read()).hexdigest() == expected['manifest.json']
    print('Published: ' + published['html_url'])


if __name__ == '__main__':
    main()

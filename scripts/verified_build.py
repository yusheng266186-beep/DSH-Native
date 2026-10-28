#!/usr/bin/env python3
"""Promote an APK only from successful full CI of the exact current Git tree."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
ARTIFACT = 'DSHNative-bootstrap'


def command(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def api(repo, path):
    return json.loads(command('gh', 'api', f'repos/{repo}/{path}'))


def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def source():
    # Promotion never uses uncommitted files or a version changed after CI.
    if command('git', 'status', '--porcelain', '--untracked-files=no'):
        raise ValueError('source has uncommitted changes')
    version = re.search(r's\("(\d+\.\d+\.\d+)"\)', (ROOT/'scripts/mkmanifest.py').read_text())[1]
    return {'source_commit': command('git', 'rev-parse', 'HEAD'),
            'source_tree': command('git', 'rev-parse', 'HEAD^{tree}'), 'version': version}


def validate(proof, run, tree, repo, version, apk, commit_tree):
    if (run.get('conclusion') != 'success' or run.get('status') != 'completed'
            or run.get('path') != '.github/workflows/build.yml'
            or run.get('repository', {}).get('full_name') != repo
            or run.get('head_repository', {}).get('full_name') != repo):
        raise ValueError('run is not successful full CI from this repository')
    expected = {'schema': 1, 'checks': 'full-android-runtime', 'repository': repo,
                'run_id': run['id'], 'run_attempt': run['run_attempt'],
                'workflow_head_sha': run['head_sha'], 'source_tree': tree, 'version': version}
    if any(proof.get(key) != value for key, value in expected.items()):
        raise ValueError('CI proof does not match run, tree or version')
    if not re.fullmatch(r'[0-9a-f]{40}', proof.get('source_commit', '')) or commit_tree != tree:
        raise ValueError('CI checkout commit has a different tree')
    if proof.get('apk_bytes') != apk.stat().st_size or proof.get('apk_sha256') != digest(apk):
        raise ValueError('CI APK size or digest mismatch')


def record(args):
    repo = os.environ['GITHUB_REPOSITORY']
    run = api(repo, 'actions/runs/'+os.environ['GITHUB_RUN_ID'])
    proof = {**source(), 'schema': 1, 'checks': 'full-android-runtime', 'repository': repo,
             'run_id': run['id'], 'run_attempt': run['run_attempt'], 'workflow_head_sha': run['head_sha'],
             'apk_bytes': args.apk.stat().st_size, 'apk_sha256': digest(args.apk)}
    args.apk.with_name('verified-build.json').write_text(json.dumps(proof, indent=2)+'\n')
    print('Recorded full CI proof for tree '+proof['source_tree'])


def reuse(args):
    repo = os.environ['GITHUB_REPOSITORY']
    current = source()
    if args.version and args.version != current['version']:
        if args.run_id:
            raise ValueError('requested version must already be committed before CI')
        return False
    if args.run_id:
        runs = [api(repo, f'actions/runs/{args.run_id}')]
    else:
        runs = api(repo, 'actions/workflows/build.yml/runs?status=success&per_page=20')['workflow_runs']
    for run in runs:
        try:
            if run['id'] == int(os.environ.get('GITHUB_RUN_ID', '0')):
                continue
            # HEAD trees cheaply exclude unrelated runs; PR checkouts may be
            # synthetic merges, so their proof's checkout tree is authoritative.
            if run.get('head_repository', {}).get('full_name') != repo:
                raise ValueError('foreign head repository')
            if not args.run_id and api(repo, 'git/commits/'+run['head_sha'])['tree']['sha'] != current['source_tree']:
                continue
            artifacts = api(repo, f"actions/runs/{run['id']}/artifacts")['artifacts']
            if not any(a['name'] == ARTIFACT and not a['expired'] for a in artifacts):
                raise ValueError('verified artifact unavailable')
            with tempfile.TemporaryDirectory(prefix='dsh-verified-ci-') as temp:
                command('gh', 'run', 'download', str(run['id']), '--repo', repo, '--name', ARTIFACT, '--dir', temp)
                directory = pathlib.Path(temp)
                proof = json.loads((directory/'verified-build.json').read_text())
                apk = directory/'DSHNative-bootstrap.apk'
                commit = api(repo, 'git/commits/'+proof['source_commit'])
                validate(proof, run, current['source_tree'], repo, current['version'], apk, commit['tree']['sha'])
                args.output.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(apk, args.output/apk.name)
                shutil.copyfile(directory/'verified-build.json', args.output/'verified-build.json')
            print(f"Reused full CI run {run['id']} for identical tree {current['source_tree']}")
            output = os.environ.get('GITHUB_OUTPUT')
            if output:
                with open(output, 'a') as stream:
                    stream.write(f"reused=true\nbuild_run_id={run['id']}\n")
            return True
        except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
            if args.run_id:
                raise ValueError(f'explicit CI run {run["id"]} cannot be promoted: {error}') from error
            print(f"CI run {run['id']} not reusable: {type(error).__name__}")
    print('No identical verified artifact; a full build is required.')
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='mode', required=True)
    create = commands.add_parser('record')
    create.add_argument('apk', type=pathlib.Path)
    promote = commands.add_parser('reuse')
    promote.add_argument('output', type=pathlib.Path)
    promote.add_argument('--run-id', type=int)
    promote.add_argument('--version')
    args = parser.parse_args()
    if args.mode == 'record':
        record(args)
    else:
        reuse(args)


if __name__ == '__main__':
    main()

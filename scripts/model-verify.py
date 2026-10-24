#!/usr/bin/env python3
"""Verify the exact phone bundle; optionally download it before verification.
Network is used only with --check-source or --download. No runtime model download.
Never replaces an existing incomplete/corrupt destination silently.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

p = argparse.ArgumentParser()
p.add_argument('--model', type=Path, default=Path(__file__).resolve().parents[1] / 'models/gemma-4-E2B-it.litertlm')
p.add_argument('--check-source', action='store_true')
p.add_argument('--download', action='store_true')
p.add_argument('--receipt', type=Path)
a = p.parse_args()
repo = 'litert-community/gemma-4-E2B-it-litert-lm'
revision = '6e5c4f1e395deb959c494953478fa5cec4b8008f'
filename = 'gemma-4-E2B-it.litertlm'
size = 2588147712
sha = '181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c'
source = f'https://huggingface.co/api/models/{repo}/revision/{revision}?blobs=true'
if a.check_source or a.download:
    with urllib.request.urlopen(source, timeout=30) as response:
        metadata = json.load(response)
    artifact = next(item for item in metadata['siblings'] if item['rfilename'] == filename)
    assert metadata['sha'] == revision
    assert artifact['size'] == size == artifact['lfs']['size']
    assert artifact['lfs']['sha256'] == sha
candidate = a.model
if a.download and not a.model.exists():
    a.model.parent.mkdir(parents=True, exist_ok=True)
    candidate = a.model.with_suffix(a.model.suffix + '.partial')
    subprocess.run(['curl', '--fail', '--location', '--retry', '3', '--continue-at', '-',
                    '--output', str(candidate), f'https://huggingface.co/{repo}/resolve/{revision}/{filename}'], check=True)
assert candidate.is_file(), f'Missing asset: {candidate}'
assert candidate.stat().st_size == size, 'Reject incomplete file or LFS pointer: byte count differs'
h = hashlib.sha256()
with candidate.open('rb') as stream:
    assert stream.read(8) == b'LITERTLM', 'Reject wrong file type'
    stream.seek(0)
    while chunk := stream.read(8 * 1024 * 1024):
        h.update(chunk)
assert h.hexdigest() == sha, 'Reject file: full SHA-256 differs'
if candidate != a.model:
    candidate.rename(a.model)
receipt = {'model_id': repo, 'revision': revision, 'filename': filename, 'bytes': size,
           'sha256': sha, 'host_path': str(a.model.resolve()), 'source': source,
           'verified_at_utc': datetime.now(timezone.utc).isoformat(), 'status': 'downloaded_integrity_verified',
           'phone_installed': False, 'phone_offline_tested': False}
if a.receipt:
    a.receipt.parent.mkdir(parents=True, exist_ok=True)
    a.receipt.write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt, indent=2))

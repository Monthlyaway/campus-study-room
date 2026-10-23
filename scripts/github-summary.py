#!/usr/bin/env python3
"""Emit only structured, non-secret verification outcomes in the Actions summary."""
import json, os
from pathlib import Path
root=Path(__file__).resolve().parent.parent
lines=['## Campus study room verification', '', 'Runner: codex-lb (Linux AMD64)', '']
for path in sorted((root/'output').glob('**/result.json')):
    value=json.loads(path.read_text())
    lines.append('- ' + str(path.relative_to(root)) + ': **' + str(value.get('result', value.get('status', 'recorded'))) + '**')
lines += ['', 'Only completed, non-skipped checks qualify for promotion. Runtime secrets and database files are excluded from artifacts.']
with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream: stream.write('\n'.join(lines)+'\n')

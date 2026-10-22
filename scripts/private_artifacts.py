"""Prevent known runtime secrets from entering CI reports, including HTML ZIP payloads."""
import base64
import io
from pathlib import Path
import re
import shutil
import zipfile


def contains_secret(data, secrets):
    if any(value in data for value in secrets):
        return True
    # Playwright's self-contained HTML embeds its report as a Base64 ZIP.
    for match in re.finditer(rb'data:application/zip;base64,([A-Za-z0-9+/=]+)', data):
        archive = base64.b64decode(match.group(1), validate=True)
        with zipfile.ZipFile(io.BytesIO(archive)) as report:
            for member in report.infolist():
                if not member.is_dir() and any(value in report.read(member) for value in secrets):
                    return True
    return False


def protect_reports(directories, secret_values, quarantine):
    """Move only unsafe report files outside artifact paths, then fail the job."""
    secrets = {str(value).encode() for value in secret_values if value and len(str(value)) >= 8}
    if not secrets:
        raise RuntimeError('No runtime secrets supplied to the artifact gate')
    quarantine = Path(quarantine)
    unsafe = []
    for index, directory in enumerate(map(Path, directories)):
        if not directory.exists():
            continue
        for path in directory.rglob('*'):
            if not path.is_file():
                continue
            if contains_secret(path.read_bytes(), secrets):
                destination = quarantine / str(index) / path.relative_to(directory)
                destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                shutil.move(str(path), destination)
                destination.chmod(0o600)
                unsafe.append(str(path.relative_to(directory)))
    if unsafe:
        raise RuntimeError(f'{len(unsafe)} report files contained runtime secrets and were quarantined outside artifact paths')

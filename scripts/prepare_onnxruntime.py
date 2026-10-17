#!/usr/bin/env python3
"""Reproduce the ARM64-only ORT AAR using < 7 MB of bounded official Maven ranges."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
URL = 'https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/onnxruntime-android-1.22.0.aar'
ETAG = '"191c000859d72c7de816af6408e14e4d"'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, help='Reuse previously extracted members after SHA-256 validation')
    args = parser.parse_args()
    members = json.loads((ROOT / 'third_party/onnxruntime/members.json').read_text())
    values = {}
    with tempfile.TemporaryDirectory(prefix='street-snap-ort-') as temporary:
        for item in members:
            cached = args.cache / item['name'] if args.cache else None
            if cached and cached.is_file():
                data = cached.read_bytes()
            else:
                start = item['offset']
                end = start + 30 + len(item['name'].encode()) + item['compressed'] + 256
                part = Path(temporary) / 'range'
                headers = Path(temporary) / 'headers'
                subprocess.run([
                    'curl', '--fail', '--silent', '--show-error', '--connect-timeout', '15',
                    '--max-time', '120', '--max-filesize', str(item['compressed'] + 1024),
                    '--range', str(start) + '-' + str(end), '-H', 'If-Match: ' + ETAG,
                    '-D', str(headers), URL, '-o', str(part),
                ], check=True)
                header_text = headers.read_text().lower()
                assert 'content-range: bytes ' + str(start) + '-' in header_text, 'Server did not honor bounded range'
                raw = part.read_bytes()
                header = struct.unpack_from('<4s5H3I2H', raw)
                assert header[0] == b'PK\x03\x04'
                assert raw[30:30 + header[9]].decode() == item['name']
                payload = raw[30 + header[9] + header[10]:30 + header[9] + header[10] + item['compressed']]
                data = zlib.decompress(payload, -15) if item['method'] == 8 else payload
            assert len(data) == item['size'], 'Unexpected uncompressed size'
            assert zlib.crc32(data) & 0xffffffff == item['crc32'], 'ZIP integrity check failed'
            assert hashlib.sha256(data).hexdigest() == item['sha256'], 'Pinned SHA-256 mismatch'
            values[item['name']] = data
        for name in ('LICENSE', 'ThirdPartyNotices.txt'):
            values['META-INF/onnxruntime/' + name] = (ROOT / 'third_party/onnxruntime' / name).read_bytes()
        output = ROOT / 'app/libs/onnxruntime-android-1.22.0-arm64.aar'
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, 'w') as archive:
            for name, data in sorted(values.items()):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, data)
        print(str(output))
        print('SHA-256:', hashlib.sha256(output.read_bytes()).hexdigest())
        print('Bytes:', output.stat().st_size)

if __name__ == '__main__':
    main()

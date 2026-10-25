#!/usr/bin/env python3
"""Prepare bounded public-domain USGS maps; the app makes no map network requests."""
import argparse
import concurrent.futures
from datetime import datetime, timezone
import hashlib
import http.client
import json
import math
import os
import pathlib
import struct
import tempfile
import time
import urllib.parse
import urllib.request
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
# Hillsborough event area. Bounds are west, south, east, north in WGS84.
BOUNDS = dict(west=-122.390, south=37.549, east=-122.345, north=37.585)
BASEMAP = 'https://basemap.nationalmap.gov/arcgis/rest/services'
LAYERS = {
    'topo': ('maps', f'{BASEMAP}/USGSTopo/MapServer', range(12, 17)),
    'aerial': ('maps-aerial', f'{BASEMAP}/USGSImageryOnly/MapServer', range(14, 17)),
    'hybrid': ('maps-hybrid', f'{BASEMAP}/USGSImageryTopo/MapServer', range(12, 17)),
    'aerial-detail': ('maps-detail', 'https://imagery.nationalmap.gov/arcgis/rest/services/USGSNAIPPlus/ImageServer', [16]),
}
DETAIL_PIXELS = 1024
MAX_BYTES = 8 * 1024 * 1024
MERCATOR_HALF = math.pi * 6378137.0


def tile(lat, lon, z):
    n = 2 ** z
    return (min(n - 1, int((lon + 180) / 360 * n)),
            min(n - 1, int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)))


def tile_bbox(z, x, y):
    """XYZ tile edges in EPSG:3857: xmin, ymin, xmax, ymax."""
    edge = 2 * MERCATOR_HALF / 2 ** z
    return (x * edge - MERCATOR_HALF, MERCATOR_HALF - (y + 1) * edge,
            (x + 1) * edge - MERCATOR_HALF, MERCATOR_HALF - y * edge)


def source_url(service, item, detail=False):
    z, x, y = item
    if not detail:
        return f'{service}/tile/{z}/{y}/{x}'
    params = dict(bbox=','.join(f'{v:.9f}' for v in tile_bbox(z, x, y)),
                  bboxSR=3857, imageSR=3857, size=f'{DETAIL_PIXELS},{DETAIL_PIXELS}', format='jpg',
                  bandIds='0,1,2', compressionQuality=90, f='image')
    return service + '/exportImage?' + urllib.parse.urlencode(params)


def raster_valid(data, expected_size, jpeg_only=False):
    """Reject error pages, pointer files, wrong sizes, and truncated containers."""
    if data.startswith(b'\x89PNG\r\n\x1a\n') and not jpeg_only:
        offset, dimensions, has_pixels = 8, None, False
        while offset + 12 <= len(data):
            size = struct.unpack('>I', data[offset:offset + 4])[0]
            kind = data[offset + 4:offset + 8]
            end = offset + 12 + size
            if end > len(data):
                return False
            payload = data[offset + 8:offset + 8 + size]
            crc = struct.unpack('>I', data[end - 4:end])[0]
            if zlib.crc32(kind + payload) & 0xffffffff != crc:
                return False
            if offset == 8:
                if kind != b'IHDR' or size != 13:
                    return False
                dimensions = struct.unpack('>II', payload[:8])
            has_pixels |= kind == b'IDAT' and size > 0
            if kind == b'IEND':
                return size == 0 and end == len(data) and has_pixels and dimensions == (expected_size, expected_size)
            offset = end
        return False
    if not data.startswith(b'\xff\xd8'):
        return False
    offset, dimensions = 2, None
    while offset + 4 <= len(data):
        if data[offset] != 0xff:
            return False
        while offset < len(data) and data[offset] == 0xff:
            offset += 1
        if offset >= len(data):
            return False
        marker = data[offset]
        offset += 1
        if marker in (0xd8, 0xd9, 0x00):
            return False
        if marker == 0x01 or 0xd0 <= marker <= 0xd7:
            continue
        if offset + 2 > len(data):
            return False
        size = struct.unpack('>H', data[offset:offset + 2])[0]
        if size < 2 or offset + size > len(data):
            return False
        if marker in (0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf):
            if size < 8:
                return False
            dimensions = struct.unpack('>HH', data[offset + 3:offset + 7])
        if marker == 0xda:
            # USGS exportImage can include a binary trailer after JPEG EOI.
            # Require the complete JPEG scan, but retain those exact source bytes.
            eoi = data.find(b'\xff\xd9', offset + size)
            return dimensions == (expected_size, expected_size) and eoi > offset + size
        offset += size
    return False


def download(url, validate=lambda data: True):
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'Travis-offline-map-preparation/1.0'})
            with urllib.request.urlopen(request, timeout=45) as response:
                data = response.read(MAX_BYTES + 1)
                length = response.headers.get('Content-Length')
            if len(data) > MAX_BYTES or (length is not None and int(length) != len(data)):
                raise ValueError('Response size mismatch or limit exceeded')
            if not validate(data):
                raise ValueError('Invalid or incomplete response')
            return data
        except (OSError, ValueError, http.client.HTTPException) as error:
            if attempt == 2:
                raise RuntimeError(f'Download failed after 3 attempts: {url}: {error}') from error
            time.sleep(attempt + 1)


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=f'.{path.name}.', suffix='.part', delete=False) as stream:
            temporary = pathlib.Path(stream.name)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def write_json(path, value):
    atomic_write(path, (json.dumps(value, indent=2) + '\n').encode())


def read_records(path):
    try:
        value = json.loads(path.read_text())
        return {row['path']: row for row in value['files']}
    except (OSError, ValueError, KeyError, TypeError):
        return {}


def fetch(item, dest, service, detail, previous):
    z, x, y = item
    relative = f'{z}/{x}/{y}.{"jpg" if detail else "png"}'
    path, url = dest / relative, source_url(service, item, detail)
    validate = lambda data: raster_valid(data, DETAIL_PIXELS if detail else 256, detail)
    known = previous.get(relative, {})
    if path.is_file() and known.get('source') == url:
        data = path.read_bytes()
        if (len(data) == known.get('bytes') and validate(data)
                and hashlib.sha256(data).hexdigest() == known.get('sha256')):
            return dict(path=relative, bytes=len(data), sha256=known['sha256'], source=url), True
    data = download(url, validate)
    atomic_write(path, data)
    return dict(path=relative, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), source=url), False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--layer', choices=LAYERS, default='topo')
    parser.add_argument('--bounds', type=float, nargs=4, metavar=('WEST', 'SOUTH', 'EAST', 'NORTH'),
                        default=list(BOUNDS.values()), help='WGS84 region; defaults to the Hillsborough venue')
    args = parser.parse_args()
    west, south, east, north = args.bounds
    if not (all(math.isfinite(v) for v in args.bounds) and -180 <= west < east <= 180 and -85.05112878 < south < north < 85.05112878):
        parser.error('Bounds must be finite, ordered, and within the Web Mercator world; antimeridian crossings are unsupported')
    bounds = dict(zip(('west', 'south', 'east', 'north'), args.bounds))
    folder, service, levels = LAYERS[args.layer]
    levels, detail = list(levels), args.layer == 'aerial-detail'
    dest = ROOT / 'app/src/main/assets' / folder
    manifest_path = dest / 'manifest.json'
    progress_path = ROOT / 'local-data/map-preparation' / f'{args.layer}-progress.json'
    metadata_url = service + '?f=pjson'
    def valid_metadata(data):
        value = json.loads(data)
        return isinstance(value, dict) and 'error' not in value
    metadata_data = download(metadata_url, valid_metadata)
    metadata = json.loads(metadata_data)
    if detail:
        if metadata.get('allowCopy') is not True or 'Image' not in metadata.get('capabilities', '').split(','):
            raise RuntimeError('Image service does not permit copying and image export')
    elif metadata.get('exportTilesAllowed') is not True:
        raise RuntimeError('Offline tile export not permitted by service')
    jobs = []
    for z in levels:
        x0, y0 = tile(north, west, z)
        x1, y1 = tile(south, east, z)
        jobs.extend((z, x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1))
    previous = read_records(manifest_path)
    previous.update(read_records(progress_path))
    files, errors, reused = [], [], 0
    print(f'Preparing {args.layer}: {len(jobs)} tiles, zooms {levels}, bounds {bounds}', flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(fetch, job, dest, service, detail, previous) for job in jobs]
        for completed, future in enumerate(concurrent.futures.as_completed(futures), 1):
            try:
                record, skipped = future.result()
                files.append(record)
                reused += skipped
                previous[record['path']] = record
                # Outside assets: resumable receipts are explicitly not a complete map manifest.
                write_json(progress_path, dict(complete=False, source=service, files=list(previous.values())))
            except Exception as error:
                errors.append(str(error))
            if completed % 25 == 0 or completed == len(jobs):
                print(f'{completed}/{len(jobs)} processed; {len(files)} verified ({reused} reused), {len(errors)} failed', flush=True)
    if errors or len(files) != len(jobs):
        raise RuntimeError(f'Incomplete map preparation; final manifest not replaced. {len(errors)} failures: ' + '\n'.join(errors))
    files.sort(key=lambda item: tuple(map(int, pathlib.PurePosixPath(item['path']).with_suffix('').parts)))
    manifest = dict(region='Hillsborough event area' if bounds == BOUNDS else 'Custom prepared bounds',
                    bounds=bounds, zooms=levels, source=service, layer=args.layer,
                    attribution=metadata.get('copyrightText', 'USGS, USDA, The National Map'),
                    license=('USGS public-domain topographic data; service allows exportTiles' if args.layer == 'topo'
                             else 'USDA/USGS public-domain orthoimagery in the conterminous US; service permits ' + ('copy and image export' if detail else 'exportTiles')),
                    license_source=('https://www.usgs.gov/faqs/are-usgs-topographic-maps-copyrighted' if args.layer == 'topo' else metadata_url),
                    metadata_source=metadata_url, metadata_bytes=len(metadata_data),
                    metadata_sha256=hashlib.sha256(metadata_data).hexdigest(), metadata=metadata,
                    prepared_at=datetime.now(timezone.utc).isoformat(), complete=True,
                    request_kind='exportImage' if detail else 'cached tile', tile_pixels=DETAIL_PIXELS if detail else 256,
                    cached_max_zoom=max(levels),
                    detail_note=('1024px RGB JPEG exports over XYZ zoom-16 extents; same sampling as 512px zoom-17 exports with fewer requests. Source imagery resolution and date vary.' if detail else 'Cached source tiles through zoom 16; closer zoom magnifies source detail.'),
                    hash_note='Locally calculated full SHA-256; USGS does not publish independent tile digests.', files=files)
    if detail:
        manifest['export_parameters'] = dict(bboxSR=3857, imageSR=3857, size=f'{DETAIL_PIXELS},{DETAIL_PIXELS}', format='jpg', bandIds='0,1,2', compressionQuality=90, f='image')
    write_json(manifest_path, manifest)
    print(f'Verified {len(files)} raster files, {sum(item["bytes"] for item in files)} bytes. Manifest: {manifest_path}. No external glyph/sprite/style dependencies.', flush=True)


if __name__ == '__main__':
    main()

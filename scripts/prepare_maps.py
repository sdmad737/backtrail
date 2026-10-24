#!/usr/bin/env python3
"""Small public-domain USGS cache; no runtime map network requests."""
import argparse, concurrent.futures, hashlib, json, math, pathlib, urllib.request
ROOT = pathlib.Path(__file__).resolve().parents[1]
DEST = ROOT / 'app/src/main/assets/maps'
SERVICE = 'https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer'
# Hillsborough event area, an explicit bounded preparation region.
BOUNDS = dict(west=-122.390, south=37.549, east=-122.345, north=37.585)
def tile(lat, lon, z):
    n=2**z
    return int((lon+180)/360*n), int((1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*n)
def fetch(item):
    z,x,y=item; url=f'{SERVICE}/tile/{z}/{y}/{x}'; dest=DEST/f'{z}/{x}/{y}.png'
    dest.parent.mkdir(parents=True,exist_ok=True)
    if dest.exists(): data=dest.read_bytes()
    else:
        with urllib.request.urlopen(url,timeout=60) as response: data=response.read()
    if not (data.startswith(b'\x89PNG\r\n\x1a\n') or data.startswith(b'\xff\xd8')): raise ValueError(f'Not a raster: {url}')
    dest.write_bytes(data)
    return dict(path=str(dest.relative_to(DEST)),bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),source=url)
def main():
    global DEST, SERVICE
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--layer',choices=['topo','aerial'],default='topo')
    args=parser.parse_args()
    aerial=args.layer=='aerial'
    if aerial:
        DEST=ROOT/'app/src/main/assets/maps-aerial'
        SERVICE='https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer'
    levels=list(range(14 if aerial else 12,17))
    DEST.mkdir(parents=True,exist_ok=True)
    with urllib.request.urlopen(SERVICE+'?f=pjson') as response: metadata=json.load(response)
    if not metadata.get('exportTilesAllowed'): raise RuntimeError('Offline export not permitted by service')
    jobs=[]
    for z in levels:
        x0,y0=tile(BOUNDS['north'],BOUNDS['west'],z);x1,y1=tile(BOUNDS['south'],BOUNDS['east'],z)
        jobs += [(z,x,y) for x in range(x0,x1+1) for y in range(y0,y1+1)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: files=list(pool.map(fetch,jobs))
    manifest=dict(region='Hillsborough event area',bounds=BOUNDS,zooms=levels,source=SERVICE,
      attribution=metadata['copyrightText'],license=('USDA/USGS public-domain orthoimagery in the conterminous US; service allows exportTiles' if aerial else 'USGS public-domain topographic data; service allows exportTiles'),
      license_source=(SERVICE+'?f=pjson' if aerial else 'https://www.usgs.gov/faqs/are-usgs-topographic-maps-copyrighted'),
      layer='aerial' if aerial else 'topo',cached_max_zoom=16,
      detail_note='USGS currently serves cached tiles only through zoom 16 here; closer zoom magnifies source detail.',
      hash_note='Locally calculated full SHA-256; USGS does not publish independent tile digests.',files=files)
    (DEST/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(f'Verified {len(files)} raster files, {sum(x["bytes"] for x in files)} bytes. Layer {args.layer}; zooms {levels}. No external glyph/sprite/style dependencies.')
if __name__=='__main__': main()

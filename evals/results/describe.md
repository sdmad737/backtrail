# Describe a landmark photo

Model `gemma-4-E2B-it.litertlm`, LiteRT-LM 0.18.0, Mac GPU, greedy, thinking off, max_tokens 384. 11 photos (8 private garden + junction-a/b street + porch-a). Latency measured under 4-way GPU contention (3 other eval agents running Gemma concurrently).

| condition | prompt | schema | valid (app validator) | fenced ``` | mean features (valid) | transient mention (of valid) | junction-a 'Lalibela' in features | median ms | p90 ms |
|---|---|---|---|---|---|---|---|---|---|
| baseline | generic | no | 0/11 (0%) | 11/11 (100%) | n/a | n/a | no (raw mentions: no) | 1941 | 3170 |
| bootstrap | DESCRIBE_PROMPT_V1 | no | 0/11 (0%) | 11/11 (100%) | n/a | n/a | no (raw mentions: no) | 1756 | 2318 |
| ours | DESCRIBE_PROMPT | yes | 11/11 (100%) | 0/11 (0%) | 6.36 | 2/11 (18%) | no (raw mentions: no) | 1188 | 1924 |

Transient terms (whole-word, case-insensitive): person, people, pedestrian, man, woman, car, cars, vehicle, bus, tram, bike, bicycle, cyclist, dog, cat, animal, shadow, sunlight, weather, cloud.
Validity mirrors ProposalValidator.description: the whole reply must parse as JSON with exactly label + fixedFeatures; label non-blank ≤100 chars; ≤8 features, each non-blank ≤200 chars. A fenced reply fails.

## Diagnostic only: content after stripping ``` fences (NOT what the app accepts)

The app rejects fenced replies, so this is not a validity number. It separates the prompt's effect on content from the schema's effect on format.

| condition | parses after fence strip | transient mention (of those) | mean features | junction-a 'Lalibela' in features |
|---|---|---|---|---|
| baseline | 10/11 | 4/10 (bicycle, car, cat, tram) | 6.30 | no |
| bootstrap | 11/11 | 3/11 (car, cars, cat, pedestrian, people, tram, vehicle) | 7.55 | no |
| ours | 11/11 | 2/11 (cat, tram) | 6.36 | no |

## Example outputs (verbatim, first 3 street/porch photos)

### baseline
- `junction-a.jpg` (INVALID): `ˋˋˋjson {   "label": "street scene with historic buildings and tram lines",   "fixedFeatures": [     "A street scene featuring multi-story brick buildings on the left and right.",     "A tram or streetcar is visible on the right side of the road.",     "There are overhead wires for the tram system.",     "A dark-colored car is driving in the foreground on the left.",     "Pedestrians are visible on the sidewalk and near the tram.",     "The sky is partly cloudy with blue patches.",     "The architecture suggests an older European city setting."   ] } ˋˋˋ`
- `junction-b.jpg` (INVALID): `ˋˋˋjson {   "label": "street scene with a tram",   "fixedFeatures": [     "a street with tram tracks in the foreground",     "a blue car driving on the street on the left",     "a row of brick and stucco buildings lining the street",     "a tram with a blue and yellow livery on the right side of the street",     "pedestrians walking on the sidewalk",     "overhead wires for the tram system"   ] } ˋˋˋ`
- `porch-a.jpg` (INVALID): `ˋˋˋjson {   "label": "cat on a railing",   "fixedFeatures": [     "A tabby cat with brown and grey stripes is sitting on a red railing.",     "The cat is looking slightly to the left of the camera.",     "The background consists of a light blue/grey corrugated metal wall on the left and dense green foliage on the right.",     "The cat is wearing a red collar."   ] } ˋˋˋ`

### bootstrap
- `junction-a.jpg` (INVALID): `ˋˋˋjson {   "label": "Urban street scene with brick buildings and tram lines",   "fixedFeatures": [     "Brick multi-story buildings lining the street",     "Tram tracks running along the street",     "Overhead electrical wires for the tram system",     "Streetlights or utility poles",     "Commercial storefronts on the ground level",     "Parked cars on the road",     "A blue and white tram vehicle",     "A pedestrian crossing area"   ] } ˋˋˋ`
- `junction-b.jpg` (INVALID): `ˋˋˋjson {   "label": "Urban street scene with buildings and tram tracks",   "fixedFeatures": [     "Brick residential/commercial buildings lining the street",     "Tram tracks embedded in the road surface",     "Overhead electrical wires for the tram system",     "A blue and white tram visible on the right side",     "A dark blue car driving on the road in the foreground",     "Multiple parked bicycles along the sidewalk area",     "Streetlights or utility poles visible on the left",     "A pedestrian area with people walking near the tram"   ] } ˋˋˋ`
- `porch-a.jpg` (INVALID): `ˋˋˋjson {   "label": "Cat with tabby markings on a wooden railing",   "fixedFeatures": [     "A tabby cat with brown and grey markings",     "The cat is wearing a red collar",     "The cat is standing on a red wooden surface",     "A vertical red wooden post is visible behind the cat",     "A light blue corrugated metal wall is in the background",     "Dense green foliage is visible in the background",     "The cat has prominent whiskers",     "The cat is looking towards the left side of the frame"   ] } ˋˋˋ`

### ours
- `junction-a.jpg` (valid): `{"label": "Red brick buildings with white trim", "fixedFeatures": [ "Red brick buildings", "White trim around windows", "Multi-story residential/commercial facade", "Balconies on the upper floors", "Ground floor shop fronts", "Street lamp posts", "Overhead wires spanning the street", "Asphalt road surface in foreground" ]}`
- `junction-b.jpg` (valid): `{"label": "Red brick building facade", "fixedFeatures": [ "Red brick building facade, left", "White balcony structure, left", "Ground floor shop fronts, centre", "Street lamp post, left", "Tram tracks, foreground", "Blue and white tram, right", "Green foliage, background", "Overhead wires, top" ]}`
- `porch-a.jpg` (valid): `{"label": "Red railing and cat on ledge", "fixedFeatures": [ "Red railing in foreground", "Cat sitting on red ledge", "Light blue corrugated wall on left", "Dark green foliage in background" ]}`

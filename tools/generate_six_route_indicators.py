"""Generate compact six-direction colour-light indicators and their 16 orientations.

The nine bilateral combinations use left 1/12/123 and right 4/45/456.
Existing models are kept intact; only the six missing pairs and two one-sided
models are generated with JSON cuboids; diagonal edges use half-unit steps, matching indicator_1-2.
"""
import copy
import itertools
from PIL import Image
from generate_triple_indicators import (ASSETS, MODELS, element, union_boxes,
    write_json, build_rotations_and_shapes, read_model, join_strip_center)
from indicator_textures import write_indicator_textures

BILATERAL = [left + right for left in ((1,), (1, 2), (1, 2, 3))
             for right in ((4,), (4, 5), (4, 5, 6))]
EXISTING = {(1, 4), (1, 2, 4), (1, 4, 5)}
COMBINATIONS = {"indicator_" + "-".join(map(str, routes)): routes
                for routes in [(1, 2, 3), (4, 5, 6)] +
                [routes for routes in BILATERAL if routes not in EXISTING]}
DIRECTIONS = {1: (-1, 1), 2: (-1, 0), 3: (-1, -1),
              4: (1, 1), 5: (1, 0), 6: (1, -1)}


def lamp_centres(route, raised):
    dx, dy = DIRECTIONS[route]
    # 1.5-unit pitch keeps even the horizontal arms within one block at every yaw.
    return [(8 + dx * i * 1.5, (8 if raised else 3.5) + dy * i * 1.5) for i in range(5)]


def hull(points):
    def cross(o, a, b):
        return (a[0]-o[0])*(b[1]-o[1])-(a[1]-o[1])*(b[0]-o[0])
    points = sorted(set(points))
    lower, upper = [], []
    for seq, result in ((points, lower), (reversed(points), upper)):
        for point in seq:
            while len(result) >= 2 and cross(result[-2], result[-1], point) <= 0:
                result.pop()
            result.append(point)
    return lower[:-1] + upper[:-1]


def raster_plate(polygon):
    """Rasterize straight polygon edges into the model family's cuboid grid.

    Every edge must be horizontal, vertical or a single constant-slope 45-degree
    line. No circle samples or changing-slope corner profiles are permitted.
    """
    edges = list(zip(polygon, polygon[1:]+polygon[:1]))
    for a,b in edges:
        dx,dy = abs(b[0]-a[0]),abs(b[1]-a[1])
        assert dx == 0 or dy == 0 or abs(dx-dy) < 1e-9, (a,b)
    cells = []
    for ix in range(32):
        for iy in range(32):
            x, y = (ix+.5)/2, (iy+.5)/2
            if all((b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]) >= -1e-9 for a,b in edges):
                cells.append((ix/2, iy/2, 7, (ix+1)/2, (iy+1)/2, 9))
    return cells


def side_outline(centres):
    # Long, sharp 45-degree mitres instead of the previous short octagonal cap
    # (which looked like a rounded lamp-end when seen together with the hood).
    offsets = ((0,-1.5),(1.5,0),(0,1.5),(-1.5,0))
    return hull([(x+dx,y+dy) for x,y in centres for dx,dy in offsets])


def top_outline(raised):
    # Share the side fans' x=.5 / 15.5 edges: the former .75 inset let the
    # fans protrude and interrupt each upper chamfer with a second short step.
    # Four equal .5-wide/.5-high steps now match the indicator_1-2 shoulders.
    # The entire side fan stays below this roof, so panel union cannot round it.
    cy = 8 if raised else 3.5
    return [(6,cy-2.5),(10,cy-2.5),(15.5,cy+3),
            (15.5,cy+6),(13.5,cy+8),(2.5,cy+8),
            (.5,cy+6),(.5,cy+3)]


def stepped_plate(centres):
    return raster_plate(side_outline(centres))


def authored_top_plate(raised):
    return raster_plate(top_outline(raised))


def panel_polygons(routes, raised):
    # Union panels, not a global hull: 1456 retains the 14 apron + 456 fan.
    polygons = [top_outline(raised)] if 1 in routes and 4 in routes else []
    for family in ((1,2,3),(4,5,6)):
        active = [r for r in family if r in routes]
        if active and not (len(active) == 1 and 1 in routes and 4 in routes):
            polygons.append(side_outline([p for r in active for p in lamp_centres(r,raised)]))
    return polygons


# These complete backplates are authored PNGs, not generated outlines. Right
# variants are exact mirrors of their left counterparts; the full fan is authored
# symmetrically. Keeping the source images authoritative preserves later edits.
AUTHORED_BACKPLATES = {
    (1,2,3): ((1,2,3), False),
    (4,5,6): ((1,2,3), True),
    (1,2,3,4): ((1,2,3,4), False),
    (1,4,5,6): ((1,2,3,4), True),
    (1,2,3,4,5): ((1,2,3,4,5), False),
    (1,2,4,5,6): ((1,2,3,4,5), True),
    (1,2,3,4,5,6): ((1,2,3,4,5,6), False),
}


def backplate(routes, raised):
    if routes in AUTHORED_BACKPLATES:
        source_routes, mirrored = AUTHORED_BACKPLATES[routes]
        source = 'indicator_' + '-'.join(map(str,source_routes)) + '_back.png'
        with Image.open(ASSETS / 'textures/block/indicator' / source) as image:
            image = image.convert('RGBA')
        assert image.size == (32,32), source
        return union_boxes([
            ((31-x if mirrored else x)/2, (31-y)/2, 7,
             (32-x if mirrored else x+1)/2, (32-y)/2, 9)
            for y in range(32) for x in range(32) if image.getpixel((x,y))[3]
        ])
    cy = 8 if raised else 3.5
    panels = [box for polygon in panel_polygons(routes, raised) for box in raster_plate(polygon)]
    return union_boxes(panels + [(7,1,7,9,cy,9)])


def base_beside_plate(plate):
    # Keep only the original foot volume not occupied by the authored plate.
    # The filled lower edge of the full fan now also covers the grey side lips.
    return union_boxes([
        (x/2,y/2,7,(x+1)/2,(y+1)/2,9)
        for x in range(13,19) for y in range(2)
        if not any(b[0] <= (x+.5)/2 < b[3] and b[1] <= (y+.5)/2 < b[4]
                   for b in plate)
    ])


def rear_braces(routes, raised):
    cy = 8 if raised else 3.5
    pieces = [(7,0,6,9,cy,7)]
    # Reuse the authored 1-4 diagonal rib, clipped at its centre before moving
    # or reflecting it. Do not reflect the original ground-reaching post.
    left = []
    for e in read_model('indicator_1-4_null')['elements']:
        a,b = e['from'],e['to']
        if '_strip_' in e.get('name','') and b[0] <= 8 and b[1] > 3.5:
            left.append((a[0],max(3.5,a[1]),6,b[0],b[1],7))
    for route in routes:
        if route in (2,5):
            pieces.append((2.5,cy-1,6,8,cy+.5,7) if route==2 else (8,cy-1,6,13.5,cy+.5,7))
            continue
        for x,y,z,X,Y,Z in left:
            if route >= 4:
                x,X = 16-X,16-x
            if route in (3,6):
                y,Y = cy-(Y-3.5),cy-(y-3.5)
            else:
                y,Y = y+cy-3.5,Y+cy-3.5
            pieces.append((x,y,z,X,Y,Z))
    return join_strip_center(pieces, cy, routes)


def make_models():
    for prefix, routes in COMBINATIONS.items():
        raised = 3 in routes or 6 in routes
        lamps = {route: lamp_centres(route, raised) for route in routes}
        centres = sorted(set(itertools.chain.from_iterable(lamps.values())))
        plate = backplate(routes, raised)
        groups = {"base": base_beside_plate(plate),
                  "strip": rear_braces(routes, raised),
                  "back": plate, "hood": []}
        for x, y in centres:
            # Existing style: upper visor, two side cheeks, dark rear lip.
            groups["hood"].extend([(x-.5,y+.5,9,x+.5,y+1,11),
                (x-1,y-.5,9,x-.5,y+.5,10.5),
                (x+.5,y-.5,9,x+1,y+.5,10.5),
                (x-.5,y-1,9,x+.5,y+.5,9.25)])
        groups["hood"] = union_boxes(groups["hood"])
        shell = {"credit": "MTR BR indicator family: compact six-direction variants",
            "textures": {"base": "mtr_brsignal_addon:block/grey",
                "back": "mtr_brsignal_addon:block/indicator/indicator_1-4_back",
                "hood": "mtr_brsignal_addon:block/indicator/indicator_1-4",
                "strip": "mtr_brsignal_addon:block/indicator/indicator_1-4_strip",
                "particle": "mtr_brsignal_addon:block/grey"},
            "elements": [element(f"{prefix}_{group}_{i}", box, group)
                         for group, boxes in groups.items() for i, box in enumerate(boxes)]}
        write_json(MODELS / f"{prefix}_null.json", shell)
        write_json(ASSETS / f"models/item/{prefix}.json", {"parent": f"mtr_brsignal_addon:block/{prefix}_null"})
        for route, positions in lamps.items():
            lit = copy.deepcopy(shell)
            lit["textures"]["light"] = "mtr_brsignal_addon:block/white"
            lit["elements"].extend(element(f"{prefix}_route_{route}_{i}",
                (x-.5,y-.5,9,x+.5,y+.5,9.5), "light") for i,(x,y) in enumerate(positions))
            write_json(MODELS / f"{prefix}_{route}.json", lit)
    for prefix, routes in COMBINATIONS.items():
        write_indicator_textures(ASSETS, prefix, routes)
    build_rotations_and_shapes(COMBINATIONS, "SixRouteIndicatorShapes", "generate_six_route_indicators.py")



if __name__ == "__main__":
    make_models()
    from generate_signal_mounts import generate as generate_mounts
    generate_mounts()
    print(f"Generated {len(COMBINATIONS)} new indicators; bilateral family now contains 9 combinations.")

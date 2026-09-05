"""Geometry, resources, registration and renderer contracts for all six routes."""
import itertools
import json
import re
import unittest
from generate_six_route_indicators import (COMBINATIONS, BILATERAL, ASSETS, MODELS,
    top_outline, side_outline, lamp_centres)
from generate_triple_indicators import ROOT, rotate, mirror

JAVA = ROOT / 'src/main/java/org/mtrbr'

def read(path):
    return path.read_text(encoding='utf-8-sig')

def model(name):
    return json.loads(read(MODELS / (name+'.json')))

def box(e):
    return tuple(e['from']+e['to'])

class SixRoutes(unittest.TestCase):
    def test_nine_bilateral_combinations_and_registration(self):
        self.assertEqual(len(BILATERAL), 9)
        expected = {'indicator_'+suffix for suffix in (
            '1','1-2','1-2-3','4','4-5','4-5-6','1-4','1-4-5','1-4-5-6',
            '1-2-4','1-2-4-5','1-2-4-5-6','1-2-3-4','1-2-3-4-5','1-2-3-4-5-6')}
        registry = read(JAVA/'MTRBR.java')
        actual = set(re.findall(r'BLOCKS.register\("(indicator_[0-9-]+)"', registry))
        self.assertEqual(actual, expected)
        for folder in ('blockstates','models/item'):
            self.assertEqual({p.stem for p in (ASSETS/folder).glob('indicator_*.json')}, expected)
        registry, client, entity = [read(p) for p in (JAVA/'MTRBR.java', JAVA/'client/ClientSetup.java', JAVA/'block/ColorLightIndicatorBlockEntity.java')]
        for routes in BILATERAL:
            prefix = 'indicator_'+'-'.join(map(str,routes))
            self.assertTrue((MODELS / (prefix+'_null.json')).exists())
        for prefix in COMBINATIONS:
            suffix=prefix[10:].replace('-','_'); constant='COLOR_LIGHT_INDICATOR_'+suffix
            for part in ('BLOCK', 'ITEM', 'BLOCK_ENTITY'):
                self.assertIn(constant+'_'+part+' = ',registry)
            self.assertIn('SixRouteIndicatorShapes.ROUTES_'+suffix,registry)
            self.assertIn('new ItemStack('+constant+'_ITEM.get())',registry)
            self.assertIn('MTRBR.'+constant+'_BLOCK.get(), RenderType.cutout()',client)
            self.assertIn('MTRBR.'+constant+'_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new',client)
            self.assertIn('if (state.is(MTRBR.'+constant+'_BLOCK.get())) return MTRBR.'+constant+'_BLOCK_ENTITY.get()',entity)
            for lang in ('en_us','zh_cn'):
                self.assertIn('block.mtr_brsignal_addon.'+prefix,json.loads(read(ASSETS/f'lang/{lang}.json')))
            item=json.loads(read(ASSETS/f'models/item/{prefix}.json'))
            self.assertEqual(item['parent'],'mtr_brsignal_addon:block/'+prefix+'_null')

    def test_json_cuboids_only(self):
        for prefix in COMBINATIONS:
            for path in MODELS.glob(prefix + '_*.json'):
                data = json.loads(read(path))
                self.assertNotIn('loader', data, path)
                self.assertNotIn('model', data, path)
                self.assertTrue(data['elements'], path)
            self.assertFalse(list(MODELS.glob(prefix + '_*.obj')))
        self.assertFalse((MODELS / 'indicator_straight_edges.mtl').exists())

    def test_all_states_are_complete_and_unlit(self):
        for file in (ASSETS/'blockstates').glob('indicator_*.json'):
            variants=json.loads(read(file))['variants']
            variants = {k.replace(",hanging=false", ""): v for k, v in variants.items() if "hanging=true" not in k}
            self.assertEqual(len(variants),112,file)
            for facing,index,route in itertools.product(('north','east','south','west'),range(4),('off','1','2','3','4','5','6')):
                key=f'facing={facing},is_22_5={str(bool(index&1)).lower()},is_45={str(bool(index&2)).lower()},route={route}'
                entry=variants[key]
                m=model(entry['model'].split('/')[-1])
                self.assertFalse(any('_route_' in e.get('name','') for e in m['elements']),file)

    def test_five_lights_per_route_direction_and_clear_front(self):
        for prefix,routes in COMBINATIONS.items():
            shell=model(prefix+'_null')['elements']
            centre=None
            for route in routes:
                m=model(f'{prefix}_{route}');self.assertEqual(m['elements'][:-5],shell)
                lights=[e for e in m['elements'] if e['name'].startswith(f'{prefix}_route_{route}_')]
                self.assertEqual(len(lights),5)
                first=box(lights[0]);centre=first if centre is None else centre;self.assertEqual(first,centre)
                for light in lights:
                    a,b=light['from'],light['to'];self.assertEqual([b[i]-a[i] for i in range(3)],[1,1,.5])
                    for x,y in itertools.product((a[0]+.01,b[0]-.01),(a[1]+.01,b[1]-.01)):
                        self.assertFalse(any(e['from'][0]<x<e['to'][0] and e['from'][1]<y<e['to'][1] and e['to'][2]>b[2] for e in shell))
                dx=lights[-1]['from'][0]-lights[0]['from'][0];dy=lights[-1]['from'][1]-lights[0]['from'][1]
                self.assertEqual(dx,-6 if route<=3 else 6)
                self.assertEqual(dy,{1:6,2:0,3:-6,4:6,5:0,6:-6}[route])
            for texture in model(prefix+'_null')['textures'].values():
                self.assertTrue((ASSETS/'textures'/ (texture.split(':')[1]+'.png')).exists())

    def test_all_rotations_fit_one_block_and_match_collision_and_lights(self):
        shapes=read(JAVA/'block/SixRouteIndicatorShapes.java')
        for prefix in COMBINATIONS:
            section=re.search(r'ROUTES_'+prefix[10:].replace('-','_')+r' = \{(.*?)\n\t};',shapes,re.S).group(1)
            bounds=[tuple(map(float,s.split(','))) for s in re.findall(r'Block.box\(([^)]+)\)',section)]
            states=json.loads(read(ASSETS/f'blockstates/{prefix}.json'))['variants']
            states = {k.replace(",hanging=false", ""): v for k, v in states.items() if "hanging=true" not in k}
            for fi,facing in enumerate(('north','east','south','west')):
                for index in range(4):
                    entry=states[f'facing={facing},is_22_5={str(bool(index&1)).lower()},is_45={str(bool(index&2)).lower()},route=off']
                    points=[]
                    for e in model(entry['model'].split('/')[-1])['elements']:
                        angle=e['rotation']['angle']-entry.get('y',0)
                        # Same transform as -(SignalLogic angle + 180) in the light renderer.
                        runtime=-({'north':180,'east':270,'south':0,'west':90}[facing]+index*22.5+180)
                        self.assertAlmostEqual((angle-runtime)%360,0)
                        points.extend(rotate(p,angle) for p in itertools.product(*zip(e['from'],e['to'])))
                    actual=tuple(min(p[i] for p in points) for i in range(3))+tuple(max(p[i] for p in points) for i in range(3))
                    self.assertEqual(actual[1],0)
                    for value in actual:self.assertTrue(-1e-6<=value<=16+1e-6,(prefix,facing,index,actual))
                    for a,b in zip(actual,bounds[fi*4+index]):self.assertAlmostEqual(a,b,delta=.000051)

    def test_solid_ground_support_and_filled_sides(self):
        for prefix,routes in COMBINATIONS.items():
            elements=model(prefix+'_null')['elements'];boxes=[box(e) for e in elements]
            # All shell pieces must be connected to the foot by faces/volume, not corners.
            visited={i for i,b in enumerate(boxes) if b[1]==0};self.assertTrue(visited)
            pending=list(visited)
            while pending:
                b=boxes[pending.pop()]
                for i,c in enumerate(boxes):
                    if i in visited:continue
                    overlap=[min(b[j+3],c[j+3])-max(b[j],c[j]) for j in range(3)]
                    if min(overlap)>=0 and sum(v>0 for v in overlap)>=2:
                        visited.add(i);pending.append(i)
            self.assertEqual(len(visited),len(boxes),prefix)
            back=[box(e) for e in elements if '_back_' in e.get('name','')]
            cy=8 if 3 in routes or 6 in routes else 3.5
            for side,diag in ((2,1),(2,3),(5,4),(5,6)):
                if side not in routes or diag not in routes:continue
                sign=-1 if side==2 else 1; vertical=1 if diag in (1,4) else -1
                for step in range(1,25):
                    x=8+sign*step/4
                    for n in range(step+1):
                        y=cy+vertical*n/4
                        self.assertTrue(any(b[0]<=x<=b[3] and b[1]<=y<=b[4] for b in back),(prefix,x,y))

    def test_backplate_geometry_matches_authored_images_and_mirrors(self):
        from PIL import Image
        pairs = {
            '1-2-3': '4-5-6',
            '1-2-3-4': '1-4-5-6',
            '1-2-3-4-5': '1-2-4-5-6',
            '1-2-3-4-5-6': '1-2-3-4-5-6',
        }
        def image(suffix):
            with Image.open(ASSETS/f'textures/block/indicator/indicator_{suffix}_back.png') as im:
                return im.convert('RGBA')
        for left,right in pairs.items():
            self.assertEqual(image(left).transpose(Image.Transpose.FLIP_LEFT_RIGHT).tobytes(),
                             image(right).tobytes(), (left,right))
            for suffix in (left,right):
                im=image(suffix)
                expected={(x,31-y) for x,y in itertools.product(range(32),repeat=2)
                          if im.getpixel((x,y))[3]}
                back=[box(e) for e in model(f'indicator_{suffix}_null')['elements']
                      if '_back_' in e.get('name','')]
                actual={(x,y) for b in back for x,y in itertools.product(
                    range(round(b[0]*2),round(b[3]*2)),range(round(b[1]*2),round(b[4]*2)))}
                self.assertEqual(actual,expected,suffix)
                self.assertTrue(all(b[2]==7 and b[5]==9 for b in back),suffix)
                xs=[x for x,y in actual];ys=[y for x,y in actual]
                for corner in itertools.product((min(xs),max(xs)),(min(ys),max(ys))):
                    self.assertNotIn(corner,actual,(suffix,'square outer corner',corner))

    def test_rear_route_braces_and_both_support_layers(self):
        for prefix,routes in COMBINATIONS.items():
            parts = model(prefix+'_null')['elements']
            cy = 8 if 3 in routes or 6 in routes else 3.5
            def covers(x,y,z,group=None):
                return any((group is None or '_'+group+'_' in e.get('name','')) and
                           all(e['from'][i] <= v < e['to'][i] for i,v in enumerate((x,y,z)))
                           for e in parts)
            # Rear strip reaches ground; the plate-thickness stem meets its base.
            for y in (0.25,0.75,1.25,2.25,cy-.25):
                self.assertTrue(covers(8,y,6.5,'strip'), (prefix,y))
                self.assertTrue(covers(8,y,8), (prefix,y))
            self.assertTrue(covers(8,1.25,8,'back'), prefix)
            # Ribs follow every active arm on the REAR, rather than just the post.
            for route in routes:
                dx,dy={1:(-1,1),2:(-1,0),3:(-1,-1),4:(1,1),5:(1,0),6:(1,-1)}[route]
                for distance in (1.5,3,4.5):
                    self.assertTrue(covers(8+dx*distance,cy+dy*distance-.01,6.5,'strip'), (prefix,route,distance))

    def test_strip_central_connection_matches_user_reference(self):
        for prefix,routes in COMBINATIONS.items():
            if not (1 in routes or 4 in routes):
                continue
            cy=8 if 3 in routes or 6 in routes else 3.5
            strip=[box(e) for e in model(prefix+'_null')['elements'] if '_strip_' in e.get('name','')]
            for x,y in itertools.product((7.75,8.25),(cy+.25,cy+.75,cy+1.25)):
                self.assertTrue(any(b[0]<=x<b[3] and b[1]<=y<b[4] for b in strip),(prefix,x,y))

    def test_authored_downward_components_and_grounded_plate(self):
        from PIL import Image
        with Image.open(ASSETS/'textures/block/indicator/indicator_3_back.png') as image:
            left = {(x,31-y) for x,y in itertools.product(range(32),repeat=2)
                    if image.convert('RGBA').getpixel((x,y))[3]}
        self.assertTrue({(x,0) for x in range(2,9)} <= left)
        self.assertTrue({(x,y) for x in range(14,18) for y in (0,1)} <= left)
        self.assertIn((13,17),left)  # Broad plate, not a narrow lamp sleeve.
        for prefix,routes in COMBINATIONS.items():
            elements=model(prefix+'_null')['elements']
            back=[box(e) for e in elements if '_back_' in e.get('name','')]
            actual={(x,y) for b in back for x,y in itertools.product(
                range(round(b[0]*2),round(b[3]*2)),range(round(b[1]*2),round(b[4]*2)))}
            for route in (3,6):
                if route in routes:
                    expected=left if route==3 else {(31-x,y) for x,y in left}
                    self.assertTrue(expected <= actual, (prefix,route))
            # Grey foot and dark plate must not share any volume.
            for base in (box(e) for e in elements if '_base_' in e.get('name','')):
                self.assertFalse(any(all(min(base[i+3],b[i+3]) > max(base[i],b[i])
                                         for i in range(3)) for b in back),prefix)

    def test_upper_shoulders_have_uninterrupted_half_unit_steps(self):
        # Inspect the final UNION of rendered cuboids, not the ideal polygons:
        # the previous top/fan overlap introduced a small extra ledge here.
        for prefix,routes in COMBINATIONS.items():
            if not (1 in routes and 4 in routes):
                continue
            cy = 8 if 3 in routes or 6 in routes else 3.5
            back = [box(e) for e in model(prefix+'_null')['elements']
                    if '_back_' in e.get('name','')]
            left, right = [], []
            for step in range(4):
                y = cy+6.25+step*.5
                row = [b for b in back if b[1] <= y < b[4]]
                left.append(min(b[0] for b in row))
                right.append(max(b[3] for b in row))
            self.assertEqual(left, [.5,1,1.5,2], prefix)
            self.assertEqual(right, [15.5,15,14.5,14], prefix)
            for b in back:
                self.assertTrue(all(abs(v*2-round(v*2)) < 1e-9 for v in b), prefix)

    def test_mirrors_and_far_fullbright_rendering(self):
        mapping={1:4,2:5,3:6,4:1,5:2,6:3}
        for prefix,routes in COMBINATIONS.items():
            other='indicator_'+'-'.join(map(str,sorted(mapping[r] for r in routes)))
            a=sorted((e['faces']['south']['texture'],mirror(box(e))) for e in model(prefix+'_null')['elements'])
            b=sorted((e['faces']['south']['texture'],box(e)) for e in model(other+'_null')['elements'])
            # Merging boxes may partition mirrored surfaces differently: compare occupied cells.
            def cells(parts):
                return {(mat,x,y,z) for mat,b in parts for x,y,z in itertools.product(*(range(round(b[i]*4),round(b[i+3]*4)) for i in range(3)))}
            self.assertEqual(cells(a),cells(b),prefix)
        far=read(JAVA/'render/SignalFarRenderer.java')
        self.assertIn('instanceof ColorLightIndicatorBlockEntity',far)
        self.assertIn('instanceof LedIndicatorBlockEntity',far)
        renderer=read(JAVA/'render/ColorLightIndicatorRenderer.java')
        self.assertIn('QueuedRenderLayer.LIGHT',renderer)
        self.assertIn('GraphicsHolder.getDefaultLight()',renderer)

if __name__=='__main__':
    unittest.main(verbosity=2)

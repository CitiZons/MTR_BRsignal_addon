"""Standing/hanging JSON geometry and repeater integration regressions."""
import copy
import itertools
import json
import re
import unittest
from generate_signal_mounts import ROOT, ASSETS, MODELS, read, rotate, generate
JAVA=ROOT/'src/main/java/org/mtrbr'

class SignalMounts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.families=sorted((ASSETS/'blockstates').glob('indicator_*.json'))+[ASSETS/'blockstates/led_indicator.json',ASSETS/'blockstates/banner_repeating_signal.json']

    def test_all_families_have_both_mounts_and_sixteen_angles(self):
        self.assertEqual(len(self.families),17)
        for file in self.families:
            variants=read(file)['variants']
            self.assertEqual(len(variants),224 if file.stem.startswith('indicator_') else 32)
            for key,value in variants.items():
                self.assertIn('hanging=',key)
                self.assertTrue((MODELS/(value['model'].split('/')[-1]+'.json')).is_file())
                other=key.replace('hanging=true','hanging=false')
                self.assertEqual(value['model'].removesuffix('_hanging'),variants[other]['model'])
                self.assertEqual(value.get('y',0),variants[other].get('y',0))

    def test_upright_casing_uv_and_rear_support_meets_mtr_pole(self):
        checked=set()
        for file in self.families:
            for key,value in read(file)['variants'].items():
                name=value['model'].split('/')[-1]
                if name in checked or 'hanging=true' in key:continue
                checked.add(name)
                standing=read(MODELS/(name+'.json'));hanging=read(MODELS/(name+'_hanging.json'))
                offset=max(0,16-max(e['to'][1] for e in standing['elements']))
                expected=[]
                for source in standing['elements']:
                    if '_base_' in source.get('name','') or (not source.get('name') and source['from']==[6.5,0,7] and source['to']==[9.5,1,9]):continue
                    e=copy.deepcopy(source);e['from'][1]+=offset;e['to'][1]+=offset;expected.append(e)
                casing=[e for e in hanging['elements'] if not e.get('name','').startswith('ceiling_mount_')]
                self.assertEqual(casing,expected,name) # Includes face UVs, left/right orientation and all rear authored pieces.
                self.assertEqual(max(e['to'][1] for e in hanging['elements']),16,name)
                self.assertTrue(all(0<=e['from'][1]<=e['to'][1]<=16 for e in hanging['elements']),name)
                # A face at y=16 overlaps the central 6..10 MTR pole, not just a corner.
                top=[e for e in hanging['elements'] if e['to'][1]==16 and min(e['to'][0],10)>max(e['from'][0],6) and min(e['to'][2],10)>max(e['from'][2],6)]
                self.assertTrue(top,name)
                for e in hanging['elements'][len(casing):]:
                    self.assertTrue(any(all(min(e['to'][i],b['to'][i])>=max(e['from'][i],b['from'][i]) for i in range(3)) for b in casing),name)

    def test_generated_shapes_match_every_model_transform(self):
        java=(JAVA/'block/IndicatorMountGeometry.java').read_text(encoding='utf-8')
        tables=dict(re.findall(r'case "([^"]+)" -> S_(\d+)\[index\]',java))
        for file in self.families:
            text=re.search(r'private static VoxelShape\[\] make_'+tables[file.stem]+r'\(\) \{ return new VoxelShape\[\] \{(.*?)\n };',java,re.S).group(1)
            shapes=[tuple(map(float,m.split(','))) for m in re.findall(r'Block.box\(([^)]+)\)',text)]
            self.assertEqual(len(shapes),32)
            seen=set()
            for key,value in read(file)['variants'].items():
                p=dict(part.split('=') for part in key.split(','))
                index=('north','east','south','west').index(p['facing'])*4+(p['is_22_5']=='true')+2*(p['is_45']=='true')+16*(p['hanging']=='true')
                if index in seen:continue
                seen.add(index)
                m=read(MODELS/(value['model'].split('/')[-1]+'.json'))
                pts=[rotate(v,e.get('rotation',{}).get('angle',0)-value.get('y',0)) for e in m['elements'] for v in itertools.product(*zip(e['from'],e['to']))]
                bounds=tuple(min(p[i] for p in pts) for i in range(3))+tuple(max(p[i] for p in pts) for i in range(3))
                for a,b in zip(bounds,shapes[index]):self.assertAlmostEqual(a,b,delta=.000001)

    def test_repeater_model_face_and_orientation_contract(self):
        variants=read(ASSETS/'blockstates/banner_repeating_signal.json')['variants']
        for key,value in variants.items():
            p=dict(part.split('=') for part in key.split(','))
            m=read(MODELS/(value['model'].split('/')[-1]+'.json'))
            actual=m['elements'][0]['rotation']['angle']-value.get('y',0)
            angle={'north':180,'east':270,'south':0,'west':90}[p['facing']]+22.5*(p['is_22_5']=='true')+45*(p['is_45']=='true')
            self.assertAlmostEqual((actual+angle+180)%360,0)
            self.assertTrue(all('/repeating_signal/' not in m['textures'].get(f['texture'].lstrip('#'),'') for e in m['elements'] for f in e['faces'].values()))
        for state in ('on','off','off_limiting'):
            m=read(MODELS/f'banner_repeating_signal_{state}.json')
            layers=[(e,f) for e in m['elements'] for f in e['faces'].values() if '/repeating_signal/' in m['textures'].get(f['texture'].lstrip('#'),'')]
            self.assertEqual(len(layers),1)
            self.assertEqual(layers[0][1]['uv'],[0,0,16,16])
            self.assertTrue((ASSETS/f'textures/block/repeating_signal/{state}.png').is_file())
        self.assertTrue((ROOT/'src/main/resources/data/mtr_brsignal_addon/loot_tables/blocks/banner_repeating_signal.json').is_file())

    def test_bind_ui_far_render_and_mount_height_integrated(self):
        for f in ('network/BindIndicatorPacket.java','network/UnbindIndicatorPacket.java','network/PacketValidation.java','item/DebugToolItem.java','screen/IndicatorInfoScreen.java','screen/SignalDebugScreen.java','render/SignalFarRenderer.java'):
            self.assertIn('RepeatingSignalBlockEntity',(JAVA/f).read_text(encoding='utf-8'),f)
        for renderer in ('LedIndicatorRenderer','ColorLightIndicatorRenderer','RepeatingSignalRenderer'):
            text=(JAVA/f'render/{renderer}.java').read_text(encoding='utf-8')
            self.assertIn('IndicatorMount.offset(state)',text)
            self.assertIn('GraphicsHolder.getDefaultLight()',text)
        text=(JAVA/'render/RepeatingSignalRenderer.java').read_text(encoding='utf-8')
        self.assertIn('ServerAspectCache.get(bound,false)',text)
        self.assertNotIn('authorizationId',text)
        self.assertIn('RepeatingSignalRenderer.clearModelCache()',(JAVA/'client/ClientSetup.java').read_text(encoding='utf-8'))
        self.assertIn('SetIndicatorMountPacket.class',(JAVA/'network/Network.java').read_text(encoding='utf-8'))

    def test_regenerator_preserves_authored_resources_and_is_idempotent(self):
        authored=list((ASSETS/'textures').rglob('*.png'))+[MODELS/f'banner_repeating_signal_{state}.json' for state in ('on','off','off_limiting')]
        before={p:p.read_bytes() for p in authored}
        generated=list(MODELS.glob('*_hanging.json'))+self.families+[JAVA/'block/IndicatorMountGeometry.java']
        old={p:p.read_bytes() for p in generated}
        generate()
        self.assertEqual(before,{p:p.read_bytes() for p in authored})
        self.assertEqual(old,{p:p.read_bytes() for p in generated})

if __name__=='__main__':unittest.main(verbosity=2)

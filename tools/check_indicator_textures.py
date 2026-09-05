"""Validate independent textures and their real JSON face UVs for all new indicators."""
import itertools
import json
import math
import unittest
from PIL import Image
from generate_six_route_indicators import COMBINATIONS as SIX, ASSETS, MODELS
from generate_triple_indicators import COMBINATIONS as TRIPLE

COMBINATIONS = {**TRIPLE, **SIX}


class IndicatorTextures(unittest.TestCase):
    def test_each_model_has_its_own_small_textures(self):
        for prefix,routes in COMBINATIONS.items():
            expected = {'hood':prefix, 'back':prefix+'_back', 'strip':prefix+'_strip'}
            images={}
            for suffix in ('','_back','_strip') + tuple('_route_'+str(r) for r in routes):
                name = prefix+suffix
                image = Image.open(ASSETS/f'textures/block/indicator/{name}.png').convert('RGBA')
                self.assertEqual(image.size,(64,32) if prefix in TRIPLE else (32,32),name)
                self.assertTrue(image.getbbox(),name)
                self.assertTrue(any(pixel[3]==0 for pixel in (image.getpixel((x,y)) for y in range(image.height) for x in range(image.width))),name)
                images[name]=image
            for path in MODELS.glob(prefix+'_*.json'):
                m=json.loads(path.read_text(encoding='utf-8'))
                for material,name in expected.items():
                    self.assertEqual(m['textures'][material],f'mtr_brsignal_addon:block/indicator/{name}')
                route = next((r for r in routes if path.stem==f'{prefix}_{r}'),None)
                if route is not None:
                    self.assertEqual(m['textures']['light'],f'mtr_brsignal_addon:block/indicator/{prefix}_route_{route}')
                for e in m['elements']:
                    for face in e['faces'].values():
                        texture = m['textures'][face['texture'].lstrip('#')]
                        if '/indicator/' not in texture:
                            continue
                        image=images[texture.rsplit('/',1)[1]]
                        u0,v0,u1,v1=face['uv']
                        self.assertTrue(all(0<=v<=16 for v in face['uv']),path)
                        # Check near each edge as well as the centre: no face may
                        # sample transparent pixels from the projected silhouette.
                        for fu,fv in itertools.product((.01,.5,.99),repeat=2):
                            x=min(image.width-1,math.floor((u0+(u1-u0)*fu)/16*image.width))
                            y=min(image.height-1,math.floor((v0+(v1-v0)*fv)/16*image.height))
                            self.assertEqual(image.getpixel((x,y))[3],255,(path,e['name'],face,(x,y)))
                if route is not None:
                    image=images[f'{prefix}_route_{route}']
                    self.assertEqual(sum(p[3]>0 for p in (image.getpixel((x,y)) for y in range(image.height) for x in range(image.width))),20,(prefix,route))
                    self.assertEqual({p for p in (image.getpixel((x,y)) for y in range(image.height) for x in range(image.width)) if p[3]}, {(255,255,255,255)})


if __name__=='__main__':
    unittest.main(verbosity=2)

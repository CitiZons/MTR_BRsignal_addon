"""Build repeater shells and upright ceiling mounts; never overwrite authored source models/textures."""
import copy
import itertools
import json
import math
from pathlib import Path
from generate_indicator_rotations import rotate_model
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'src/main/resources/assets/mtr_brsignal_addon'
MODELS=ASSETS/'models/block'

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def write(path,data):path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def rotate(p,angle):
    x,y,z=p[0]-8,p[1],p[2]-8;c=math.cos(math.radians(angle));s=math.sin(math.radians(angle))
    return (8+x*c+z*s,y,8-x*s+z*c)

def repeater():
    prefix='banner_repeating_signal'
    shell=read(MODELS/f'{prefix}_on.json')
    for e in shell['elements']:
        e['rotation']={'angle':0,'axis':'y','origin':[8,0,8]}
        for face in e['faces'].values():
            texture=shell['textures'].get(face['texture'].lstrip('#'),'')
            if '/repeating_signal/' in texture:
                face['texture']='#dark'
    shell['textures']['dark']='mtr_brsignal_addon:block/black'
    write(MODELS/f'{prefix}.json',shell)
    for angle,suffix in ((-22.5,'22_5'),(-45,'45'),(22.5,'67_5')):
        rotate_model(MODELS/f'{prefix}.json',MODELS/f'{prefix}_{suffix}.json',angle)
    variants={}
    for facing,y in (('north',0),('east',90),('south',180),('west',270)):
        for index,suffix in enumerate(('', '_22_5','_45','_67_5')):
            variants[f'facing={facing},is_22_5={str(bool(index&1)).lower()},is_45={str(bool(index&2)).lower()}']={'model':f'mtr_brsignal_addon:block/{prefix}{suffix}','y':(y+(90 if index==3 else 0))%360}
    write(ASSETS/f'blockstates/{prefix}.json',{'variants':variants})
    write(ASSETS/f'models/item/{prefix}.json',{'parent':f'mtr_brsignal_addon:block/{prefix}_on'})
    loot=ASSETS.parents[1]/'data/mtr_brsignal_addon/loot_tables/blocks'
    loot.mkdir(parents=True,exist_ok=True)
    write(loot/f'{prefix}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':f'mtr_brsignal_addon:{prefix}'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})

def mount_model(model,offset):
    m=copy.deepcopy(model)
    # Discrete floor foot is not part of the suspended casing. Preserve all
    # authored plates/diagonal ribs; in particular don't vertically flip routes.
    m['elements']=[e for e in m['elements'] if '_base_' not in e.get('name','') and not (
        not e.get('name') and e['from']==[6.5,0,7] and e['to']==[9.5,1,9])]
    for e in m['elements']:
        e['from'][1]+=offset;e['to'][1]+=offset
    angle=m['elements'][0].get('rotation',{}).get('angle',0)
    # An upright rear hanger meets the central 6..10 cross-section of the MTR
    # signal pole at y=16, independent of yaw. Subtract existing solids first.
    boxes=[]
    for x,y,z in itertools.product(range(14,18),range(32),range(12,18)):
        X,Y,Z=(x+.5)/2,(y+.5)/2,(z+.5)/2
        if Y<max(offset+3,8):continue
        if any(all(e['from'][i] <= v < e['to'][i] for i,v in enumerate((X,Y,Z))) for e in m['elements']):continue
        boxes.append((x/2,y/2,z/2,(x+1)/2,(y+1)/2,(z+1)/2))
    from generate_triple_indicators import union_boxes
    m['textures']['mount']='mtr_brsignal_addon:block/grey'
    for i,b in enumerate(union_boxes(boxes)):
        m['elements'].append({'name':f'ceiling_mount_{i}','from':list(b[:3]),'to':list(b[3:]),'rotation':{'angle':angle,'axis':'y','origin':[8,0,8]},'faces':{f:{'uv':[0,0,2,2],'texture':'#mount'} for f in ('north','south','east','west','up','down')}})
    return m

def generate():
    repeater()
    files=sorted((ASSETS/'blockstates').glob('indicator_*.json'))+[ASSETS/'blockstates/led_indicator.json',ASSETS/'blockstates/banner_repeating_signal.json']
    java=['package org.mtrbr.block;','','import net.minecraft.world.level.block.Block;','import net.minecraft.world.phys.shapes.VoxelShape;','','/** Generated from standing/hanging model bounds by generate_signal_mounts.py. */','public final class IndicatorMountGeometry {',' private IndicatorMountGeometry() {}']
    offsets={};tables={}
    for file in files:
        states=read(file)['variants'];standing={k.replace(',hanging=false',''):v for k,v in states.items() if 'hanging=true' not in k}
        base=next(v for k,v in standing.items() if k.startswith('facing=north,is_22_5=false,is_45=false'))
        bm=read(MODELS/(base['model'].split('/')[-1]+'.json'));offset=max(0,16-max(e['to'][1] for e in bm['elements']));offsets[file.stem]=offset/16
        result={};shapes={};models={}
        for key,entry in standing.items():
            name=entry['model'].split('/')[-1]
            if name not in models:
                source=read(MODELS/(name+'.json'));hanging=mount_model(source,offset)
                write(MODELS/(name+'_hanging.json'),hanging)
                models[name]=(source,hanging)
            source,hanging=models[name]
            for suspended,m in ((False,source),(True,hanging)):
                value=copy.deepcopy(entry)
                if suspended:value['model']+='_hanging'
                result[key+',hanging='+str(suspended).lower()]=value
                parts=dict(v.split('=') for v in key.split(','));index=('north','east','south','west').index(parts['facing'])*4+(parts['is_22_5']=='true')+2*(parts['is_45']=='true')+16*suspended
                if index in shapes:continue
                points=[rotate(p,e.get('rotation',{}).get('angle',0)-entry.get('y',0)) for e in m['elements'] for p in itertools.product(*zip(e['from'],e['to']))]
                shapes[index]=tuple(min(p[i] for p in points) for i in range(3))+tuple(max(p[i] for p in points) for i in range(3))
        write(file,{'variants':result});tables[file.stem]=shapes
    java+=[' public static double offset(String model) {','  return switch(model) {']+[f'   case "{name}" -> {offset};' for name,offset in offsets.items()]+['   default -> 0;','  };',' }',' public static VoxelShape shape(String model, int index) {','  return switch(model) {']+[f'   case "{name}" -> S_{i}[index];' for i,name in enumerate(tables)]+['   default -> Block.box(0,0,0,16,16,16);','  };',' }']
    for i,(name,shapes) in enumerate(tables.items()):
        java+=[f' private static final VoxelShape[] S_{i} = make_{i}();',f' private static VoxelShape[] make_{i}() {{ return new VoxelShape[] {{']+['  Block.box('+','.join(f'{v:.6f}' for v in shapes[j])+'),' for j in range(32)]+[' }; }']
    java+=['}'];(ROOT/'src/main/java/org/mtrbr/block/IndicatorMountGeometry.java').write_text('\n'.join(java)+'\n',encoding='utf-8')
    print(f'Generated {len(files)} standing/hanging families, 16 orientations each.')

if __name__=='__main__':generate()

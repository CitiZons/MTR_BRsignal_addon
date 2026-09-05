"""Write per-indicator silhouette textures and projected JSON UVs, like authored models."""
import json
import math
from PIL import Image, ImageDraw


def write_indicator_textures(assets, prefix, routes):
    models = assets / 'models/block'
    textures = assets / 'textures/block/indicator'
    shell_path = models / f'{prefix}_null.json'
    shell = json.loads(shell_path.read_text(encoding='utf-8'))
    lit = {r: json.loads((models / f'{prefix}_{r}.json').read_text(encoding='utf-8')) for r in routes}
    all_elements = shell['elements'] + [e for m in lit.values() for e in m['elements'] if '_route_' in e.get('name', '')]
    # Two pixels/model unit, as in the original 32px sheets. The older triple
    # models extend past x=0/16, so use a 64px-wide sheet rather than clip them.
    xmin = min(0, min(e['from'][0] for e in all_elements))
    ymax = max(16, max(e['to'][1] for e in all_elements))
    xmax = max(16, max(e['to'][0] for e in all_elements))
    ymin = min(0, min(e['from'][1] for e in all_elements))
    density = 2
    assert all(abs(e[k][i]*density-round(e[k][i]*density)) < 1e-8
               for e in all_elements for k in ('from','to') for i in (0,1))
    width = 2**math.ceil(math.log2((xmax-xmin)*density))
    height = 2**math.ceil(math.log2((ymax-ymin)*density))

    def material(e):
        return next(iter(e['faces'].values()))['texture'].lstrip('#')

    def emit(suffix, elements, colour):
        image = Image.new('RGBA', (width,height))
        draw = ImageDraw.Draw(image)
        for e in elements:
            a,b = e['from'],e['to']
            x0,x1 = round((a[0]-xmin)*density),round((b[0]-xmin)*density)
            y0,y1 = round((ymax-b[1])*density),round((ymax-a[1])*density)
            draw.rectangle((x0,y0,x1-1,y1-1), fill=colour)
        path = textures / f'{prefix}{suffix}.png'
        # Preserve the user's reference PNG byte-for-byte if its pixels already
        # match the updated geometry (including their central connection edit).
        same = False
        if path.exists():
            with Image.open(path) as existing:
                same = existing.size == image.size and existing.convert('RGBA').tobytes() == image.tobytes()
        if not same:
            image.save(path)
        return f'mtr_brsignal_addon:block/indicator/{prefix}{suffix}'

    references = {}
    for group,suffix,colour in (('hood','',(17,17,17,255)),('back','_back',(34,34,34,255)),('strip','_strip',(17,17,17,255))):
        references[group] = emit(suffix,[e for e in shell['elements'] if material(e)==group],colour)

    def remap(model):
        model['textures'].update(references)
        for e in model['elements']:
            if material(e)=='base':
                continue
            a,b=e['from'],e['to']
            u0,u1=(a[0]-xmin)*density/width*16,(b[0]-xmin)*density/width*16
            v0,v1=(ymax-b[1])*density/height*16,(ymax-a[1])*density/height*16
            # Edge faces sample INSIDE their occupied silhouette, not its transparent border.
            du,dv=min((u1-u0)/4,2/width),min((v1-v0)/4,2/height)
            uv={'south':[u0,v0,u1,v1], 'north':[u1,v0,u0,v1],
                'west':[u0+du,v0,u0+2*du,v1], 'east':[u1-2*du,v0,u1-du,v1],
                'up':[u0,v0+dv,u1,v0+2*dv], 'down':[u0,v1-2*dv,u1,v1-dv]}
            for face in e['faces']:
                e['faces'][face]['uv'] = uv[face]

    def save(path, model):
        path.write_text(json.dumps(model,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

    remap(shell)
    save(shell_path,shell)
    for route,model in lit.items():
        lamps = [e for e in model['elements'] if '_route_' in e.get('name','')]
        model['textures']['light'] = emit(f'_route_{route}',lamps,(255,255,255,255))
        remap(model)
        save(models/f'{prefix}_{route}.json',model)

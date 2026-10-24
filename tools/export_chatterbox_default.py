"""Export the official built-in Turbo voice without executing pickle globals.
Only NumPy storage reconstruction and OrderedDict are allowed. No PyTorch runtime.
"""
import argparse, collections, io, json, pickle, zipfile, urllib.request, struct, hashlib
from pathlib import Path
import numpy as np

parser=argparse.ArgumentParser()
parser.add_argument('--cache-dir',type=Path,default=Path.home()/'.cache/alpha-chatterbox-default')
parser.add_argument('--output',type=Path,default=Path(__file__).resolve().parent.parent/'app/src/main/assets/chatterbox-default.bin')
options=parser.parse_args()
ROOT = options.cache_dir
ROOT.mkdir(parents=True,exist_ok=True)
conditions=ROOT/'conds.pt'
if not conditions.exists():
    urllib.request.urlretrieve('https://huggingface.co/ResembleAI/chatterbox-turbo/resolve/dfcb1eb/conds.pt',conditions)
assert hashlib.sha256(conditions.read_bytes()).hexdigest()=='b1852099306fd6a7814eb9d0bd10186caba7249596cc23868f78a0eefbfa5033'
class SafeWeights(pickle.Unpickler):
    def find_class(self, module, name):
        if (module, name) == ('collections', 'OrderedDict'):
            return collections.OrderedDict
        if module == 'torch' and name in ('FloatStorage', 'LongStorage'):
            return np.dtype('<f4' if name == 'FloatStorage' else '<i8')
        if (module, name) == ('torch._utils', '_rebuild_tensor_v2'):
            return self.rebuild
        raise ValueError(f'Forbidden pickle global: {module}.{name}')
    def persistent_load(self, ident):
        kind, dtype, key, device, count = ident
        assert kind == 'storage' and str(key).isdigit() and count < 1000000
        raw = self.archive.read(f'conds/data/{key}')
        assert len(raw) == count * dtype.itemsize
        return np.frombuffer(raw, dtype=dtype)
    @staticmethod
    def rebuild(storage, offset, size, stride, requires_grad, hooks):
        assert isinstance(requires_grad, bool) and not hooks
        return np.ndarray(size, dtype=storage.dtype, buffer=storage,
                          offset=offset * storage.itemsize,
                          strides=tuple(x * storage.itemsize for x in stride)).copy()

with zipfile.ZipFile(ROOT / 'conds.pt') as archive:
    loader = SafeWeights(io.BytesIO(archive.read('conds/data.pkl')))
    loader.archive = archive
    data = loader.load()
print({group: {k: list(v.shape) if isinstance(v, np.ndarray) else v
               for k, v in values.items()} for group, values in data.items()})
np.savez(ROOT / 'builtin.npz', **{f'{g}.{k}': v for g, values in data.items()
                               for k, v in values.items() if isinstance(v, np.ndarray)})

# Read only the projection and embedding weights needed to export the built-in
# voice. Native inference does not require Python, PyTorch or a PC.
url='https://huggingface.co/ResembleAI/chatterbox-turbo/resolve/dfcb1eb/t3_turbo_v1.safetensors'
def fetch(start, length):
    cached=ROOT/f'weight-{start}-{length}.bin'
    if cached.exists():
        raw=cached.read_bytes()
    else:
        req=urllib.request.Request(url,headers={'Range':f'bytes={start}-{start+length-1}'})
        with urllib.request.urlopen(req,timeout=60) as response:
            if response.status != 206: raise ValueError('Weight server did not honor the byte range')
            raw=response.read(length+1)
        assert len(raw)==length
        cached.write_bytes(raw)
    assert len(raw)==length
    return raw
header_len=struct.unpack('<Q',fetch(0,8))[0]
assert header_len<1000000
header=json.loads(fetch(8,header_len))
def weight(name):
    spec=header[name];assert spec['dtype']=='F32'
    start,end=spec['data_offsets']
    return np.frombuffer(fetch(8+header_len+start,end-start),dtype='<f4').reshape(spec['shape'])
spkr=data['t3']['speaker_emb'] @ weight('cond_enc.spkr_enc.weight').T + weight('cond_enc.spkr_enc.bias')
speech_emb=weight('speech_emb.weight')[data['t3']['cond_prompt_speech_tokens']]
values=[np.concatenate([spkr[:,None,:],speech_emb],axis=1),data['gen']['prompt_token'],
        data['gen']['embedding'],data['gen']['prompt_feat']]
out=bytearray(b'ALPHACB1')
for i,value in enumerate(values):
    value=np.asarray(value,dtype='<i8' if i==1 else '<f4')
    assert value.shape[0]==1 and np.isfinite(value).all()
    out+=struct.pack('<i',value.ndim)+struct.pack('<'+'q'*value.ndim,*value.shape)
    out+=value.tobytes(order='C')
target=options.output
target.parent.mkdir(parents=True,exist_ok=True)
target.write_bytes(out)
print('Exported default:', [(list(v.shape), str(v.dtype)) for v in values], len(out), hashlib.sha256(out).hexdigest())

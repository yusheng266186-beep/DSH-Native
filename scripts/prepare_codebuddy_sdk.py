#!/usr/bin/env python3
"""Bundle byte-identical official files needed by the SDK's headless transport."""
import argparse,hashlib,io,json,pathlib,shutil,subprocess,tarfile,tempfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
def runtime_file(relative):
 parts=relative.parts
 if parts[:4]!=('node_modules','@tencent-ai','agent-sdk','cli'):return True
 cli=parts[4:]
 if not cli:return False
 # DSH runs the official headless entry with built-in tools disabled. Desktop
 # views, serving bundles and bundled external tools are not used by this route.
 if cli[0]=='dist':return cli==('dist','codebuddy-headless.js')
 if cli[0]=='bin':return len(cli)==2 and not cli[1].endswith(('.test.cjs','.d.cts'))
 if cli[0]=='vendor':return len(cli)>1 and cli[1]=='assets'
 if cli[0] in ('dist-server','lib','types'):return False
 return cli[0]=='builtin' or len(cli)==1 and (cli[0]=='package.json' or cli[0].startswith(('product.','LICENSE','NOTICE')))
def main():
 parser=argparse.ArgumentParser();parser.add_argument('output');parser.add_argument('--installed');args=parser.parse_args()
 output=pathlib.Path(args.output).resolve();output.mkdir(parents=True,exist_ok=True)
 for name in ['codebuddy-cli.cjs','unpack.js']:
  source=ROOT/'payload'/name
  if source.resolve()!=(output/name).resolve():shutil.copyfile(source,output/name)
 with tempfile.TemporaryDirectory(prefix='dsh-codebuddy-build-') as temp:
  base=pathlib.Path(args.installed).resolve() if args.installed else pathlib.Path(temp)
  if not args.installed:
   for name in ['package.json','package-lock.json']:shutil.copyfile(ROOT/'runtime/codebuddy'/name,base/name)
   subprocess.run(['npm','ci','--ignore-scripts','--omit=dev','--omit=optional','--no-audit','--no-fund'],cwd=base,check=True)
  sdk=base/'node_modules/@tencent-ai/agent-sdk';version=json.loads((sdk/'package.json').read_text())['version']
  if version!='0.3.291':raise SystemExit('Unexpected CodeBuddy SDK version')
  tarpath=pathlib.Path(temp)/'sdk.tar'
  with tarfile.open(tarpath,'w',format=tarfile.GNU_FORMAT) as archive:
   for item in sorted((base/'node_modules').rglob('*')):
    relative=item.relative_to(base)
    if item.is_symlink() or '.bin' in relative.parts:continue
    if not item.is_file() or not runtime_file(relative):continue
    content=item.read_bytes();info=tarfile.TarInfo(str(relative));info.size=len(content);info.mode=0o644;info.mtime=0;archive.addfile(info,io.BytesIO(content))
  asset=output/'codebuddy-sdk.tar.zst'
  subprocess.run(['node','-e',"const fs=require('fs'),z=require('zlib');fs.writeFileSync(process.argv[2],z.zstdCompressSync(fs.readFileSync(process.argv[1]),{params:{[z.constants.ZSTD_c_compressionLevel]:12}}));",str(tarpath),str(asset)],check=True)
  manifest={'sdk':version,'cli':json.loads((sdk/'cli/package.json').read_text())['version'],'layout':'headless-v1','bytes':asset.stat().st_size,'sha256':hashlib.sha256(asset.read_bytes()).hexdigest(),'unpacked_bytes':tarpath.stat().st_size}
  (output/'codebuddy-sdk-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
  print('Official CodeBuddy SDK bundled:',json.dumps(manifest))
if __name__=='__main__':main()

// Retire the removed provider only in DSH-owned active configuration. Never visit histories or projects.
const fs=require('node:fs/promises'),path=require('node:path'),crypto=require('node:crypto');
const {createRequire}=require('node:module');
async function migrate(root,runtime){
 root=await fs.realpath(root);runtime=await fs.realpath(runtime);
 const yaml=createRequire(path.join(runtime,'package.json'))('yaml'),home=path.join(root,'.dsh');
 const canonical=await fs.realpath(home).catch(error=>{if(error.code==='ENOENT')return home;throw error;});
 if(canonical!==home)throw new Error('linked configuration home');
 const preset=yaml.parse(await fs.readFile(path.join(root,'settings-preset.yaml'),'utf8'));
 const buddy=preset['llm-pi-ai'].providers.codebuddy;
 const updates=[];
 const clean=map=>{
  if(!yaml.isMap(map))return;
  const providers=map.get('providers',true);
  if(yaml.isMap(providers))for(const pair of [...providers.items]){
   const id=String(pair.key?.value??pair.key),item=pair.value;
   if(id==='codebuddy'&&yaml.isMap(item)&&item.get('baseURL')==='https://www.codebuddy.cn/v2'&&['CodeBuddy',undefined].includes(item.get('displayName')))item.set('displayName','CodeBuddy CLI');
   if(id==='commandcode'||(yaml.isMap(item)&&(item.get('apiKeyEnv')==='COMMANDCODE_API_KEY'||String(item.get('baseURL')||'').includes('api.commandcode.ai'))))providers.delete(id);
  }
  if(map.get('provider')==='commandcode'){
   map.set('provider','codebuddy');map.set('model','deepseek-v4.1-flash');map.set('reasoningEffort','max');
  }
  const refs=map.get('refs',true);if(yaml.isMap(refs))refs.delete('COMMANDCODE_API_KEY');
  const records=map.get('records',true);if(yaml.isMap(records))records.delete('commandcode');
  for(const pair of map.items){if(yaml.isMap(pair.value))clean(pair.value);else if(yaml.isSeq(pair.value))for(const item of pair.value.items)clean(item);}
 };
 for(const name of ['settings.yaml','settings.yaml.imported','.credentials.yaml','profiles/web/cordis.patch.yml','.native-project-models']){
  const file=path.join(home,name);let stat;
  try{stat=await fs.lstat(file);}catch(error){if(error.code==='ENOENT')continue;throw error;}
  if(!stat.isFile()||stat.isSymbolicLink()||stat.size>2097152||await fs.realpath(file)!==file)throw new Error('unsafe configuration target');
  const source=await fs.readFile(file,'utf8');let next;
  if(name==='.native-project-models'){
   if(source&&!source.startsWith('DSHPM1\n'))throw new Error('invalid project model configuration');
   next=source.split('\n').map(line=>{const parts=line.split('\t');const start=parts[0]==='G'?1:parts[0]==='P'?2:-1;if(start>0&&parts[start]===Buffer.from('commandcode').toString('hex')){parts[start]=Buffer.from('codebuddy').toString('hex');parts[start+1]=Buffer.from('deepseek-v4.1-flash').toString('hex');parts[start+2]=Buffer.from('max').toString('hex');}return parts.join('\t');}).join('\n');
  }else{
   const doc=yaml.parseDocument(source,{uniqueKeys:true});if(doc.errors.length)throw new Error('invalid provider configuration');
   if(yaml.isMap(doc.contents))clean(doc.contents);
   if(yaml.isSeq(doc.contents)){
    for(const item of doc.contents.items)if(yaml.isMap(item)){
     const config=item.get('config',true);clean(config);
     if(item.get('id')==='agent-default-model'&&yaml.isMap(config))clean(config);
    }
    let adapter=doc.contents.items.find(item=>yaml.isMap(item)&&item.get('id')==='llm-pi-ai');
    if(!adapter){adapter=doc.createNode({id:'llm-pi-ai',config:{providers:{}}});doc.contents.add(adapter);}
    if(!adapter.has('config'))adapter.set('config',doc.createNode({}));
    const config=adapter.get('config',true);if(!config.has('providers'))config.set('providers',doc.createNode({}));
    const providers=config.get('providers',true);if(!providers.has('codebuddy'))providers.set('codebuddy',doc.createNode(buddy));
   }
   next=doc.toString();
  }
  if(next!==source)updates.push({file,next,source});
 }
 for(const item of updates){
  const temp=item.file+'.'+crypto.randomUUID()+'.next';
  try{const handle=await fs.open(temp,'wx',0o600);try{await handle.writeFile(item.next,'utf8');await handle.sync();}finally{await handle.close();}await fs.rename(temp,item.file);}
  finally{await fs.unlink(temp).catch(error=>{if(error.code!=='ENOENT')throw error;});}
 }
 return {updated:updates.length};
}
module.exports={migrate};
if(require.main===module)migrate(path.resolve(process.argv[2]),path.resolve(process.argv[3])).then(value=>console.log('Provider configuration migration: '+value.updated+' files updated')).catch(error=>{console.error('Provider configuration migration failed: '+(error.code||error.name));process.exitCode=1;});

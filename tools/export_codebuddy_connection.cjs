#!/usr/bin/env node
// Creates private encrypted import material. Never print or embed a provider credential.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto');
function encrypt(apiKey,password){
 if(typeof apiKey!=='string'||apiKey.length<16||apiKey.length>4096||/[\x00-\x1f\x7f]/.test(apiKey))throw new Error('Invalid API credential');
 const salt=crypto.randomBytes(16),iv=crypto.randomBytes(12),format='DSH-CODEBUDDY-1',iterations=200000;
 const key=crypto.pbkdf2Sync(password,salt,iterations,32,'sha256'),cipher=crypto.createCipheriv('aes-256-gcm',key,iv);
 cipher.setAAD(Buffer.from(format));
 const body=Buffer.from(JSON.stringify({provider:'codebuddy',baseURL:'https://www.codebuddy.cn/v2',model:'deepseek-v4.1-flash',apiKey}));
 const data=Buffer.concat([cipher.update(body),cipher.final(),cipher.getAuthTag()]);body.fill(0);key.fill(0);
 return {format,iterations,salt:salt.toString('base64'),iv:iv.toString('base64'),data:data.toString('base64')};
}
module.exports={encrypt};
if(require.main===module){
 try{
  const out=path.resolve(process.argv[2]||'private-codebuddy-connection'),source=path.resolve(process.argv[3]||path.join(os.homedir(),'.codebuddy/settings.json'));
  const settings=JSON.parse(fs.readFileSync(source,'utf8')),password=crypto.randomBytes(15).toString('base64url');
  const bundle=encrypt(settings.env?.CODEBUDDY_API_KEY,password);
  fs.mkdirSync(out,{recursive:true,mode:0o700});fs.chmodSync(out,0o700);
  const file=path.join(out,'CodeBuddy.dshconnection'),secret=path.join(out,'连接口令.txt');
  fs.writeFileSync(file,JSON.stringify(bundle,null,2)+'\n',{mode:0o600,flag:'wx'});
  fs.writeFileSync(secret,password+'\n',{mode:0o600,flag:'wx'});
  console.log('Private encrypted connection created: '+file+'\nPassphrase file: '+secret);
 }catch(error){console.error('Connection export failed: '+(error.code||error.name));process.exitCode=1;}
}

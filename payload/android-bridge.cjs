// DSH native CLI/client. Credentials remain in a private descriptor, never in arguments.
const fs = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');
const METHODS = ['capabilities','clipboard.get','clipboard.set','notification.post','share.text','share.file',
    'url.open','file.open','app.open','keepalive.status','screenshot'];
async function invoke(command, options = {}) {
    if (!command || !METHODS.includes(command.method)) throw new Error('INVALID_REQUEST: unknown native method');
    let config;
    try {
        const file = options.descriptor || path.join(__dirname,'.native-bridge.json');
        const stat = await fs.lstat(file);
        if (!stat.isFile() || stat.size > 2048 || (stat.mode & 0o077) !== 0) throw new Error('descriptor permissions');
        config = JSON.parse(await fs.readFile(file,'utf8'));
        if (config.version !== 1 || !Number.isInteger(config.port) || config.port < 1 || config.port > 65535
            || !/^[a-f0-9]{64}$/.test(config.token)) throw new Error('descriptor format');
    } catch (error) { throw new Error('BRIDGE_NOT_READY: start DSH and retry'); }
    const body=Buffer.from(JSON.stringify(command));
    if (body.length>131072) throw new Error('INVALID_REQUEST: request too large');
    return new Promise((resolve,reject)=>{
        let timedOut=false;
        const failure=fallback=>new Error(options.signal?.aborted?'CANCELLED: native request cancelled':timedOut?'BRIDGE_TIMEOUT: native response deadline exceeded; check DSH before retrying':fallback);
        const request=http.request({host:'127.0.0.1',port:config.port,path:'/native',method:'POST',
            headers:{'Authorization':'Bearer '+config.token,'Content-Type':'application/json','Content-Length':body.length},
            signal:options.signal},response=>{
            let size=0;const buffers=[];
            response.on('data',bytes=>{size+=bytes.length;if(size>262144){request.destroy(new Error('limit'));return;}buffers.push(bytes);});
            response.on('error',()=>reject(failure('BRIDGE_DISCONNECTED: native response interrupted')));
            response.on('end',()=>{
                try {
                    const value=JSON.parse(Buffer.concat(buffers).toString('utf8'));
                    if (!value || typeof value.ok!=='boolean') throw new Error('invalid response');
                    if (value.ok) {
                        if (response.statusCode!==200 || !value.result || typeof value.result!=='object' || Array.isArray(value.result)) throw new Error('invalid result');
                    } else if (!/^[A-Z][A-Z0-9_]{0,63}$/.test(value.error) || typeof value.message!=='string' || value.message.length>512) throw new Error('invalid error');
                    resolve(value);
                }catch(error){reject(new Error('BRIDGE_PROTOCOL: invalid native response'));}
            });
        });
        const timeout=setTimeout(()=>{timedOut=true;request.destroy(new Error('deadline'));},command.method==='screenshot'?95000:12000);
        request.on('close',()=>clearTimeout(timeout));
        request.on('error',()=>reject(failure('BRIDGE_UNAVAILABLE: return to DSH and retry')));
        request.end(body);
    });
}
module.exports={invoke,METHODS};
if(require.main===module)(async()=>{
    const [method,...args]=process.argv.slice(2);
    if(!method||method==='--help'){
        console.log('dsh-android METHOD [JSON]\nMethods: '+METHODS.join(', ')+'\nUse - as JSON to read stdin.\nExample: dsh-android clipboard.set \'{"text":"hello"}\'');return;
    }
    let raw=args.join(' ')||'{}';
    if(raw==='-'){
        const chunks=[];let size=0;for await(const bytes of process.stdin){size+=bytes.length;if(size>131072)throw new Error('INVALID_REQUEST: stdin too large');chunks.push(bytes);}raw=Buffer.concat(chunks).toString('utf8');
    }
    let fields;try{fields=JSON.parse(raw);}catch(error){throw new Error('INVALID_REQUEST: supply a JSON object');}
    if(!fields||typeof fields!=='object'||Array.isArray(fields)||Object.hasOwn(fields,'method'))throw new Error('INVALID_REQUEST: supply fields without method');
    const value=await invoke({method,...fields});console.log(JSON.stringify(value));if(!value.ok)process.exitCode=1;
})().catch(error=>{console.error(JSON.stringify({ok:false,error:error.message.split(':',1)[0],message:error.message.split(': ').slice(1).join(': ')}));process.exitCode=1;});

import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {spawnSync} from 'node:child_process';
import vm from 'node:vm';
import {EventEmitter} from 'node:events';
const require = createRequire(import.meta.url);
const {checkFileTool} = require('../../payload/runtime-checks.js');
const runtime = path.resolve(process.argv[2]);
const {LocalFileSystem} = await import(pathToFileURL(path.join(runtime,'node_modules/@deepseek-ai/dsh-fs-local/lib/index.js')));
const scratch = await fs.mkdtemp(path.join(os.tmpdir(),'dsh-environment-'));
let checks=0;
const check=(value,message)=>{assert(value,message);checks++;};
const original=LocalFileSystem.prototype.writeText;
try {
    await fs.writeFile(path.join(scratch,'user-file.txt'),'USER-DATA');
    const retained=async()=>check((await fs.readdir(scratch)).join(',')==='user-file.txt','probe removes only its own unique directory');
    for(const code of ['EACCES','EPERM','EXDEV','ENOSYS','EOPNOTSUPP','ENOTSUP','EMLINK']) {
        LocalFileSystem.prototype.writeText=function(...args) {
            this.internals={linkFile:async()=>{throw Object.assign(new Error('synthetic Android hardlink refusal'),{code});}};
            return original.apply(this,args);
        };
        check((await checkFileTool(runtime,scratch)).includes('同名保护'),code+' actual public backend probe failed');
        await retained();
    }
    LocalFileSystem.prototype.writeText=function(...args) {
        this.internals={linkFile:async()=>{throw Object.assign(new Error('PRIVATE-CONTENT'),{code:'ENOSPC'});}};
        return original.apply(this,args);
    };
    await assert.rejects(checkFileTool(runtime,scratch),error=>{
        check(error.message==='file-tool create (FS_IO_ERROR)','disk failure reports stage/code without path/content');return true;
    });await retained();
    LocalFileSystem.prototype.writeText=function(...args) {
        this.internals={linkFile:async()=>{throw Object.assign(new Error('refused'),{code:'EACCES'});},
            copyFile:async()=>{throw Object.assign(new Error('PRIVATE-CONTENT'),{code:'EACCES'});}};
        return original.apply(this,args);
    };
    await assert.rejects(checkFileTool(runtime,scratch),/file-tool create \(FS_IO_ERROR\)/);checks++;await retained();
    LocalFileSystem.prototype.writeText=function(target,content,intent,...rest) {
        // Reproduce a backend that silently ignores create-only protection.
        return original.call(this,target,content,intent?.kind==='createIfAbsent'?undefined:intent,...rest);
    };
    await assert.rejects(checkFileTool(runtime,scratch),/file-tool collision-guard/);checks++;await retained();
    LocalFileSystem.prototype.writeText=original;
    await assert.rejects(checkFileTool(path.join(scratch,'missing-runtime'),scratch),/file-tool import \(ERR_MODULE_NOT_FOUND\)/);checks++;
    await assert.rejects(checkFileTool(runtime,path.join(scratch,'missing-workspace')),/file-tool temporary-directory \(ENOENT\)/);checks++;
    await retained();
    const result=spawnSync(process.execPath,[new URL('../../payload/runtime-checks.js',import.meta.url).pathname,runtime,scratch],{encoding:'utf8',timeout:15000});
    check(result.status===0&&result.stdout.startsWith('FILE_TOOL|PASS|'),'shipped diagnostic CLI uses actual backend');await retained();
    check(await fs.readFile(path.join(scratch,'user-file.txt'),'utf8')==='USER-DATA','user content unchanged');
    const fixture=await fs.mkdtemp(path.join(os.tmpdir(),'dsh-preflight-test-'));
    try {
        await fs.symlink(runtime,path.join(fixture,'dsh'),'dir');await fs.mkdir(path.join(fixture,'tmp'));
        await fs.writeFile(path.join(fixture,'.preflight'),'USER-SENTINEL');
        const source=await fs.readFile(new URL('../../payload/preflight.js',import.meta.url),'utf8');
        async function preflight(workspace,legacy=false) {
            const logs=[],timers=[];let exit;
            class Worker extends EventEmitter {constructor(){super();queueMicrotask(()=>this.emit('message',4));}terminate(){}}
            const nativeFs=require('node:fs');
            const fakeRequire=name=>{
                if(name==='fs'&&legacy)return {...nativeFs,readFileSync:(file,...rest)=>file===path.join(fixture,'dsh/package.json')?'{"version":"0.2.0-rc.2"}':nativeFs.readFileSync(file,...rest)};
                if(name==='child_process')return {execFileSync:(exe,args)=>args.includes('echo BASH_OK')?'BASH_OK':args.some(value=>value.includes('import importlib,json'))?'{"requests":"available","docx":"missing or dependency missing"}':'synthetic-tool'};
                if(name==='node-pty')return {spawn(){}};
                if(name==='worker_threads')return {Worker};
                if(name==='dns')return {lookup:(host,callback)=>callback(null,'127.0.0.1')};
                if(name==='./runtime-checks.js')return {checkFileTool};
                if(['koffi','sharp','@deepseek-ai/node-addon-system','node-addon-require-builtin'].includes(name))throw new Error('optional fixture');
                return require(name);
            };
            try {
                await vm.runInNewContext(source,{require:fakeRequire,Buffer,__dirname:fixture,process:{argv:['node','preflight.js',fixture,workspace],env:{TMPDIR:path.join(fixture,'tmp')},exit:code=>{exit=code;}},
                    console:{log:line=>logs.push(line)},setTimeout:(fn,delay)=>{const timer=setTimeout(fn,delay);timers.push(timer);return timer;}});
            }finally{timers.forEach(clearTimeout);}
            check(exit===0,'production preflight emits summary without crashing');return logs;
        }
        const lines=await preflight(scratch);
        check(lines.some(line=>line.startsWith('PREFLIGHT|PASS|私有目录文件工具|')),'production preflight checks private runtime');
        check(lines.some(line=>line.startsWith('PREFLIGHT|PASS|工作区文件工具|')),'production preflight checks selected workspace');
        check(lines.some(line=>line.includes('PREFLIGHT|INFO|可选 Python 库|')&&line.includes('docx: missing')),'missing optional library is capability information');
        check(lines.at(-1)==='PREFLIGHT_END|0','optional packages do not block successful mandatory checks');
        check(await fs.readFile(path.join(fixture,'.preflight'),'utf8')==='USER-SENTINEL','raw IO check does not overwrite fixed-name file');
        const missing=await preflight(path.join(scratch,'absent'));
        check(missing.some(line=>line.startsWith('PREFLIGHT|FAIL|工作区文件工具|file-tool temporary-directory')),'workspace failure remains a visible failed check');
        check(missing.at(-1)==='PREFLIGHT_END|1','workspace failure prevents a successful preflight cache');
        const old=await preflight(scratch,true);
        check(old.some(line=>line.startsWith('PREFLIGHT|INFO|文件工具|旧内核')),'rollback runtime has explicit unsupported-check information');
        check(!old.some(line=>line.includes('工作区文件工具'))&&old.at(-1)==='PREFLIGHT_END|0','legacy runtime does not fail incompatible new probe');
        check((await fs.readdir(path.join(fixture,'tmp'))).length===0,'production preflight leaves no test directories');
    }finally{await fs.rm(fixture,{recursive:true,force:true});}
    console.log('Actual environment checks: '+checks+' checks; public file backend, denied links, guards, failures, cleanup and diagnostic CLI passed.');
} finally {
    LocalFileSystem.prototype.writeText=original;
    await fs.rm(scratch,{recursive:true,force:true});
}

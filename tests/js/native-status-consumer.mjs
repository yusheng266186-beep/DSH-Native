import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import readline from 'node:readline';
export async function connectNativeStatus(launch,classes){
 const child=spawn('java',['-Dfile.encoding=UTF-8','-cp',classes,'dev.dsh.nativeapp.CoreRpcConsumer'],{stdio:['pipe','pipe','pipe']});
 const pending=[];readline.createInterface({input:child.stdout}).on('line',line=>pending.shift()?.(JSON.parse(line)));
 const next=()=>new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('native state timed out')),20000);pending.push(value=>{clearTimeout(timer);resolve(value);});});
 const address=new URL(launch),ready=next();
 child.stdin.write(JSON.stringify({port:Number(address.port),token:address.searchParams.get('token'),expectedSettings:''})+'\n');
 assert((await ready).ready);
 return {async state(){const result=next();child.stdin.write(JSON.stringify({method:'session/list',args:'{"_request":{}}',taskState:true})+'\n');const value=await result;assert(Number.isInteger(value.state),'native task read failed');return value.state;},close(){child.stdin.end();}};
}

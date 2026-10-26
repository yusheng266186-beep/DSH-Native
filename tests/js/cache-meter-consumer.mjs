// Actual core usage projection, context pressure and WebUI percentage formatter.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const runtime=path.resolve(process.argv[2]);
const moduleFile=path.join(runtime,'node_modules/@deepseek-ai/dsh-token-meter/lib/index.js');
const probe=path.join(path.dirname(moduleFile),'native-cache-consumer.mjs');
let checks=0;const check=(value,label)=>{assert(value,label);checks++;};
try{
 await fs.writeFile(probe,await fs.readFile(moduleFile,'utf8')+'\nexport {tokenUsageProjectionDefinition,pressureFrom};\n');
 const {tokenUsageProjectionDefinition:meter,pressureFrom}=await import(pathToFileURL(probe));
 const ui=await fs.readFile(path.join(runtime,'node_modules/@deepseek-ai/dsh-client-ui-chat/lib/client.js'),'utf8');
 const start=ui.indexOf('function roundedPercentUnits('),end=ui.indexOf('//#endregion',start);
 assert(start>=0&&end>start);const percent=Function(ui.slice(start,end)+'\nreturn formatCacheHitPercent;')();
 let state=meter.init();
 // Contract reproduced by the real pinned SDK / production-adapter test.
 const usage={inputTokens:100,outputTokens:2,cacheReadTokens:900,totalTokens:1002};
 for(let turn=1;turn<=50;turn++){
  const event={type:'assistant/message',data:{turn,step:1,usage}};
  state=meter.apply(state,event);const again=meter.apply(state,event);
  check(again===state,'replayed usage settlement is not counted twice');
 }
 const totals=meter.wire.view(state),prompt=totals.uncachedInputTokens+totals.cacheReadTokens+totals.cacheWriteTokens;
 check(totals.uncachedInputTokens===5000&&totals.cacheReadTokens===45000,'fifty rounds retain exact disjoint totals');
 check(percent(totals.cacheReadTokens,prompt,1)==='90','actual DSH WebUI displays 90 percent after fifty rounds');
 check(pressureFrom(usage)===1000,'context occupancy is the provider input, not input plus duplicated cache');
 check(percent(1000,1000,1)==='100'&&percent(999,1000,1)==='99.9','genuine full and partial hits keep the upstream honest formatting');
 console.log('Core cache meter: '+checks+' actual projection, pressure and UI-format checks passed.');
}finally{await fs.rm(probe,{force:true});}

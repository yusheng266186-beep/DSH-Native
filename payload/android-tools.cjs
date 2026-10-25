// App-owned Cordis adapter, loaded by the launch patch without changing user profiles.
const path=require('node:path');
const {pathToFileURL}=require('node:url');
const bridge=require('./android-bridge.cjs');
module.exports={name:'android-native',inject:['tools','systemPrompt'],async apply(ctx){
    const {defineTool}=await import(pathToFileURL(path.join(__dirname,'dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js')));
    ctx.tools.register(defineTool({name:'android',description:'Use DSH Android native capabilities: clipboard text, notifications, share sheets, open links/files/apps, keepalive status and a user-authorized screenshot. Clipboard/UI actions require DSH foreground. Sharing opens a chooser; it does not send automatically. Screenshot requires a new Android approval each time. Use only for the user’s requested task.',
        parameters:{method:{type:'string',required:true,enum:bridge.METHODS},text:{type:'string'},title:{type:'string'},path:{type:'string'},url:{type:'string'},package:{type:'string'}},
        output:{schema:{type:'json'},render:(_args,value)=>[{type:'text',text:JSON.stringify(value)}]},
        async execute(args,exec){const value=await bridge.invoke(args,{signal:exec.signal});if(!value.ok)throw new Error(value.error+': '+value.message);return value.result;}
    }));
    ctx.systemPrompt.section({name:'android:native',order:10150,text:'This Android deployment provides the android tool and dsh-android CLI. Inspect capabilities before use. Clipboard reads return private user text only for the current request; do not poll or log it. UI operations require the user to return to DSH. A share chooser is not proof of delivery. Screenshots require a per-request Android system prompt and return a private PNG path for read_image; do not claim to control or observe other apps beyond the approved image. Runtime details are in '+path.join(__dirname,'runtime-environment.md')+'.'});
}};

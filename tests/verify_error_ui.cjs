const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/../src/main/resources/static/js/app.js','utf8');
const code=source.slice(source.indexOf('function requestFailure('),source.indexOf('async function recoverExpiredSession'));
const checks=[];const check=(ok,label)=>{assert.ok(ok,label);checks.push(label);};
function setup(fetch,user=null){const c={fetch,FormData,AbortController,setTimeout,clearTimeout,state:{csrf:'qa-csrf',user},recovered:0};c.recoverExpiredSession=async()=>{c.recovered++;};vm.createContext(c);vm.runInContext(code,c);return c;}
const id='77777777-7777-4777-8777-777777777777';
const response=(status,data,header=id)=>({status,ok:status<400,headers:new Headers({'X-Request-ID':header}),json:async()=>data});
(async()=>{
 let settings,c=setup(async(u,o)=>{settings=o;return response(200,[1,2]);});
 check(JSON.stringify(await c.api('/api/test'))==='[1,2]','success arrays preserved');
 check(settings.credentials==='same-origin'&&!settings.headers['X-CSRF-Token'],'same origin read unchanged');
 await c.api('/api/test',{method:'PUT',body:{a:1}});check(settings.headers['X-CSRF-Token']==='qa-csrf'&&settings.body==='{"a":1}','write csrf and json preserved');
 c=setup(async()=>response(500,{message:'服务暂时遇到问题，请稍后重试',code:'SERVER_ERROR',request_id:id}));
 await assert.rejects(c.api('/api/test'),e=>e.status===500&&e.requestId===id&&e.message.includes(id));checks.push('server error visible identifier');
 c=setup(async()=>response(400,{message:'请检查输入',code:'INVALID_REQUEST',request_id:id}));
 await assert.rejects(c.api('/api/test'),e=>e.status===400&&e.message==='请检查输入'&&e.code==='INVALID_REQUEST');checks.push('expected validation remains concise');
 c=setup(async()=>{throw new TypeError('browser internal detail secret');});
 await assert.rejects(c.api('/api/test'),e=>e.code==='NETWORK_ERROR'&&e.message.includes('网络连接失败')&&!e.message.includes('secret'));checks.push('network exception translated');
 let attempts=0;c=setup((u,o)=>new Promise((resolve,reject)=>{attempts++;o.signal.addEventListener('abort',()=>reject(new DOMException('aborted','AbortError')),{once:true});}));
 await assert.rejects(c.api('/api/test',{method:'POST',body:{a:1},timeoutMs:10}),e=>e.code==='REQUEST_TIMEOUT'&&e.message.includes('确认操作结果'));check(attempts===1,'timeout never retries mutation');
 let aborter=new AbortController();c=setup((u,o)=>new Promise((resolve,reject)=>o.signal.addEventListener('abort',()=>reject(new DOMException('aborted','AbortError')),{once:true})));
 let task=c.api('/api/test',{signal:aborter.signal});aborter.abort();await assert.rejects(task,e=>e.name==='AbortError'&&e.code==='REQUEST_CANCELLED');checks.push('caller cancellation distinguished');
 c=setup(async()=>({status:200,ok:true,headers:new Headers({'X-Request-ID':id}),json:async()=>{throw new SyntaxError('html');}}));
 await assert.rejects(c.api('/api/test'),e=>e.code==='INVALID_RESPONSE'&&e.status===502&&e.message.includes(id));checks.push('HTML success is not a successful JSON save');
 c=setup(async()=>response(401,{message:'请先登录',code:'AUTH_REQUIRED',request_id:id}),{id:1});await assert.rejects(c.api('/api/test'),e=>e.status===401);check(c.recovered===1,'expired-session recovery retained');
 c=setup(async()=>response(204,null));check(JSON.stringify(await c.api('/api/test'))==='{}','204 supported');
 c=setup(async()=>response(500,{message:'服务问题',code:'SERVER_ERROR',request_id:'<script>secret</script>'},'bad'));await assert.rejects(c.api('/api/test'),e=>e.requestId===null&&!e.message.includes('script'));checks.push('only valid server identifiers displayed');
 console.log('PASS',checks.length,'actual-source request error assertions');
})().catch(e=>{console.error(e);process.exitCode=1;});

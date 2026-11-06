// No npm dependencies: Node >=20 with --experimental-websocket, Edge/Chrome already installed.
import {createServer} from 'node:http';
import {spawn,spawnSync} from 'node:child_process';
import {readFileSync,writeFileSync,existsSync,mkdirSync,mkdtempSync,rmSync,realpathSync} from 'node:fs';
import {resolve,join,sep,extname} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const root=resolve('docs/api/site'),target=resolve('target');mkdirSync(target,{recursive:true});
rmSync(join(target,'swagger-static-validation.json'),{force:true});
const candidates=process.platform==='win32'?[
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe','C:/Program Files/Microsoft/Edge/Application/msedge.exe'
]:['/usr/bin/google-chrome','/usr/bin/google-chrome-stable','/usr/bin/chromium','/usr/bin/chromium-browser'];
const browserPath=process.env.ANITEC_DOCS_BROWSER||candidates.find(existsSync);
assert(browserPath&&existsSync(browserPath),'An installed Edge/Chrome executable is required; no browser is downloaded');
assert.equal(typeof WebSocket,'function','Run Node with --experimental-websocket');
const spec=JSON.parse(readFileSync(join(root,'openapi-public.json'),'utf8'));
const expected=Object.values(spec.paths).reduce((n,p)=>n+Object.keys(p).filter(k=>['get','post','put','patch','delete'].includes(k)).length,0);
const assets=new Set(['/index.html','/vendor/swagger-ui.css','/site.css','/vendor/swagger-ui-bundle.js','/init.js','/openapi-public.json']);
const served=[],pageRequests=[],errors=[];
const server=createServer((req,res)=>{
  const path=new URL(req.url,'http://localhost').pathname;served.push({method:req.method,path});
  if(!assets.has(path)||req.method!=='GET'){res.writeHead(410);res.end();return;}
  const file=resolve(root,'.'+path);assert(file.startsWith(root+sep));
  const types={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json; charset=utf-8'};
  res.writeHead(200,{'Content-Type':types[extname(file)]||'text/plain','Cache-Control':'no-store'});res.end(readFileSync(file));
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));const origin=`http://127.0.0.1:${server.address().port}`;
// Keep the disposable profile inside the writable, ignored workspace target directory.
const profile=mkdtempSync(join(target,'anitec-docs-browser-'));
let child,ws,nextId=0;const pending=new Map();
const send=(method,params={})=>new Promise((resolve,reject)=>{
  const id=++nextId;const timer=setTimeout(()=>{pending.delete(id);reject(new Error('Browser command timed out: '+method));},15000);
  pending.set(id,{resolve:r=>{clearTimeout(timer);resolve(r);},reject});ws.send(JSON.stringify({id,method,params}));
});
async function evaluate(expression){const r=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});assert(!r.exceptionDetails,'Browser evaluation failed');return r.result.value;}
async function until(test,seconds=30){for(let i=0;i<seconds*5;i++){if(await test())return;await sleep(200);}throw new Error('Static UI did not reach the expected state');}
try {
  const args=['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--disable-background-networking','--disable-component-update','--disable-sync','--disable-extensions',`--user-data-dir=${profile}`,'--remote-debugging-port=0','about:blank'];
  if(process.platform!=='win32'&&process.env.CI==='true')args.push('--no-sandbox'); // Isolated CI browser, never backend policy.
  child=spawn(browserPath,args,{windowsHide:true,stdio:['ignore','ignore','pipe']});child.stderr.resume();
  const portFile=join(profile,'DevToolsActivePort');await until(()=>Promise.resolve(existsSync(portFile)),20);
  const port=readFileSync(portFile,'utf8').split(/\r?\n/)[0];
  const pages=await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  ws=new WebSocket(pages.find(p=>p.type==='page').webSocketDebuggerUrl);
  await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
  ws.onmessage=e=>{
    const message=JSON.parse(e.data);if(message.id){const p=pending.get(message.id);pending.delete(message.id);if(p)message.error?p.reject(new Error('Browser protocol error')):p.resolve(message.result);}
    if(message.method==='Network.requestWillBeSent'&&/^https?:/.test(message.params.request.url))pageRequests.push({method:message.params.request.method,url:message.params.request.url});
    if(message.method==='Runtime.exceptionThrown')errors.push('Uncaught browser exception');
  };
  await send('Page.enable');await send('Network.enable');await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  // URL configuration injection must not replace the reviewed static copy or enable execution.
  await send('Page.navigate',{url:origin+'/index.html?url=https://untrusted.example.invalid/spec.json&tryItOutEnabled=true'});
  await until(()=>evaluate(`document.querySelectorAll('.opblock').length===${expected}`));
  await evaluate(`(()=>{const b=Array.from(document.querySelectorAll('.opblock')).find(b=>b.querySelector('.opblock-summary-path')?.textContent.trim()==='/api/v1/animals'&&b.classList.contains('opblock-get'));b.querySelector('.opblock-summary-control').click();b.scrollIntoView();})()`);
  await until(()=>evaluate(`document.querySelectorAll('.opblock.is-open .parameters tr').length>1`));
  const navigation=await evaluate(`({operations:document.querySelectorAll('.opblock').length,models:document.querySelectorAll('.models .model-container').length,parameterRows:document.querySelectorAll('.opblock.is-open .parameters tr').length,executeButtons:Array.from(document.querySelectorAll('button')).filter(b=>/^(Try it out|Execute)$/.test(b.textContent.trim())).length})`);
  assert.equal(navigation.operations,expected);assert(navigation.models>0);assert(navigation.parameterRows>1);assert.equal(navigation.executeButtons,0);
  const config=await evaluate(`(()=>{const c=ui.getConfigs();let blocks=false;try{c.requestInterceptor({url:'https://api.example.invalid/api/v1/auth/login',method:'POST'});}catch{blocks=true;}return {submit:c.supportedSubmitMethods,tryItOut:c.tryItOutEnabled,query:c.queryConfigEnabled,validator:c.validatorUrl,blocks};})()`);
  assert.deepEqual(config,{submit:[],tryItOut:false,query:false,validator:null,blocks:true});
  const image=await send('Page.captureScreenshot',{format:'png'});writeFileSync(join(target,'swagger-static-operations.png'),Buffer.from(image.data,'base64'));
  await evaluate(`(()=>{const m=Array.from(document.querySelectorAll('.models .model-container')).find(m=>m.textContent.trim().startsWith('AccountProfile'));m.scrollIntoView();m.querySelector('button').click();})()`);
  await until(()=>evaluate(`Array.from(document.querySelectorAll('.models .model-container')).find(m=>m.textContent.trim().startsWith('AccountProfile')).textContent.includes('emailVerified')`));
  const modelsImage=await send('Page.captureScreenshot',{format:'png'});writeFileSync(join(target,'swagger-static-models.png'),Buffer.from(modelsImage.data,'base64'));
  await sleep(500);
  const external=pageRequests.filter(r=>new URL(r.url).origin!==origin);
  const operational=pageRequests.filter(r=>r.method!=='GET'||!assets.has(new URL(r.url).pathname));
  assert.deepEqual(external,[]);assert.deepEqual(operational,[]);assert.deepEqual(errors,[]);
  assert(served.every(r=>r.method==='GET'&&assets.has(r.path)));
  let fileProtocolPreview='NOT_REQUESTED';
  if(process.argv.includes('--check-file-preview')){
    await send('Page.navigate',{url:pathToFileURL(join(root,'index.html')).href});
    await until(()=>evaluate(`document.querySelector('#swagger-ui')?.textContent.includes('No se pudo cargar la referencia. Utilice un servidor HTTP estático local.')`));
    fileProtocolPreview='EXPECTED_ERROR_USE_HTTP';
  }
  const evidence={status:'PASS',operations:expected,navigation,config,externalRequests:external.length,operationalRequests:operational.length,fileProtocolPreview,requests:served};
  writeFileSync(join(target,'swagger-static-validation.json'),JSON.stringify(evidence,null,2)+'\n');
} catch(error) {
  console.error('Static Swagger validation failed: '+error.message);
  if(ws?.readyState===1){try{const debug=await evaluate(`({title:document.title,operations:document.querySelectorAll('.opblock').length,models:document.querySelectorAll('.models .model-container').length,open:document.querySelectorAll('.opblock.is-open').length,parameterRows:document.querySelectorAll('.opblock.is-open .parameters tr').length})`);writeFileSync(join(target,'swagger-static-failure.json'),JSON.stringify(debug,null,2));const shot=await send('Page.captureScreenshot',{format:'png'});writeFileSync(join(target,'swagger-static-failure.png'),Buffer.from(shot.data,'base64'));}catch{}}
  throw error;
} finally {
  if(ws?.readyState===1){try{await send('Browser.close');}catch{}ws.close();}
  if(child&&child.exitCode===null){await Promise.race([new Promise(r=>child.once('close',r)),sleep(5000)]);if(child.exitCode===null){if(process.platform==='win32')spawnSync('taskkill',['/PID',String(child.pid),'/T','/F'],{windowsHide:true,stdio:'ignore'});else child.kill('SIGTERM');}}
  for(const p of pending.values())p.reject(new Error('Browser closed'));pending.clear();
  await new Promise(r=>server.close(r));
  // Verify the owned, resolved absolute temporary path before recursive deletion; no shared profile.
  const owned=realpathSync(profile),parent=realpathSync(target);assert(owned.startsWith(parent+sep+'anitec-docs-browser-'));
  rmSync(owned,{recursive:true,force:true,maxRetries:20,retryDelay:250});
}
const finalEvidence=JSON.parse(readFileSync(join(target,'swagger-static-validation.json'),'utf8'));
finalEvidence.cleanup=true;writeFileSync(join(target,'swagger-static-validation.json'),JSON.stringify(finalEvidence,null,2)+'\n');
console.log(JSON.stringify({status:'PASS',operations:finalEvidence.operations,models:finalEvidence.navigation.models,operationalRequests:0,externalRequests:0,cleanup:true}));

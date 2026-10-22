const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const { createGraphProjection } = require('../media/graph-state');
const draft = () => ({name:'Synthetic workflow',steps:[{id:'start',type:'START'},{id:'end',type:'END',preconditionStepId:'start'}]});
const state = (id='root', definition=draft()) => ({rootId:'root',currentId:id,following:true,loading:false,history:[],warnings:[],logs:[],children:{content:[],page:0,totalPages:0},
  graph:{definition,process:{id,name:definition.name,definitionVersion:1,status:'COMPLETED',variables:{}},overlay:{start:{state:'COMPLETED'},end:{state:'COMPLETED'}},steps:[]}});
const plain = x => JSON.parse(JSON.stringify(x));

test('draft changes keep captured execution states only on unmodified nodes and retain layout edits',()=>{
  const p=createGraphProjection(), original=draft();p.setDraft(original);p.capture('root',original);p.update(state());
  const edited=draft();edited.steps.push({id:'new',type:'AGENT',agent:{ref:'synthetic-agent'}});edited.steps[1].name='Changed end';edited.layout={start:{x:100,y:200}};
  p.setDraft(edited);const view=p.view();
  assert.equal(view.readOnly,false);assert.deepEqual(JSON.parse(view.overlay),{start:{state:'COMPLETED'}});
  assert.deepEqual(JSON.parse(view.value),edited);assert.match(view.notice,/Draft changed/);
  p.showSnapshot();assert.equal(p.view().readOnly,true);assert.deepEqual(JSON.parse(p.view().value),original);
  p.showDraft();assert.deepEqual(JSON.parse(p.view().value),edited);
});

test('children, history and latest continuations use their effective snapshot without replacing the draft',()=>{
  const p=createGraphProjection();p.setDraft(draft());p.capture('root',draft());p.update(state());
  const child={name:'Child v2',steps:[{id:'child-only',type:'START'}]};
  p.update({...state('child',child),graph:undefined});assert.equal(p.view().readOnly,true);
  p.update(state('child',child));assert.equal(p.view().readOnly,true);assert.deepEqual(JSON.parse(p.view().value),child);
  p.showDraft();assert.deepEqual(JSON.parse(p.view().value),draft());assert.deepEqual(JSON.parse(p.view().overlay),{});
  p.update(state('continued-latest',{...child,name:'Latest version'}));assert.equal(p.view().readOnly,true);
  p.update(state());assert.equal(p.view().readOnly,false);assert.deepEqual(JSON.parse(p.view().overlay),state().graph.overlay);
});

test('layout changes and object key order do not mark executed steps as modified',()=>{
  const p=createGraphProjection();p.capture('root',draft());p.setDraft({steps:draft().steps,name:'Synthetic workflow',layout:{start:{x:12,y:34}}});p.update(state());
  assert.deepEqual(JSON.parse(p.view().overlay),state().graph.overlay);assert.doesNotMatch(p.view().notice,/Draft changed/);
});

function bridge() {
  class Node {
    constructor() {this.listeners=new Map();this.attrs=new Map();this.children=[];this.style={};this.hidden=false;this.disabled=false;this.value='';this.writes=0;this.textContent='';}
    addEventListener(type,fn){const list=this.listeners.get(type)||[];list.push(fn);this.listeners.set(type,list);}
    emit(type,event={}){for(const fn of this.listeners.get(type)||[])fn(event);}
    setAttribute(k,v){this.attrs.set(k,v);if(k==='value')this.writes++;}getAttribute(k){return this.attrs.get(k);}removeAttribute(k){this.attrs.delete(k);}
    append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=[...nodes];}
  }
  const nodes=new Map(), graph=new Node(), posts=[], listeners=[];
  const byId=id=>{if(!nodes.has(id))nodes.set(id,new Node());return nodes.get(id);};
  const tabs=['steps','data','logs','related'].map(name=>{const n=new Node();n.dataset={tab:name};return n;});
  const window={addEventListener:(type,fn)=>{if(type==='message')listeners.push(fn);}};
  const context={window,document:{querySelector:()=>graph,getElementById:byId,createElement:()=>new Node(),querySelectorAll:()=>tabs,body:{classList:{contains:()=>false}}},
    acquireVsCodeApi:()=>({postMessage:m=>posts.push(plain(m))}),MutationObserver:class{observe(){}},setTimeout,
    customElements:{whenDefined:()=>Promise.resolve()}};
  vm.createContext(context);
  for(const name of ['graph-state.js','execution.js','main.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'../media',name),'utf8'),context);
  return {graph,posts,byId,receive:async msg=>{for(const fn of listeners)await fn({data:plain(msg)});}};
}

test('the actual editor bridge updates execution overlays while edits keep going to the document',async()=>{
  const b=bridge();await b.receive({type:'setValue',value:JSON.stringify(draft())});
  await new Promise(resolve=>setTimeout(resolve,1));
  await b.receive({type:'executionSnapshot',id:'root',definition:draft()});await b.receive({type:'execution',state:state()});
  const before=b.graph.writes;
  const edited=draft();edited.steps.push({id:'new',type:'TIMER',duration:'PT1S'});
  b.graph.setAttribute('value',JSON.stringify(edited));b.graph.emit('value-changed',{detail:{value:JSON.stringify(edited)}});
  assert.deepEqual(JSON.parse(b.posts.at(-1).value),edited);
  await b.receive({type:'execution',state:state()});
  assert.equal(b.graph.readOnly,false);assert.equal(b.graph.writes,before+1);
  assert.deepEqual(JSON.parse(b.graph.getAttribute('value')),edited);assert.match(b.byId('draft-notice').textContent,/Draft changed/);
  b.byId('snapshot-view').emit('click');assert.equal(b.graph.readOnly,true);
  const edits=b.posts.filter(p=>p.type==='edit').length;
  b.graph.emit('value-changed',{detail:{value:JSON.stringify(draft())}});assert.equal(b.posts.filter(p=>p.type==='edit').length,edits);
  b.byId('draft-view').emit('click');assert.equal(b.graph.readOnly,false);assert.deepEqual(JSON.parse(b.graph.getAttribute('value')),edited);
});

test('preparing inputs and an uncertain retry remain intact while an older execution continues polling',async()=>{
  const b=bridge();await b.receive({type:'setValue',value:JSON.stringify(draft())});
  await b.receive({type:'executionSnapshot',id:'root',definition:draft()});await b.receive({type:'execution',state:state()});
  await b.receive({type:'variables',value:'{"caseId":""}'});b.byId('variables').value='{"caseId":"typed"}';
  await b.receive({type:'execution',state:state()});assert.equal(b.byId('input').hidden,false);assert.equal(b.byId('variables').value,'{"caseId":"typed"}');
  await b.receive({type:'submitting',id:'second'});await b.receive({type:'submissionError',id:'second',uncertain:true,message:'Disconnected'});
  await b.receive({type:'execution',state:state()});assert.equal(b.byId('uncertain').hidden,false);assert.equal(b.byId('variables').disabled,true);assert.equal(b.byId('execution-error').textContent,'Disconnected');
  b.byId('retry').emit('click');assert.deepEqual(b.posts.at(-1),{type:'retrySubmission'});
  await b.receive({type:'executionSnapshot',id:'second',definition:draft()});await b.receive({type:'execution',state:state('second')});
  assert.equal(b.byId('uncertain').hidden,true);assert.equal(b.byId('input').hidden,true);
});

test('Stop targets the engine through the host, keeps the graph editable, and waits for server confirmation',async()=>{
  const b=bridge();await b.receive({type:'setValue',value:JSON.stringify(draft())});
  const running=state();running.graph.process.status='RUNNING';
  await b.receive({type:'executionSnapshot',id:'root',definition:draft()});await b.receive({type:'execution',state:running});
  assert.equal(b.byId('stop').disabled,false);assert.equal(b.graph.hostExecutionActive,true);
  b.byId('collapse-execution').emit('click');assert.equal(b.byId('execution-dock').hidden,true);
  b.graph.emit('ec-stop-workflow',{detail:{id:'forged-target'}});assert.deepEqual(b.posts.at(-1),{type:'stopRun'});assert.equal(b.byId('execution-dock').hidden,false);
  await b.receive({type:'cancellation',state:{phase:'accepted',id:'root',pending:true,message:'Waiting for engine…'}});
  assert.equal(b.byId('stop').disabled,true);assert.equal(b.graph.hostExecutionStopping,true);assert.equal(b.graph.readOnly,false);
  assert.equal(running.graph.process.status,'RUNNING');assert.doesNotMatch(b.byId('cancellation-status').textContent,/confirmed/);
  await b.receive({type:'cancellation',state:{phase:'unknown',id:'root',pending:false,message:'Not confirmed'}});
  assert.equal(b.byId('stop').disabled,false);assert.match(b.byId('cancellation-status').textContent,/Not confirmed/);
  const cancelled=state();cancelled.graph.process.status='CANCELLED';await b.receive({type:'execution',state:cancelled});assert.equal(b.byId('stop').disabled,true);
});
test('historical and ended views cannot send Stop, while a followed child can be stopped explicitly',async()=>{
  const b=bridge(),history=state();history.following=false;history.graph.process.status='RUNNING';
  await b.receive({type:'execution',state:history});assert.equal(b.byId('stop').disabled,true);
  b.graph.emit('ec-stop-workflow');assert.ok(!b.posts.some(p=>p.type==='stopRun'));
  const child=state('child');child.graph.process.status='PAUSED';await b.receive({type:'execution',state:child});assert.equal(b.byId('stop').disabled,false);
  const ended=state('child');await b.receive({type:'execution',state:ended});assert.equal(b.byId('stop').disabled,true);
});
